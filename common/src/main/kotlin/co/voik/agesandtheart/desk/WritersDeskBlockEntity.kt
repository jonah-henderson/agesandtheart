package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.platform.Services
import com.mojang.serialization.Codec
import net.minecraft.core.UUIDUtil
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The desk's contents, and its reading of the room.
 *
 * Only the centre block has one of these (see [WritersDeskBlock.newBlockEntity]), so a wing reaches it
 * through [WritersDeskBlock.entityAt] rather than holding anything itself.
 */
class WritersDeskBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.WRITERS_DESK_ENTITY, pos, state) {

    var archive: PageArchive = PageArchive.EMPTY
        private set

    var stores: DeskStores = DeskStores.EMPTY
        private set

    /**
     * The pages each writer has laid out on this desk, in order — **kept between visits**.
     *
     * A composition used to dissolve back into the archive the moment the screen closed, so stepping away
     * to fetch a page cost you the sentence you were building (Jonah, 2026-08-06, walked). Pages laid on a
     * surface stay on it.
     *
     * **Per writer, on the desk.** Two people at one desk share its archive and its ink, which is the
     * point of a desk, but a row of pages is an argument half-made and stomping on somebody else's would
     * be worse than either sharing or forbidding. Keyed by who laid it, kept where it was laid.
     */
    private val compositions = mutableMapOf<UUID, List<Identifier>>()

    fun compositionFor(writer: UUID): List<Identifier> = compositions[writer].orEmpty()

    fun setComposition(writer: UUID, words: List<Identifier>) {
        if (words.isEmpty()) compositions.remove(writer) else compositions[writer] = words.toList()
        setChanged()
    }

    /** Lays [word] in [writer]'s row at [at], or at the end. Where it goes is what it means. */
    fun lay(writer: UUID, word: Identifier, at: Int? = null) {
        val laid = compositionFor(writer).toMutableList()
        laid.add((at ?: laid.size).coerceIn(0, laid.size), word)
        setComposition(writer, laid)
    }

    /** Takes the page at [at] out of [writer]'s row, or null where there is none. */
    fun takeFromComposition(writer: UUID, at: Int): Identifier? {
        val laid = compositionFor(writer).toMutableList()
        if (at !in laid.indices) return null
        val taken = laid.removeAt(at)
        setComposition(writer, laid)
        return taken
    }

    /** Moves [writer]'s page at [from] to [to], clamped, so a drop past the end puts it last. */
    fun moveInComposition(writer: UUID, from: Int, to: Int) {
        val laid = compositionFor(writer).toMutableList()
        if (from !in laid.indices) return
        val landing = to.coerceIn(0, laid.size - 1)
        if (from == landing) return
        laid.add(landing, laid.removeAt(from))
        setComposition(writer, laid)
    }

    /** Every writer's pages together — what a broken desk owes the floor, whoever laid it. */
    val everyComposition: List<Identifier> get() = compositions.values.flatten()

    /**
     * What the room grants, read fresh every time it is asked — see [WritersDesk.survey].
     *
     * This was cached, and the cache was the whole of the bug: `neighborChanged` fires only for blocks
     * *touching* the desk, which is almost nothing inside a radius of five, so an enchanting table set
     * down two blocks away never reached it and the desk reported itself bare for good.
     */
    fun capabilities(desk: WritersDesk): DeskState =
        desk.survey(level ?: return DeskState.bare(null), blockPos)

    fun addPages(word: Identifier, count: Int) {
        if (count <= 0) return
        archive = archive.with(word, count)
        setChanged()
    }

    /** @return whether the archive held that many. */
    fun takePages(word: Identifier, count: Int): Boolean {
        val remaining = archive.without(word, count) ?: return false
        archive = remaining
        setChanged()
        return true
    }

    /** How much one tank holds here. The unit is the loader's, so this is not a constant. */
    val inkCapacity: Long
        get() = Services.INK_FLUIDS.unitsPerBucket * AgeFluids.TANK_CAPACITY_BUCKETS

    fun inkSpace(tier: InkTier): Long = stores.inkSpace(tier, inkCapacity)

    /** @return what would not fit. */
    fun addInk(tier: InkTier, amount: Long): Long {
        val (updated, rejected) = stores.addingInk(tier, amount, inkCapacity)
        stores = updated
        setChanged()
        return rejected
    }

    /** Used by the loaders' fluid interfaces, which settle the arithmetic on their own side. */
    fun setInk(tier: InkTier, amount: Long) {
        stores = stores.withInk(tier, amount, inkCapacity)
        setChanged()
    }

    /** @return what would not fit. */
    fun addPaper(tier: InkTier, sheets: Int): Int {
        val (updated, rejected) = stores.addingPaper(tier, sheets)
        stores = updated
        setChanged()
        return rejected
    }

    /** @return what would not fit. */
    fun addBinding(count: Int): Int {
        val (updated, rejected) = stores.addingBinding(count)
        stores = updated
        setChanged()
        return rejected
    }

    /** @return whether a binding was available to spend. */
    fun spendBinding(): Boolean {
        val remaining = stores.spendingBinding() ?: return false
        stores = remaining
        setChanged()
        return true
    }

    /** @return whether the whole cost could be paid; nothing is spent unless all of it can be. */
    fun spend(inkTier: InkTier, inkUnits: Long, paperTier: InkTier, sheets: Int): Boolean {
        val remaining = stores.spending(inkTier, inkUnits, paperTier, sheets) ?: return false
        stores = remaining
        setChanged()
        return true
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        archive = input.read(ARCHIVE_KEY, PageArchive.CODEC).orElse(PageArchive.EMPTY)
        stores = input.read(STORES_KEY, DeskStores.CODEC).orElse(DeskStores.EMPTY)
        compositions.clear()
        compositions.putAll(input.read(COMPOSING_KEY, COMPOSITIONS_CODEC).orElse(emptyMap()))
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.store(ARCHIVE_KEY, PageArchive.CODEC, archive)
        output.store(STORES_KEY, DeskStores.CODEC, stores)
        if (compositions.isNotEmpty()) output.store(COMPOSING_KEY, COMPOSITIONS_CODEC, compositions.toMap())
    }

    private companion object {
        const val ARCHIVE_KEY = "archive"
        const val STORES_KEY = "stores"
        const val COMPOSING_KEY = "composing"

        /** Who laid which row of pages. Order is word order, so the value is a list and never a set. */
        val COMPOSITIONS_CODEC: Codec<Map<UUID, List<Identifier>>> =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Identifier.CODEC.listOf())
    }
}
