package co.voik.agesandtheart.optics

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult

/**
 * Two open faces at right angles, joined by a mirror: a beam entering by one leaves by the other, and a beam meeting
 * any other face stops. Its [plane] says which faces it is placed joining. Using it turns the mirror a quarter turn
 * about its pivot, and back while sneaking.
 */
class ReflectorBlock(properties: Properties, val plane: OpticPlane) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(MIRROR, OpticPlane.resting(plane)).setValue(PIECE, OpticPiece.STAND))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(MIRROR, PIECE)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ReflectorBlockEntity(pos, state)

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState = defaultBlockState().setValue(MIRROR, plane.placed(context))

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val turned = state.getValue(MIRROR).turned(back = player.isSecondaryUseActive)
        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(MIRROR, turned), UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.SPYGLASS_USE, SoundSource.BLOCKS, 1f, 1f)
        }
        return InteractionResult.SUCCESS
    }

    /** The faces a mirror joins; which is [first] matters only to a splitter or merger, whose straight face it is. */
    enum class Mirror(val first: Direction, val second: Direction) : StringRepresentable {
        DOWN_NORTH(Direction.DOWN, Direction.NORTH),
        DOWN_SOUTH(Direction.DOWN, Direction.SOUTH),
        DOWN_WEST(Direction.DOWN, Direction.WEST),
        DOWN_EAST(Direction.DOWN, Direction.EAST),
        UP_NORTH(Direction.UP, Direction.NORTH),
        UP_SOUTH(Direction.UP, Direction.SOUTH),
        UP_WEST(Direction.UP, Direction.WEST),
        UP_EAST(Direction.UP, Direction.EAST),
        NORTH_DOWN(Direction.NORTH, Direction.DOWN),
        NORTH_UP(Direction.NORTH, Direction.UP),
        NORTH_WEST(Direction.NORTH, Direction.WEST),
        NORTH_EAST(Direction.NORTH, Direction.EAST),
        SOUTH_DOWN(Direction.SOUTH, Direction.DOWN),
        SOUTH_UP(Direction.SOUTH, Direction.UP),
        SOUTH_WEST(Direction.SOUTH, Direction.WEST),
        SOUTH_EAST(Direction.SOUTH, Direction.EAST),
        WEST_DOWN(Direction.WEST, Direction.DOWN),
        WEST_UP(Direction.WEST, Direction.UP),
        WEST_NORTH(Direction.WEST, Direction.NORTH),
        WEST_SOUTH(Direction.WEST, Direction.SOUTH),
        EAST_DOWN(Direction.EAST, Direction.DOWN),
        EAST_UP(Direction.EAST, Direction.UP),
        EAST_NORTH(Direction.EAST, Direction.NORTH),
        EAST_SOUTH(Direction.EAST, Direction.SOUTH),
        ;

        override fun getSerializedName(): String = "${first.serializedName}_${second.serializedName}"

        /** The axis it turns about, square to both its faces. */
        val pivot: Direction.Axis get() = Direction.Axis.entries.first { it != first.axis && it != second.axis }

        /** A quarter turn about its [pivot], or [back] the other way, which never takes it out of its plane. */
        fun turned(back: Boolean): Mirror {
            fun turn(face: Direction) = if (back) face.getCounterClockWise(pivot) else face.getClockWise(pivot)
            return of(turn(first), turn(second))
        }

        /** The same faces, the other first. */
        fun swapped(): Mirror = of(second, first)

        companion object {
            fun of(first: Direction, second: Direction): Mirror =
                entries.first { it.first == first && it.second == second }
        }
    }

    companion object {
        val MIRROR: EnumProperty<Mirror> = EnumProperty.create("mirror", Mirror::class.java)
        val PIECE: EnumProperty<OpticPiece> = EnumProperty.create("piece", OpticPiece::class.java)

        /** Where a beam travelling [direction] into a reflector in [state] leaves it, or none where it stops. */
        fun turn(state: BlockState, direction: Direction): Direction? {
            val mirror = state.getValue(MIRROR)
            val enteredBy = direction.opposite
            return when (enteredBy) {
                mirror.first -> mirror.second
                mirror.second -> mirror.first
                else -> null
            }
        }
    }
}

/** Only so the client can draw a reflector's mirror turning; it keeps nothing. */
class ReflectorBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(Optics.REFLECTOR_ENTITY, pos, state), HoldsAMirror {
    override val faces: ReflectorBlock.Mirror get() = blockState.getValue(ReflectorBlock.MIRROR)
    override val isHalfSilvered: Boolean get() = false
    override val turning = MirrorTurning()
}
