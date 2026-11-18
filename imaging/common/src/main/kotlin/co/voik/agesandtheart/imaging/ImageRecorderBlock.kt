package co.voik.agesandtheart.imaging

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * An image recorder set down on something it can stand on: an automatic camera. It faces one of sixteen ways, a facing
 * and a [TURN] of 22.5 degrees clockwise within it; looking through it, the strafe keys turn it ([ImagingTurns]). Used,
 * it opens its five slots, plates in and pictures out ([ImageRecorderBlockEntity]); used sneaking, it is looked
 * through; a pulse of redstone takes a picture. It is gone at a touch, dropping what it holds.
 */
class ImageRecorderBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TURN, 0).setValue(POWERED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, TURN, POWERED)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ImageRecorderBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        val recorder = level.getBlockEntity(pos) as? ImageRecorderBlockEntity ?: return
        if (placer is Player) recorder.owner = placer.uuid
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (player.isSecondaryUseActive) {
            if (level.isClientSide) lookThroughOnTheClient?.invoke(pos)
            return InteractionResult.SUCCESS
        }
        val recorder = level.getBlockEntity(pos) as? ImageRecorderBlockEntity ?: return InteractionResult.PASS
        if (!level.isClientSide) player.openMenu(recorder)
        return InteractionResult.SUCCESS
    }

    /** A pulse takes a picture, on its rising edge only, as a dispenser fires. */
    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighbour: Block, orientation: Orientation?, movedByPiston: Boolean) {
        val isPowered = level.hasNeighborSignal(pos)
        val wasPowered = state.getValue(POWERED)
        if (isPowered == wasPowered) return
        level.setBlock(pos, state.setValue(POWERED, isPowered), UPDATE_CLIENTS)
        if (isPowered && level is ServerLevel) (level.getBlockEntity(pos) as? ImageRecorderBlockEntity)?.shoot(level)
    }

    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos, direction: Direction): Int =
        (level.getBlockEntity(pos) as? ImageRecorderBlockEntity)?.picturesHeldSignal() ?: 0

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

        /** How many sixteenths of a turn clockwise past [FACING] it points. */
        val TURN: IntegerProperty = IntegerProperty.create("turn", 0, TURNS_A_QUARTER - 1)

        val POWERED: BooleanProperty = BlockStateProperties.POWERED

        /** Set by the client's entrypoint: what looking through a set-down recorder does there. */
        var lookThroughOnTheClient: ((BlockPos) -> Unit)? = null

        const val TURNS_A_QUARTER = 4
        const val WAYS = 16
        private const val DEGREES_A_TURN = 360.0f / WAYS

        /** Its height off the ground to the middle of the lens, in blocks: two pixels. */
        private const val LENS_HEIGHT = 2.0 / 16.0

        /** How far forward of the block's middle a picture is taken from: just past the lens's glass, six pixels out. */
        private const val LENS_FORWARD = 6.5 / 16.0

        /** Round enough to take in the recorder at any of its angles. */
        private val SHAPE: VoxelShape = box(3.0, 0.0, 3.0, 13.0, 10.0, 13.0)

        /** Which of its sixteen ways it points, nought facing south and on clockwise, as a yaw counts them. */
        fun wayOf(state: BlockState): Int =
            Math.floorMod(state.getValue(FACING).get2DDataValue() * TURNS_A_QUARTER + state.getValue(TURN), WAYS)

        /** [state] turned [by] sixteenths clockwise, on into the next facing past its fourth turn. */
        fun turned(state: BlockState, by: Int): BlockState {
            val way = Math.floorMod(wayOf(state) + by, WAYS)
            return state.setValue(FACING, Direction.from2DDataValue(way / TURNS_A_QUARTER)).setValue(TURN, way % TURNS_A_QUARTER)
        }

        /** The yaw it points along, as an entity's is counted. */
        fun yawOf(state: BlockState): Float = wayOf(state) * DEGREES_A_TURN

        /** Where its lens is, and so where a picture is taken from. */
        fun lensOf(state: BlockState, pos: BlockPos): Vec3 =
            Vec3.atBottomCenterOf(pos).add(0.0, LENS_HEIGHT, 0.0).add(Vec3.directionFromRotation(0.0f, yawOf(state)).scale(LENS_FORWARD))
    }
}
