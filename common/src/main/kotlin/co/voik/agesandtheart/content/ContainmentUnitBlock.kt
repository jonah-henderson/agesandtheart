package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** A plasma containment unit set down on something solid beneath it, never hung, and waterlogged where it stands in water. */
open class ContainmentUnitBlock(properties: Properties) : Block(properties), SimpleWaterloggedBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(WATERLOGGED)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val placed = defaultBlockState()
            .setValue(WATERLOGGED, context.level.getFluidState(context.clickedPos).`is`(Fluids.WATER))
        return if (placed.canSurvive(context.level, context.clickedPos)) placed else null
    }

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        canSupportCenter(level, pos.below(), Direction.UP)

    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        direction: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        if (state.getValue(WATERLOGGED)) ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        val lostWhatItStoodOn = direction == Direction.DOWN && !state.canSurvive(level, pos)
        if (lostWhatItStoodOn) return Blocks.AIR.defaultBlockState()
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbourState, random)
    }

    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    companion object {
        val WATERLOGGED: BooleanProperty = BlockStateProperties.WATERLOGGED

        /** The caps and glass make a plus, the top cap's boss rising from its middle. */
        private val SHAPE: VoxelShape = Shapes.or(
            box(6.0, 0.0, 4.0, 10.0, 13.0, 12.0),
            box(4.0, 0.0, 6.0, 12.0, 13.0, 10.0),
            box(5.0, 0.0, 5.0, 11.0, 14.0, 11.0),
            box(6.0, 0.0, 6.0, 10.0, 15.0, 10.0),
        )
    }
}
