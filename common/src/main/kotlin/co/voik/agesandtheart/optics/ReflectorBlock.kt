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
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult

/**
 * Two open faces at right angles, joined by a mirror: a beam entering by one leaves by the other, and a beam meeting
 * any other face stops. Using a face opens it, the mirror turning to join it with the face opened before.
 */
class ReflectorBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(MIRROR, Mirror.of(Direction.NORTH, Direction.EAST)))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(MIRROR)
    }

    /** Open to the placer, and to their right, or toward them where they look up or down at it. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val towardThePlacer = context.nearestLookingDirection.opposite
        val other = if (towardThePlacer.axis.isHorizontal) towardThePlacer.clockWise else context.horizontalDirection.opposite
        return defaultBlockState().setValue(MIRROR, Mirror.of(towardThePlacer, other))
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val opened = state.getValue(MIRROR).opening(hitResult.direction) ?: return InteractionResult.PASS
        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(MIRROR, opened), UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.SPYGLASS_USE, SoundSource.BLOCKS, 1f, 1f)
        }
        return InteractionResult.SUCCESS
    }

    /** The faces a mirror joins: [first] the one opened before [second], which matters only to the next turn. */
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

        /** The mirror after [face] is opened, keeping the last face opened where it can; none where nothing changes. */
        fun opening(face: Direction): Mirror? {
            if (face == first || face == second) return null
            val kept = if (face.axis != second.axis) second else first
            return of(kept, face)
        }

        companion object {
            fun of(first: Direction, second: Direction): Mirror =
                entries.first { it.first == first && it.second == second }
        }
    }

    companion object {
        val MIRROR: EnumProperty<Mirror> = EnumProperty.create("mirror", Mirror::class.java)

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
