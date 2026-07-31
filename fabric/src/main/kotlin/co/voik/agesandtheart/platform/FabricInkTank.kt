package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.desk.WritersDeskBlockEntity
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage

/**
 * One of the desk's tanks, as something a pipe understands.
 *
 * Automation is meant to work (design §7.4): the brake on treasure Ages is how hard masterwork ink is to
 * *produce*, never a refusal to accept it from a pump. So it fills and drains alike.
 *
 * `SingleVariantStorage` carries the transaction handling — an insert can be rolled back before it
 * commits — and [onFinalCommit] is the only point the block entity hears about. Built fresh per lookup
 * and seeded from the desk, so it can never hold a stale level.
 */
class FabricInkTank(
    private val desk: WritersDeskBlockEntity,
    private val tier: InkTier,
) : SingleVariantStorage<FluidVariant>() {

    init {
        variant = FluidVariant.of(FabricInkFluids.still(tier))
        amount = desk.stores.ink(tier)
    }

    override fun getBlankVariant(): FluidVariant = FluidVariant.blank()

    override fun getCapacity(variant: FluidVariant): Long = desk.inkCapacity

    /** A tank only ever holds its own ink. */
    override fun canInsert(variant: FluidVariant): Boolean =
        variant.fluid === FabricInkFluids.still(tier)

    override fun canExtract(variant: FluidVariant): Boolean =
        variant.fluid === FabricInkFluids.still(tier)

    override fun onFinalCommit() {
        desk.setInk(tier, amount)
    }
}
