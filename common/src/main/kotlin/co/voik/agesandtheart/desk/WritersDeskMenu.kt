package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.learnedWords
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.platform.Services
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

    /** Whoever has this open. Taken from the inventory, so it is never unset. */
    private val owner: Player = playerInventory.player

    /** Anything the desk understands, routed by [DeskIntake] the moment it lands. */
    private val intake: Container = SimpleContainer(1)

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
    private val showsPlayerInventory: Boolean get() = openTab == SUPPLIES_TAB

    init {
        // The general doorway: everything the desk understands, binding included, on the supplies tab.
        addSlot(object : Slot(intake, 0, INTAKE_X, INTAKE_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = isActive && DeskIntake.accepts(stack)
            override fun isActive(): Boolean = openTab == SUPPLIES_TAB
        })
        addSlot(object : Slot(output, 0, OUTPUT_X, OUTPUT_Y) {
            /** Take-only: a finished book is produced here, never placed here. */
            override fun mayPlace(stack: ItemStack): Boolean = false
            override fun isActive(): Boolean = openTab == BOOK_TAB
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

    /** @return whether anything was taken, so the caller only re-syncs when there is news. */
    fun drainIntake(player: ServerPlayer): Boolean {
        val desk = deskOf(player) ?: return false
        val offered = intake.getItem(0)
        if (offered.isEmpty) return false
        val result = DeskIntake.offer(desk, offered)
        if (!result.took) return false
        intake.setItem(0, result.remainder)
        if (!result.returned.isEmpty && !player.inventory.add(result.returned)) {
            player.drop(result.returned, false)
        }
        return true
    }

    /**
     * Anything in the intake slot is swallowed here.
     *
     * **Not `slotsChanged`**, which never fires for this: `SimpleContainer.setChanged()` is empty, and a
     * menu only hears about a container it was explicitly wired into — vanilla's crafting container holds
     * its menu and calls `slotsChanged` by hand. `broadcastChanges` runs every tick for the open menu, so
     * the doorway empties within a tick of something landing in it.
     */
    override fun broadcastChanges() {
        val player = owner as? ServerPlayer
        if (player != null && !intake.getItem(0).isEmpty) {
            if (drainIntake(player)) {
                deskOf(player)?.let { DeskCommands.sync(player, this, it) }
            }
        }
        super.broadcastChanges()
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        val playerSlots = FIRST_PLAYER_SLOT until slots.size
        val ours = 0 until FIRST_PLAYER_SLOT
        val destination = if (index in ours) playerSlots else ours
        if (!moveItemStackTo(moved, destination.first, destination.last + 1, index in ours)) {
            return ItemStack.EMPTY
        }
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean =
        access.evaluate({ level, pos -> level.getBlockState(pos).block is WritersDeskBlock }, true)

    /** Closing puts everything back: what is in a slot to the player, the laid-out pages to the archive. */
    override fun removed(player: Player) {
        super.removed(player)
        access.execute { _, _ ->
            val serverPlayer = player as? ServerPlayer ?: return@execute
            val desk = deskOf(serverPlayer)
            if (desk != null) {
                for (word in composing) desk.addPages(word, 1)
            }
            composing.clear()
            for (container in listOf(intake, output)) {
                val held = container.removeItemNoUpdate(0)
                if (!held.isEmpty && !player.inventory.add(held)) player.drop(held, false)
            }
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
    fun snapshot(player: ServerPlayer, desk: WritersDeskBlockEntity, capabilities: DeskState): DeskSyncPayload =
        DeskSyncPayload(
            archive = desk.archive.words.associateWith { desk.archive.count(it) },
            ink = InkTier.entries.associateWith { desk.stores.ink(it) },
            paper = InkTier.entries.associateWith { desk.stores.paper(it) },
            binding = desk.stores.binding(),
            inkCapacity = desk.inkCapacity,
            capabilities = capabilities.capabilities,
            pageLimit = capabilities.pageLimit,
            composing = composing.toList(),
        )

    /** Whether the player may write [word] at all — knowing it is the first gate (design §7.1.1). */
    fun knows(player: ServerPlayer, word: Identifier): Boolean = player.learnedWords.knows(word)

    companion object {
        /** Tab ordinals, shared with the screen's `DeskTab` — the menu only needs to compare them. */
        const val BOOK_TAB = 2
        const val SUPPLIES_TAB = 3

        // Positions live in `DeskSlots`, because the screen draws a recess behind every one of them and the
        // two must agree. Aliased here only so the slot declarations above stay readable.
        private const val INTAKE_X = DeskSlots.INTAKE_X
        private const val INTAKE_Y = DeskSlots.INTAKE_Y
        private const val OUTPUT_X = DeskSlots.OUTPUT_X
        private const val OUTPUT_Y = DeskSlots.OUTPUT_Y
        private const val INVENTORY_X = DeskSlots.INVENTORY_X
        private const val INVENTORY_Y = DeskSlots.INVENTORY_Y
        private const val HOTBAR_Y = DeskSlots.HOTBAR_Y

        /** Our two slots come first, so everything from here is the player's. */
        private const val FIRST_PLAYER_SLOT = 2

        fun vocabularyFor(player: ServerPlayer): Vocabulary = Vocabulary.of(player.level().server)

        fun unitsPerBucket(): Long = Services.INK_FLUIDS.unitsPerBucket

        fun open(player: ServerPlayer, pos: BlockPos) {
            player.openMenu(DeskMenuProvider(pos))
        }
    }
}
