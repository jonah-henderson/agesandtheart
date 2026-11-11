package co.voik.agesandtheart.age.phenomena

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

/**
 * The root of an overgrowth: it eats what it touches, raises plants and thorns, and grows what stands on
 * it very fast. A fire near it stops it spreading and raising, and never removes it (design §5.2.2). It holds the
 * water it grows into, as a waterlogged block does.
 */
class OvergrowthBlock(properties: BlockBehaviour.Properties) : Block(properties), SimpleWaterloggedBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(WATERLOGGED)
    }

    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

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
        if (state.getValue(WATERLOGGED)) ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        if (directionToNeighbour == Direction.UP) {
            Overgrowth.growthDelay(level, neighbourPos, neighbourState)?.let { ticks.scheduleTick(pos, this, it) }
        }
        return state
    }

    override fun tick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        Overgrowth.growAbove(level, at, random)?.let { level.scheduleTick(at, this, it) }
    }

    override fun randomTick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        val above = at.above()
        val delay = Overgrowth.growthDelay(level, above, level.getBlockState(above))
        if (delay != null) level.scheduleTick(at, this, delay) else Overgrowth.quickenGrowthAbove(level, at, random)
        if (Overgrowth.isHotWithin(level, at, Overgrowth.HOLD_BACK_RADIUS)) return
        Overgrowth.spreadFrom(level, at, random)
        Overgrowth.maybeRaiseFrom(level, at, random)
    }
}
