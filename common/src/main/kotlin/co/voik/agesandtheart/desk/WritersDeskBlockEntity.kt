package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.platform.Services
import com.mojang.serialization.Codec
import net.minecraft.core.UUIDUtil
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The desk's stores, each writer's template, and its reading of the room.
 *
 * Only the centre block has one of these (see [WritersDeskBlock.newBlockEntity]), so a wing reaches it
 * through [WritersDeskBlock.entityAt] rather than holding anything itself.
 */
class WritersDeskBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.WRITERS_DESK_ENTITY, pos, state) {

    var stores: DeskStores = DeskStores.EMPTY
        private set

    /**
     * What each writer has typed at this desk — **kept between visits**, and saved on every change.
     *
     * **Per writer, on the desk.** Two people at one desk share its ink, which is the point of a desk, but
     * a half-written sentence is an argument half-made and stomping on somebody else's would be worse than
     * either sharing or forbidding. It is text rather than pages: nothing is handled until the bind, so a
     * broken desk owes nobody anything for it.
     */
    private val templates = mutableMapOf<UUID, String>()

    fun templateFor(writer: UUID): String = templates[writer].orEmpty()

    fun setTemplate(writer: UUID, text: String) {
        if (text.isBlank()) templates.remove(writer) else templates[writer] = text
        setChanged()
    }

    /**
     * What the room grants, read fresh every time it is asked — see [WritersDesk.survey].
     *
     * This was cached, and the cache was the whole of the bug: `neighborChanged` fires only for blocks
     * *touching* the desk, which is almost nothing inside a radius of five, so an enchanting table set
     * down two blocks away never reached it and the desk reported itself bare for good.
     */
    fun capabilities(desk: WritersDesk): DeskState =
        desk.survey(level ?: return DeskState.bare(null), blockPos)

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

    /** @return whether the whole cost could be paid; nothing is spent unless all of it can be. */
    fun pay(cost: BookCost): Boolean {
        val remaining = stores.paying(cost) ?: return false
        stores = remaining
        setChanged()
        return true
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        stores = input.read(STORES_KEY, DeskStores.CODEC).orElse(DeskStores.EMPTY)
        templates.clear()
        templates.putAll(input.read(TEMPLATES_KEY, TEMPLATES_CODEC).orElse(emptyMap()))
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.store(STORES_KEY, DeskStores.CODEC, stores)
        if (templates.isNotEmpty()) output.store(TEMPLATES_KEY, TEMPLATES_CODEC, templates.toMap())
    }

    private companion object {
        const val STORES_KEY = "stores"
        const val TEMPLATES_KEY = "templates"

        val TEMPLATES_CODEC: Codec<Map<UUID, String>> = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING)
    }
}
