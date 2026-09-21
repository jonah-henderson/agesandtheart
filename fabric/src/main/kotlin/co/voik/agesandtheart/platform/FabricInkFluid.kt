package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeFluids
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.server.level.ServerLevel

/**
 * One ink, as a fluid Fabric can register.
 *
 * Written by hand because Fabric ships no equivalent of NeoForge's `BaseFlowingFluid` builder. The
 * behaviour is water's, slowed: ink is thick, so it spreads short and moves lazily. It is deliberately
 * **not** a source-converter — two ink streams meeting must never make more ink, which would undo the
 * whole economy (design §7.1.1).
 */
sealed class FabricInkFluid(val tier: InkTier) : FlowingFluid() {

    private val flow = AgeFluids.INKS.getValue(tier).flow

    override fun getBucket(): Item = FabricInkFluids.bucket(tier)

    override fun getFlowing(): Fluid = FabricInkFluids.flowing(tier)

    override fun getSource(): Fluid = FabricInkFluids.still(tier)

    /** Never. An ink that breeds is an ink economy that does not exist. */
    override fun canConvertToSource(level: ServerLevel): Boolean = false

    override fun beforeDestroyingBlock(level: LevelAccessor, pos: BlockPos, state: BlockState) {
        // Nothing worth dropping, and no block entity to salvage.
    }

    override fun getSlopeFindDistance(level: LevelReader): Int = flow.slopeFindDistance

    override fun getDropOff(level: LevelReader): Int = flow.dropOff

    override fun getTickDelay(level: LevelReader): Int = flow.tickDelay

    override fun getExplosionResistance(): Float = flow.explosionResistance

    override fun canBeReplacedWith(
        state: FluidState,
        level: BlockGetter,
        pos: BlockPos,
        other: Fluid,
        direction: Direction,
    ): Boolean = direction == Direction.DOWN && !other.defaultFluidState().`is`(FabricInkFluids.tag(tier))

    override fun createLegacyBlock(state: FluidState): BlockState =
        FabricInkFluids.block(tier).defaultBlockState().setValue(BlockStateProperties.LEVEL, getLegacyLevel(state))

    override fun isSame(fluid: Fluid): Boolean = fluid === getSource() || fluid === getFlowing()

    class Source(tier: InkTier) : FabricInkFluid(tier) {
        override fun getAmount(state: FluidState): Int = FULL
        override fun isSource(state: FluidState): Boolean = true
    }

    class Flowing(tier: InkTier) : FabricInkFluid(tier) {
        override fun createFluidStateDefinition(builder: StateDefinition.Builder<Fluid, FluidState>) {
            super.createFluidStateDefinition(builder)
            builder.add(LEVEL)
        }

        override fun getAmount(state: FluidState): Int = state.getValue(LEVEL)
        override fun isSource(state: FluidState): Boolean = false
    }

    private companion object {
        const val FULL = 8
    }
}
