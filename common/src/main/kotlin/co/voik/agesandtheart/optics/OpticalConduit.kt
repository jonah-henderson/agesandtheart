package co.voik.agesandtheart.optics

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.PipeBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** The stripe dyed onto an optical conduit, or none. */
enum class ConduitStripe(val dye: DyeColor?) : StringRepresentable {
    NONE(null),
    WHITE(DyeColor.WHITE),
    ORANGE(DyeColor.ORANGE),
    MAGENTA(DyeColor.MAGENTA),
    LIGHT_BLUE(DyeColor.LIGHT_BLUE),
    YELLOW(DyeColor.YELLOW),
    LIME(DyeColor.LIME),
    PINK(DyeColor.PINK),
    GRAY(DyeColor.GRAY),
    LIGHT_GRAY(DyeColor.LIGHT_GRAY),
    CYAN(DyeColor.CYAN),
    PURPLE(DyeColor.PURPLE),
    BLUE(DyeColor.BLUE),
    BROWN(DyeColor.BROWN),
    GREEN(DyeColor.GREEN),
    RED(DyeColor.RED),
    BLACK(DyeColor.BLACK),
    ;

    override fun getSerializedName(): String = dye?.serializedName ?: "none"

    /** Unstriped conduit joins every stripe; striped joins only its own. */
    fun joins(other: ConduitStripe): Boolean = this == NONE || other == NONE || this == other

    companion object {
        fun of(dye: DyeColor): ConduitStripe = entries.first { it.dye == dye }
    }
}

/**
 * An optical conduit: fibre optics, carrying light at once to everything joined to it ([LightNetworks]). It joins
 * conduits whose stripe it shares, or any where either is unstriped, and the emitters, receivers and collectors
 * facing it ([joinsEndpoint]). Dye used on it restripes it.
 */
class OpticalConduitBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(
            PipeBlock.PROPERTY_BY_DIRECTION.values.fold(stateDefinition.any()) { state, joined -> state.setValue(joined, false) }
                .setValue(STRIPE, ConduitStripe.NONE),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(STRIPE, *PipeBlock.PROPERTY_BY_DIRECTION.values.toTypedArray())
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        joinedAround(defaultBlockState(), context.level, context.clickedPos)

    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState = state.setValue(joinedProperty(directionToNeighbour), joins(state, directionToNeighbour, neighbourState))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        val joinedMask = Direction.entries.sumOf { direction -> if (state.getValue(joinedProperty(direction))) 1 shl direction.ordinal else 0 }
        return SHAPES[joinedMask]
    }

    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val dye = itemStack.get(DataComponents.DYE) ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val stripe = ConduitStripe.of(dye)
        if (state.getValue(STRIPE) == stripe) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level.isClientSide) return InteractionResult.SUCCESS
        level.setBlock(pos, joinedAround(state.setValue(STRIPE, stripe), level, pos), UPDATE_ALL)
        itemStack.consume(1, player)
        level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1f, 1f)
        return InteractionResult.SUCCESS
    }

    companion object {
        val STRIPE: EnumProperty<ConduitStripe> = EnumProperty.create("stripe", ConduitStripe::class.java)

        private const val NEAR = 5.0
        private const val FAR = 11.0
        private const val EDGE = 16.0
        private val CORE: VoxelShape = box(NEAR, NEAR, NEAR, FAR, FAR, FAR)
        private val ARMS: Map<Direction, VoxelShape> = mapOf(
            Direction.DOWN to box(NEAR, 0.0, NEAR, FAR, NEAR, FAR),
            Direction.UP to box(NEAR, FAR, NEAR, FAR, EDGE, FAR),
            Direction.NORTH to box(NEAR, NEAR, 0.0, FAR, FAR, NEAR),
            Direction.SOUTH to box(NEAR, NEAR, FAR, FAR, FAR, EDGE),
            Direction.WEST to box(0.0, NEAR, NEAR, NEAR, FAR, FAR),
            Direction.EAST to box(FAR, NEAR, NEAR, EDGE, FAR, FAR),
        )

        /** Every shape a conduit takes, by which of its sides are joined, each side a bit at its direction's ordinal. */
        private val SHAPES: List<VoxelShape> = List(1 shl Direction.entries.size) { joinedMask ->
            Direction.entries.filter { joinedMask and (1 shl it.ordinal) != 0 }
                .fold(CORE) { shape, direction -> Shapes.or(shape, ARMS.getValue(direction)) }
        }

        fun joinedProperty(direction: Direction): BooleanProperty = PipeBlock.PROPERTY_BY_DIRECTION.getValue(direction)

        /** Whether a conduit in [state] joins [neighbour], the block toward [direction]. */
        fun joins(state: BlockState, direction: Direction, neighbour: BlockState): Boolean {
            val isAConduitItJoins = neighbour.block is OpticalConduitBlock && state.getValue(STRIPE).joins(neighbour.getValue(STRIPE))
            return isAConduitItJoins || joinsEndpoint(direction, neighbour)
        }

        /**
         * Whether a conduit joins [endpoint], the block toward [towardEndpoint]: an emitter at its back, a collector at
         * the face its light leaves by, and a receiver on any side.
         */
        fun joinsEndpoint(towardEndpoint: Direction, endpoint: BlockState): Boolean = when (endpoint.block) {
            is AstriteEmitterBlock -> endpoint.getValue(AstriteEmitterBlock.FACING) == towardEndpoint
            is SunlightCollectorBlock -> endpoint.getValue(SunlightCollectorBlock.FACING) == towardEndpoint.opposite
            is BeamReceiverBlock -> true
            else -> false
        }

        private fun joinedAround(state: BlockState, level: BlockGetter, pos: BlockPos): BlockState =
            Direction.entries.fold(state) { joined, direction ->
                joined.setValue(joinedProperty(direction), joins(state, direction, level.getBlockState(pos.relative(direction))))
            }
    }
}
