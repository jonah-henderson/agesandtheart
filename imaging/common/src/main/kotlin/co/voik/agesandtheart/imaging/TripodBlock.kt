package co.voik.agesandtheart.imaging

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.WorldlyContainerHolder
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A tripod, nearly two blocks tall and standing in two as a door does: the lower half holds it up and draws the whole of
 * it, turned so its pair of legs stands toward whoever placed it, and the upper half is its head. It does nothing by itself. An image recorder used on either half is mounted on its
 * head, and the lower half becomes that recorder, [ImageRecorderBlock.MOUNTED], the head staying above it; the head
 * passes a use on to whatever stands beneath it, so the recorder on it is used as though it were reached, and a hopper
 * above it or beside it fills the recorder's plates as it would one standing on the ground.
 */
class TripodBlock(properties: Properties) : Block(properties), WorldlyContainerHolder {

    init {
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(FACING, Direction.SOUTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HALF, FACING)
    }

    /** Where there is room above for the head, facing as its placer does, so its pair of legs stands toward them. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val pos = context.clickedPos
        val level = context.level
        val hasRoomAbove = pos.y < level.maxY && level.getBlockState(pos.above()).canBeReplaced(context)
        return if (hasRoomAbove) defaultBlockState().setValue(FACING, context.horizontalDirection) else null
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), UPDATE_ALL)
    }

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            canSupportCenter(level, pos.below(), Direction.UP)
        } else {
            standsOnALowerHalf(level, pos)
        }

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
        val isTheLowerHalf = state.getValue(HALF) == DoubleBlockHalf.LOWER
        val lostItsHead = isTheLowerHalf && directionToNeighbour == Direction.UP && !isAHead(neighbourState)
        val lostWhatHoldsItUp = directionToNeighbour == Direction.DOWN && !canSurvive(state, level, pos)
        if (lostItsHead || lostWhatHoldsItUp) return Blocks.AIR.defaultBlockState()
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
    }

    /** The head broken breaks the lower half properly, so the tripod, and any recorder on it, drops from there. */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val isTheHead = state.getValue(HALF) == DoubleBlockHalf.UPPER
        if (isTheHead && !level.isClientSide && standsOnALowerHalf(level, pos)) {
            level.destroyBlock(pos.below(), !player.preventsBlockDrops(), player)
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!stack.`is`(ImagingContent.IMAGE_RECORDER)) return InteractionResult.TRY_WITH_EMPTY_HAND
        val lower = lowerHalfOf(state, pos)
        if (!level.getBlockState(lower).`is`(this)) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level is ServerLevel) mount(level, lower, stack, player)
        return InteractionResult.SUCCESS
    }

    /** The head passes a use on to the recorder beneath it, if there is one. */
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (state.getValue(HALF) != DoubleBlockHalf.UPPER) return InteractionResult.PASS
        val below = level.getBlockState(pos.below())
        if (!below.`is`(ImagingContent.IMAGE_RECORDER_BLOCK)) return InteractionResult.PASS
        return below.useWithoutItem(level, player, hitResult.withPosition(pos.below()))
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    /** The head's way in to the recorder beneath it, for a hopper; nothing, where there is no recorder there. */
    override fun getContainer(state: BlockState, level: LevelAccessor, pos: BlockPos): WorldlyContainer =
        recorderUnderTheHead(state, level, pos) ?: NOTHING_TO_HOLD

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) return LEGS
        val holdsARecorder = level.getBlockState(pos.below()).`is`(ImagingContent.IMAGE_RECORDER_BLOCK)
        return if (holdsARecorder) HEAD_WITH_A_RECORDER else HEAD
    }

    /** The recorder in [stack] on the head of the tripod whose lower half is at [lower], pointing the way [player] looks. */
    private fun mount(level: ServerLevel, lower: BlockPos, stack: ItemStack, player: Player) {
        // The recorder keeps the tripod's facing, which its tripod is drawn by.
        val tripodFacing = level.getBlockState(lower).getValue(FACING)
        val mounted = ImagingContent.IMAGE_RECORDER_BLOCK.defaultBlockState()
            .setValue(ImageRecorderBlock.MOUNTED, true)
            .setValue(ImageRecorderBlock.FACING, tripodFacing)
        level.setBlock(lower, mounted, UPDATE_ALL)
        val recorder = level.getBlockEntity(lower) as? ImageRecorderBlockEntity ?: return
        recorder.applyComponentsFromItemStack(stack)
        recorder.owner = player.uuid
        recorder.aim(player.yRot, LEVEL)
        stack.consume(1, player)
        level.playSound(null, lower.above(), SoundEvents.LANTERN_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f)
    }

    companion object {
        val HALF: EnumProperty<DoubleBlockHalf> = BlockStateProperties.DOUBLE_BLOCK_HALF

        /** The way its placer faced; its pair of legs stands on the side they stood. */
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING

        /** The legs, spread to the block's edges; the lower half's shape, a mounted recorder's included. */
        val LEGS: VoxelShape = box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0)

        /** The head, up to the top of its clamps at seven and a half pixels into the upper block. */
        private val HEAD: VoxelShape = box(3.0, 0.0, 3.0, 13.0, 8.0, 13.0)

        /** The head with a recorder on it, which stands up to the top of the upper block. */
        private val HEAD_WITH_A_RECORDER: VoxelShape = box(3.0, 0.0, 3.0, 13.0, 16.0, 13.0)

        private const val LEVEL = 0.0f

        private fun isAHead(state: BlockState): Boolean =
            state.`is`(ImagingContent.TRIPOD_BLOCK) && state.getValue(HALF) == DoubleBlockHalf.UPPER

        /** Whether the head at [pos] has its tripod beneath it, bare or with a recorder mounted. */
        private fun standsOnALowerHalf(level: BlockGetter, pos: BlockPos): Boolean {
            val below = level.getBlockState(pos.below())
            val isABareTripod = below.`is`(ImagingContent.TRIPOD_BLOCK) && below.getValue(HALF) == DoubleBlockHalf.LOWER
            val isAMountedRecorder = below.`is`(ImagingContent.IMAGE_RECORDER_BLOCK) && below.getValue(ImageRecorderBlock.MOUNTED)
            return isABareTripod || isAMountedRecorder
        }

        /** The recorder mounted beneath the head at [pos], or null where [state] is not a head or holds none. */
        fun recorderUnderTheHead(state: BlockState, level: BlockGetter, pos: BlockPos): ImageRecorderBlockEntity? {
            if (!state.`is`(ImagingContent.TRIPOD_BLOCK) || state.getValue(HALF) != DoubleBlockHalf.UPPER) return null
            return level.getBlockEntity(pos.below()) as? ImageRecorderBlockEntity
        }

        /** A container with no slots, which a hopper finds nothing to put into or take out of. */
        private val NOTHING_TO_HOLD = object : SimpleContainer(0), WorldlyContainer {
            override fun getSlotsForFace(side: Direction): IntArray = IntArray(0)
            override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, side: Direction?): Boolean = false
            override fun canTakeItemThroughFace(slot: Int, stack: ItemStack, side: Direction): Boolean = false
        }

        private fun lowerHalfOf(state: BlockState, pos: BlockPos): BlockPos =
            if (state.getValue(HALF) == DoubleBlockHalf.UPPER) pos.below() else pos
    }
}
