package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BubbleColumnBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.redstone.Orientation

/**
 * The block half of deep water: an ordinary liquid block that keeps checking whether it is allowed to be
 * one.
 *
 * **Everything about being a fluid is inherited and none of it is touched.** What is added is a single
 * scheduled tick that asks [DeepWater.standsAt] and settles back to water if the answer is no — which is
 * §5.4's second shape, "a rule on our own block", and is the reason this phenomenon needs no register
 * anywhere.
 *
 * **In common, where the fluid itself cannot be.** `LiquidBlock` is vanilla and takes its `FlowingFluid` as
 * an argument, so the rule lives here once and each loader hands in its own fluid — which is the whole of
 * why the deep water split between this file and `AgeFluids` the way the inks did not have to.
 */
class DeepWaterBlock(fluid: FlowingFluid, properties: Properties) : LiquidBlock(fluid, properties) {

    /**
     * Placed by hand, by a bucket or by a command — decide immediately whether it may stay.
     *
     * **Never reached by generation**, which writes block states into a chunk without firing placement at
     * all. That is wanted: a generator filling an abyssal sea should pay for the sea, not for a settling
     * pass over every block of it, and the blocks it writes are already right.
     */
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        level.scheduleTick(pos, this, SETTLES_IN)
    }

    /**
     * A neighbour changed shape, from any side.
     *
     * **This scheduled on `UP` alone**, which was exact while the rule read the column overhead and is
     * wrong now it reads a plane: what a change to the side does is expose ordinary water that ought to be
     * abyss, and only a block that hears about it can take that water in.
     */
    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        ticks.scheduleTick(pos, this, SETTLES_IN)
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
    }

    /**
     * The settle, and the whirlpool.
     *
     * **`super` is deliberately NOT called, and that is the whole of how the abyss gets its own column.**
     * `LiquidBlock.tick` does exactly one thing — `updateColumn(Blocks.BUBBLE_COLUMN, …)` — so calling it
     * would raise a column made of ordinary water through the deep, taking the pressure and the fog out of
     * whatever is standing in it. What replaces it is [DeepBubbleColumnBlock.raise], which raises ours and
     * stops it at the abyss line. `DeepWater`'s own entry in `#minecraft:bubble_column_can_occupy` is what
     * lets a column stand in the abyss at all.
     */
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        DeepBubbleColumnBlock.raise(level, pos, state, level.getBlockState(pos.below()))
        settle(state, level, pos)
    }

    /**
     * **Random ticks are what close the gap a scheduled tick cannot reach.** A block change fires
     * `updateShape` on its six neighbours and no further, so bucketing a block out of the sea eighty above
     * an abyss never reaches it — the water between them is vanilla's and carries no rule of ours. Only a
     * tick the block gives itself notices.
     */
    override fun isRandomlyTicking(state: BlockState): Boolean = true

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        settle(state, level, pos)
    }

    /**
     * Bring one block into line with the rule, in whichever direction it is out.
     *
     * **Most of the cost went with the plane.** Whether this block may stand is one comparison against
     * `DeepWater.lineIn` plus a scan capped at `DeepWater.STILL_A_SEA` — a quarter of the old walk, so the
     * induction the previous version needed to be affordable at all is simply gone.
     *
     * Two ways to be wrong and both are answered: where `DeepWater.standsAt` says no it reverts to ordinary
     * water, and any ordinary water it touches that *would* stand is taken in. Each conversion schedules
     * what it touched, so a correction spreads a block a tick rather than waiting on a random tick apiece.
     */
    private fun settle(state: BlockState, level: ServerLevel, pos: BlockPos) {
        if (!DeepWater.standsAt(level, pos)) {
            // **Water keeps its level rather than becoming a source**: both blocks carry the same `LEVEL`,
            // and a flowing tongue that promoted itself on the way out would make water from nothing.
            level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState().setValue(LEVEL, state.getValue(LEVEL)))
            for (side in Direction.entries) release(level, pos.relative(side))
            return
        }
        // Take in the ordinary water it touches, which is what closes a gap the moment one opens — a
        // bucket poured in, or a block mined out and filled by the sea.
        for (side in Direction.entries) deepen(level, pos.relative(side))
    }

    /**
     * Take [at] into the abyss, whether it is ordinary water or a block holding some.
     *
     * **A block that holds the abyss does not tick**, being a stair rather than a fluid, so the spread
     * reaches only what the abyss itself touches. That is enough for what changes at runtime — a stair
     * placed in the deep, a wreck opened into — and generation's own sweep has already done the interiors
     * (`DeepWater.settleTheAbyss`).
     */
    private fun deepen(level: ServerLevel, at: BlockPos) {
        val state = level.getBlockState(at)
        if (DeepWaterLogging.couldHold(state)) {
            if (DeepWater.standsAt(level, at)) level.setBlockAndUpdate(at, DeepWaterLogging.holding(state))
            return
        }
        if (!state.`is`(Blocks.WATER) || !DeepWater.standsAt(level, at)) return
        level.setBlockAndUpdate(at, defaultBlockState().setValue(LEVEL, state.getValue(LEVEL)))
        level.scheduleTick(at, this, SETTLES_IN)
    }

    /**
     * Give [at] its ordinary water back, where it was holding the abyss and the column above has opened.
     *
     * The counterpart of [deepen] and reached the same way: a reverting block releases what it touches, so
     * a cut column unzips through the wreck standing in it rather than leaving stairs full of a deep that
     * is no longer there.
     */
    private fun release(level: ServerLevel, at: BlockPos) {
        val state = level.getBlockState(at)
        if (!DeepWaterLogging.holds(state)) return
        level.setBlockAndUpdate(at, DeepWaterLogging.released(state))
    }

    /**
     * The deep pressing on whatever is standing in it.
     *
     * **The water acts, rather than being swept for.** See `DeepWater.press` for why this beat a per-second
     * search outward from the players.
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

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        orientation: Orientation?,
        movedByPiston: Boolean,
    ) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston)
        // The belt to `updateShape`'s braces: an indirect update (a piston, a command, a fluid two steps
        // away resolving) reaches here and not there, and the tick it schedules is cheap and idempotent.
        level.scheduleTick(pos, this, SETTLES_IN)
    }

    private companion object {
        /** One tick, which is "instantly" as far as anybody watching is concerned. */
        const val SETTLES_IN = 1
    }
}
