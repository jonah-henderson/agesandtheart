package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.reading.Written
import co.voik.agesandtheart.age.readout.ReadoutClause
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * The desk's menu, which has **no slots at all** (design: the writer's desk redesign).
 *
 * Nothing moves by hand at the desk any more: a template is words, the pages it draws on are taken at the
 * bind without being handled, and the stores are filled through the wings. So everything the screen shows
 * travels on [DeskSyncPayload], and the menu is what anchors it to one desk and closes it when that desk
 * goes.
 */
class WritersDeskMenu(
    containerId: Int,
    @Suppress("UNUSED_PARAMETER") playerInventory: Inventory,
    private val access: ContainerLevelAccess,
) : AbstractContainerMenu(AgeContent.WRITERS_DESK_MENU, containerId) {

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        access.evaluate({ level, pos -> level.getBlockState(pos).block is WritersDeskBlock }, true)

    /** The writer has stopped working on the sentence for now, so a crystal viewer's Age can be made ready. */
    override fun removed(player: Player) {
        super.removed(player)
        val writer = player as? ServerPlayer ?: return
        val desk = deskOf(writer) ?: return
        PreviewedAges.whenTheDeskCloses(writer, desk)
    }

    /** Wrapped in an Optional because `evaluate` will not carry a nullable result. */
    fun deskOf(player: ServerPlayer): WritersDeskBlockEntity? =
        access.evaluate(
            { level, pos -> Optional.ofNullable(WritersDeskBlock.entityAt(level, pos)) },
            Optional.empty(),
        ).orElse(null)

    /** What [player]'s screen should be showing right now. */
    fun snapshot(player: ServerPlayer, desk: WritersDeskBlockEntity, capabilities: DeskState): DeskSyncPayload {
        val text = desk.templateFor(player.uuid)
        val read = DeskTemplates.read(player, text)
        val words = TemplateReading.learnedWords(read)
        val supply = supplyFor(player, desk)
        val allocation = supply.allocate(words)
        // Read once and handed to both: the readout and the conflicts are two questions about one sentence.
        // Resolving it is dearer, so it is done at most once, and only where something in the room can show what
        // it says — the conflicts, or the readout, which says only what held.
        val vocabulary = vocabularyFor(player)
        val pages = words.map(Identifier::getPath)
        val showsWhatResolves = capabilities.capabilities.any { it == DeskCapability.REVEAL_CONFLICTS || it == DeskCapability.READABLE_GRAMMAR }
        val spellsABook = pages.isNotEmpty() && vocabulary.spellsABook(pages)
        val written = if (spellsABook && showsWhatResolves) vocabulary.written(pages, player.writingSeed) else null
        val resolved = written?.resolution
        return DeskSyncPayload(
            ink = InkTier.entries.associateWith { desk.stores.ink(it) },
            paper = InkTier.entries.associateWith { desk.stores.paper(it) },
            binding = desk.stores.binding(),
            inkCapacity = desk.inkCapacity,
            capabilities = capabilities.capabilities,
            pageLimit = capabilities.pageLimit,
            template = text,
            name = desk.nameFor(player.uuid),
            read = read,
            toWrite = allocation.toWrite.map { pricePage(player, it) },
            drawn = allocation.drawn,
            archived = supply.archived(),
            quarrels = quarrelsIn(capabilities, words, resolved),
            reading = readingOf(capabilities, written, vocabulary),
        )
    }

    /**
     * The sentence as the bound book will read it — **empty without the implement that reads it**, exactly as the
     * conflicts are (design §7.3): visibility is a property of the workspace. The gate is on the desk's
     * live reading and nothing else — a bound book still carries its own, and a found one still teaches.
     */
    private fun readingOf(capabilities: DeskState, written: Written?, vocabulary: Vocabulary): List<ReadoutClause> {
        if (DeskCapability.READABLE_GRAMMAR !in capabilities.capabilities) return emptyList()
        return written?.let(vocabulary::readout).orEmpty()
    }

    /**
     * What is wrong with the sentence as it currently stands — **empty without the implement that reveals
     * it** (design §7.3), resolved against [writingSeed] so the answer is the one the bound book will
     * actually produce.
     *
     * Two words that cannot both be what they said are each marked, naming the other. A page that placed nothing is marked alone, as a meaning nothing took, whether it dangled or
     * nothing in the vocabulary could back it — the same to a writer, and only the second a fault of ours. A
     * dangling page's flaw also names the word its meaning was drawn onto, which did nothing wrong and is left
     * unmarked.
     */
    private fun quarrelsIn(capabilities: DeskState, words: List<Identifier>, resolved: Resolution?): List<Quarrel> {
        if (DeskCapability.REVEAL_CONFLICTS !in capabilities.capabilities) return emptyList()
        val flaws = resolved?.instability?.flaws ?: return emptyList()
        val byName = words.associateBy { it.path }
        return flaws.flatMap { flaw ->
            val named = flaw.words.mapNotNull(byName::get)
            val first = named.firstOrNull() ?: return@flatMap emptyList()
            val second = named.getOrNull(1)
            val conflicts = flaw.register == Register.INCOHERENT && second != null && second != first
            if (conflicts) {
                listOf(Quarrel(first, second), Quarrel(requireNotNull(second), first))
            } else {
                listOf(Quarrel(first, against = null))
            }
        }.distinct()
    }

    companion object {
        fun vocabularyFor(player: ServerPlayer): Vocabulary = Vocabulary.of(player.level().server)

        fun open(player: ServerPlayer, pos: BlockPos) {
            player.openMenu(DeskMenuProvider(pos))
        }

        /** The pages a desk can draw on for [player]: the archives in its room, then their own inventory. */
        fun supplyFor(player: ServerPlayer, desk: WritersDeskBlockEntity): PageSupply {
            val radius = WritersDesk.of(player.level().server).radius
            return PageSupply(PageSupply.archivesAround(player.level(), desk.blockPos, radius), player.inventory)
        }

        /**
         * What writing a page of [word] costs on each paper.
         *
         * A structural word — `age`, `and`, a rung — has no referent and so no cost number of its own; it
         * is priced as the cheapest page there is, since the grammar is not what the ink is for, in the ink
         * `art/ink/` lists it under.
         */
        fun pricePage(player: ServerPlayer, word: Identifier): PagePrice {
            val vocabulary = vocabularyFor(player)
            val registries = player.level().registryAccess()
            val units = Services.INK_FLUIDS.unitsPerBucket
            val spelled = vocabulary.word(word.toString()) ?: vocabulary.word(word.path)
                ?: return PagePrice(InkTier.DEFAULT, InkTier.entries.associateWith { WriteCost.priced(LEAST_PRICE, InkTier.DEFAULT, it, units).inkUnits })
            val costs = InkTier.entries.associateWith { paper ->
                WriteCost.of(spelled, vocabulary, registries, paper, units)
            }
            return PagePrice(costs.getValue(InkTier.COMMON).inkTier, costs.mapValues { it.value.inkUnits })
        }

        /** What a page that is no word now costs: the least any page does. */
        private const val LEAST_PRICE = 1
    }
}
