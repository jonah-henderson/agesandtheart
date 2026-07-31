package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.desk.WritersDeskBlockEntity
import net.minecraft.core.NonNullList
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.transfer.fluid.FluidResource
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler

/**
 * The desk's three tanks, as something a pipe understands.
 *
 * Automation is meant to work (design §7.4): the brake on treasure Ages is how hard masterwork ink is to
 * *produce*, never a refusal to accept it from a pump. So all three are open to fill and drain alike.
 *
 * Built on `FluidStacksResourceHandler` rather than by hand, because 26.1's transfer API is transactional
 * — an insert can be rolled back — and reimplementing that correctly is exactly the kind of thing that
 * looks fine until a pipe half-commits. It keeps its own stacks; [onContentsChanged] is what makes the
 * block entity the thing that actually persists.
 *
 * One index per ink tier, so index order *is* [InkTier] order. Amounts pass through untouched: the desk
 * already stores in NeoForge's own millibuckets (see `InkFluids.unitsPerBucket`).
 */
class NeoForgeInkHandler(
    private val desk: WritersDeskBlockEntity,
) : FluidStacksResourceHandler(seed(desk), desk.inkCapacity.toInt()) {

    /** A tank only ever holds its own ink; nothing else may be pushed into it. */
    override fun isValid(index: Int, resource: FluidResource): Boolean {
        val tier = InkTier.entries.getOrNull(index) ?: return false
        return resource.isEmpty || resource.fluid === NeoForgeInkFluids.still(tier)
    }

    /** Called once a transaction commits, which is the only point the desk should hear about. */
    override fun onContentsChanged(index: Int, previousContents: FluidStack) {
        val tier = InkTier.entries.getOrNull(index) ?: return
        desk.setInk(tier, getAmountAsLong(index))
    }

    private companion object {
        /** The desk's current levels, one stack per tier, in tier order. */
        fun seed(desk: WritersDeskBlockEntity): NonNullList<FluidStack> {
            val stacks = NonNullList.withSize(InkTier.entries.size, FluidStack.EMPTY)
            InkTier.entries.forEachIndexed { index, tier ->
                val held = desk.stores.ink(tier)
                if (held > 0) stacks[index] = FluidStack(NeoForgeInkFluids.still(tier), held.toInt())
            }
            return stacks
        }
    }
}
