package co.voik.agesandtheart.imaging

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * An image recorder set down: pointing the way its placer looked, on something it can stand on, and gone at a touch.
 * Its model is turned 22.5 degrees off the way it faces, so it sits at an angle (`scripts/imaging/image_recorder_models.py`).
 */
class ImageRecorderBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection)

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        canSupportCenter(level, pos.below(), Direction.UP)

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
        val lostWhatItStoodOn = directionToNeighbour == Direction.DOWN && !canSurvive(state, level, pos)
        if (lostWhatItStoodOn) return Blocks.AIR.defaultBlockState()
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING

        /** Round enough to take in the recorder at any of its angles. */
        private val SHAPE: VoxelShape = box(3.0, 0.0, 3.0, 13.0, 10.0, 13.0)
    }
}
