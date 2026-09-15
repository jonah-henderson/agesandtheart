package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.Resolution
import co.voik.agesandtheart.age.word.grammar.Sentence
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * The desk's menu: real slots for the things you hand it, everything else on a payload.
 *
 * The hybrid the design settled on. Slots exist so pages can be shift-clicked in and a hopper could one
 * day feed it; the archive, the tanks and the composer have no slot shape and travel as
 * [DeskSyncPayload] instead.
 *
 * **The composer lives here, not on the block entity**, because a half-written sentence belongs to the
 * session rather than to the furniture — and [removed] hands its pages back so closing the screen can
 * never eat them.
 */
class WritersDeskMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
) : AbstractContainerMenu(AgeContent.WRITERS_DESK_MENU, containerId) {

    /** Where a finished book lands, so binding produces something you take rather than something you find. */
    private val output: Container = SimpleContainer(1)

    /**
     * Which tab the screen is showing.
     *
     * The menu needs it because slots cannot move — `Slot.x` and `y` are final — so each slot sits where
     * its own tab wants it and is simply *inactive* on the others. The client says which tab it is on.
     */
    var openTab: Int = 0

    /** Words laid out, in order. Order is word order, so this is a list and never a set. */
    val composing: MutableList<Identifier> = mutableListOf()

    /** Mirrors `DeskTab.showsInventory` on the client; the two must agree. */
    private val showsPlayerInventory: Boolean get() = openTab == ARCHIVE_TAB

    init {
        // No doorway here any more: supplies are the wings' business, and what a page or a notebook does
        // when shift-clicked at the centre is go straight to the archive (see [handToDesk]).
        addSlot(object : Slot(output, 0, OUTPUT_X, OUTPUT_Y) {
            /** Take-only: a finished book is produced here, never placed here. */
            override fun mayPlace(stack: ItemStack): Boolean = false
            override fun isActive(): Boolean = openTab == BIND_TAB
        })
        for (row in 0 until 3) {
            for (column in 0 until 9) {
                addSlot(
                    object : Slot(
                        playerInventory,
                        column + row * 9 + 9,
                        INVENTORY_X + column * 18,
                        INVENTORY_Y + row * 18,
                    ) {
                        override fun isActive(): Boolean = showsPlayerInventory
                    },
                )
            }
        }
        for (column in 0 until 9) {
            addSlot(
                object : Slot(playerInventory, column, INVENTORY_X + column * 18, HOTBAR_Y) {
                    override fun isActive(): Boolean = showsPlayerInventory
                },
            )
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        val playerSlots = FIRST_PLAYER_SLOT until slots.size
        val ours = 0 until FIRST_PLAYER_SLOT

        if (index in playerSlots) {
            val handed = handToDesk(player, slot, moved) ?: return ItemStack.EMPTY
            return handed
        }

        val destination = if (index in ours) playerSlots else ours
        if (!moveItemStackTo(moved, destination.first, destination.last + 1, index in ours)) {
            return ItemStack.EMPTY
        }
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    /**
     * Hands [moved] straight to the desk's stores.
     *
     * @return what was taken, or null if the desk wanted none of it.
     */
    private fun handToDesk(player: Player, slot: Slot, moved: ItemStack): ItemStack? {
        val serverPlayer = player as? ServerPlayer ?: return null
        val desk = deskOf(serverPlayer) ?: return null
        val original = moved.copy()
        val result = DeskIntake.offer(desk, moved)
        if (!result.took) return null
        slot.setByPlayer(result.remainder)
        if (!result.returned.isEmpty && !player.inventory.add(result.returned)) {
            player.drop(result.returned, false)
        }
        DeskCommands.sync(serverPlayer, this, desk)
        return original
    }

    override fun stillValid(player: Player): Boolean =
        access.evaluate({ level, pos -> level.getBlockState(pos).block is WritersDeskBlock }, true)

    /**
     * Closing hands back what is in a slot — and **leaves the laid-out pages where they were laid**.
     *
     * They used to go back into the archive, which meant stepping away to fetch one page cost the sentence
     * you were building. A row of pages is a half-made argument; it stays on the desk that holds it, under
     * the name of whoever laid it, and comes back when they do.
     */
    override fun removed(player: Player) {
        super.removed(player)
        access.execute { _, _ ->
            val serverPlayer = player as? ServerPlayer ?: return@execute
            deskOf(serverPlayer)?.setComposition(serverPlayer.uuid, composing)
            composing.clear()
            val held = output.removeItemNoUpdate(0)
            if (!held.isEmpty && !player.inventory.add(held)) player.drop(held, false)
        }
    }

    /** Wrapped in an Optional because `evaluate` will not carry a nullable result. */
    fun deskOf(player: ServerPlayer): WritersDeskBlockEntity? =
        access.evaluate(
            { level, pos -> Optional.ofNullable(WritersDeskBlock.entityAt(level, pos)) },
            Optional.empty(),
        ).orElse(null)

    fun outputIsFree(): Boolean = output.getItem(0).isEmpty

    fun putOutput(stack: ItemStack) {
        output.setItem(0, stack)
    }

    /** What the screen should be showing right now. */
    fun snapshot(player: ServerPlayer, desk: WritersDeskBlockEntity, capabilities: DeskState): DeskSyncPayload {
        // Read once and handed to both: the readout and the conflicts are two questions about one sentence,
        // and parsing it twice was two passes over the corpus for one row of pages. Resolving it is dearer
        // still and only the conflicts ask for that — so it is done at most once, and only where something
        // in the room can show what it says.
        val said = composing.takeIf { it.isNotEmpty() }
            ?.let { Grammar.read(vocabularyFor(player), it.map(Identifier::getPath)) }
        val resolved = said
            ?.takeIf { anythingReadsAResolution(capabilities) }
            ?.let { Resolver.resolve(vocabularyFor(player), it, player.writingSeed) }
        return DeskSyncPayload(
            archive = desk.archive.words.associateWith { desk.archive.count(it) },
            ink = InkTier.entries.associateWith { desk.stores.ink(it) },
            paper = InkTier.entries.associateWith { desk.stores.paper(it) },
            binding = desk.stores.binding(),
            inkCapacity = desk.inkCapacity,
            capabilities = capabilities.capabilities,
            pageLimit = capabilities.pageLimit,
            composing = composing.toList(),
            quarrels = quarrelsIn(capabilities, resolved),
            reading = readingOf(capabilities, said),
        )
    }

    /** Only the conflicts now: the survey moved to the geologist's tools and took its reason with it. */
    private fun anythingReadsAResolution(capabilities: DeskState): Boolean =
        DeskCapability.REVEAL_CONFLICTS in capabilities.capabilities

    /**
     * The sentence said back as prose — **empty without the implement that reads it**, exactly as the
     * conflicts are (design §7.3): visibility is a property of the workspace, so a bare desk tells a writer
     * nothing about what they have written and a furnished one tells them everything.
     *
     * `DeskCapability.READABLE_GRAMMAR` was defined, granted by the grammar guide, and consulted nowhere,
     * so the desk handed over the full reading whatever was in the room. Wired now (Jonah, 2026-08-07): an
     * early writer learns what their words do by writing an Age and going to look at it, and the guide is
     * what turns that trial and error into a thing you can read before you spend the ink.
     *
     * **The gate is on the desk's live preview and nothing else.** A bound book still carries its own
     * reading and a found one still teaches — the grammar is learned by reading somebody else's book
     * (§4.5), and gating that would close the only door it comes through.
     */
    private fun readingOf(capabilities: DeskState, said: Sentence?): String {
        if (DeskCapability.READABLE_GRAMMAR !in capabilities.capabilities) return ""
        return said?.let(Readout::of).orEmpty()
    }

    /**
     * What is wrong with the sentence as it currently stands — **empty without the implement that reveals
     * it**, which is what `DeskCapability.REVEAL_CONFLICTS` is for (design §7.3): visibility is a property
     * of the workspace, so a bare desk shows a writer nothing and a furnished one shows them everything.
     *
     * Resolved against [writingSeed], so the answer is the one the bound book will actually produce rather
     * than one of the answers it might have.
     *
     * Each flaw becomes a mark on **both** its words. A flaw naming one word is paired with itself, which
     * is how "nothing here can be this" reaches a display that only knows how to mark pairs.
     */
    private fun quarrelsIn(capabilities: DeskState, resolved: Resolution?): List<Quarrel> {
        if (DeskCapability.REVEAL_CONFLICTS !in capabilities.capabilities) return emptyList()
        val flaws = resolved?.instability?.flaws ?: return emptyList()
        val byName = composing.associateBy { it.path }
        return flaws.flatMap { flaw ->
            val named = flaw.words.mapNotNull(byName::get)
            val first = named.firstOrNull() ?: return@flatMap emptyList()
            val second = named.getOrNull(1) ?: first
            if (first == second) {
                listOf(Quarrel(first, first, flaw.register.key))
            } else {
                listOf(Quarrel(first, second, flaw.register.key), Quarrel(second, first, flaw.register.key))
            }
        }
    }

    companion object {
        /** Tab ordinals, shared with the screen's `DeskTab` — the menu only needs to compare them. */
        const val ARCHIVE_TAB = 0
        const val BIND_TAB = 2

        // Positions live in `DeskSlots`, because the screen draws a recess behind every one of them and the
        // two must agree. Aliased here only so the slot declarations above stay readable.
        private const val OUTPUT_X = DeskSlots.OUTPUT_X
        private const val OUTPUT_Y = DeskSlots.OUTPUT_Y
        private const val INVENTORY_X = DeskSlots.INVENTORY_X
        private const val INVENTORY_Y = DeskSlots.INVENTORY_Y
        private const val HOTBAR_Y = DeskSlots.HOTBAR_Y

        /** Our one slot comes first, so everything from here is the player's. */
        private const val FIRST_PLAYER_SLOT = 1

        fun vocabularyFor(player: ServerPlayer): Vocabulary = Vocabulary.of(player.level().server)

        fun open(player: ServerPlayer, pos: BlockPos) {
            player.openMenu(DeskMenuProvider(pos))
        }
    }
}
