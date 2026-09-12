package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BubbleColumnBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState

/**
 * A whirlpool standing in the abyss — vanilla's bubble column, made of deep water instead of water.
 *
 * **Vanilla's column is water and only water, and that hole was exactly the wrong shape.**
 * `BubbleColumnBlock.getFluidState` returns `Fluids.WATER.getSource(false)` unconditionally, so a column
 * rising through an abyss replaced every block of the bore with something that was not deep water. Two
 * things fell out of that, and both undid the vent: `DeepWater.press` is called from `DeepWaterBlock`'s
 * own `entityInside` and so never fired, and `DeepWaterFog.deepAt` reads the fluid at the eye and so
 * opened the fog back out to whatever the Age's ordinary sea is — up to `AgeAir.CLEAREST`, 256 blocks.
 * The whirlpool was a pressure-free, clear-water lift straight down into the one place that should cost
 * you something to be in.
 *
 * **A block of our own rather than a Mixin, and 26.1 is what makes that possible.**
 * `BubbleColumnBlock.updateColumn` takes the column's *block* as its first argument — Mojang's own
 * parameter, not a loader's patch, and identical on both — so a liquid may nominate which column rises out
 * of it. `DeepWaterBlock.tick` nominates this one. Everything else comes for free: `canOccupy` accepts a
 * column that is already ours, `getColumnState` returns the state below verbatim where it is ours, so
 * deepness carries up the whole shaft without a property to set or a state to fix up.
 *
 * **One edge stays vanilla's and is left to heal.** Where a column *ends*, `getColumnState` hands back
 * `Blocks.WATER.defaultBlockState()` — a private static with no seam in it — so cutting the magma out
 * leaves ordinary water in the bore for a moment. `DeepWaterBlock.settle` takes it back on the next tick,
 * which is the same repair that answers a bucket poured into the deep.
 */
class DeepBubbleColumnBlock(properties: BlockBehaviour.Properties) : BubbleColumnBlock(properties) {

    override fun codec(): MapCodec<BubbleColumnBlock> = CODEC

    /**
     * The abyss, where vanilla's column reports water.
     *
     * **Cached once per state in `BlockStateBase.initCache`**, not read per lookup — which is why
     * [DeepWaterLogging.settleTheCache] has to reach this block as well as the waterlogged ones. If deep
     * water's fluid was not registered when our states were built, every one of them cached vanilla water
     * and nothing about it would ever look like a bug.
     */
    override fun getFluidState(state: BlockState): FluidState = DeepWater.deepWater().fluidState

    /**
     * The deep pressing on whatever the column is carrying down.
     *
     * `super` first so the drag itself is vanilla's — what is ours is that being dragged costs you the
     * same as swimming, which is the whole point of the vent being entered this way.
     */
    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effectApplier: InsideBlockEffectApplier,
        isPrecise: Boolean,
    ) {
        super.entityInside(state, level, pos, entity, effectApplier, isPrecise)
        DeepWater.press(level, entity)
    }

    companion object {
        val CODEC: MapCodec<BubbleColumnBlock> = simpleCodec(::DeepBubbleColumnBlock)
    }
}
