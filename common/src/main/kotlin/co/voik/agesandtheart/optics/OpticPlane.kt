package co.voik.agesandtheart.optics

import co.voik.agesandtheart.optics.ReflectorBlock.Mirror
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext

/**
 * Which plane a reflector, splitter or merger turns a beam in: the horizontal, both its faces level, or the vertical,
 * one level face and one up or down. Each comes in both forms, and one put alone in a crafting grid becomes the other.
 */
enum class OpticPlane {
    HORIZONTAL,
    VERTICAL,
    ;

    /**
     * The faces it is placed with, first and second: the one toward the placer and the one to their right for the
     * horizontal; for the vertical, the one toward the placer and up, or toward them and the side they face from where
     * they look up or down at it.
     */
    fun placed(context: BlockPlaceContext): Mirror {
        val towardThePlacer = context.nearestLookingDirection.opposite
        val levelTowardThePlacer = context.horizontalDirection.opposite
        return when (this) {
            HORIZONTAL -> Mirror.of(levelTowardThePlacer, levelTowardThePlacer.clockWise)
            VERTICAL -> if (towardThePlacer.axis.isVertical) Mirror.of(towardThePlacer, levelTowardThePlacer)
            else Mirror.of(towardThePlacer, Direction.UP)
        }
    }

    /**
     * The faces after [face] is used, [faces]' first and second kept in their places: on the horizontal form, as
     * [levelOpening] says; on the vertical, [face] takes the place of whichever of the two is as level as it is. None
     * where nothing changes or the face is not this form's to open.
     */
    fun opening(faces: Mirror, face: Direction, levelOpening: (Mirror, Direction) -> Mirror?): Mirror? = when {
        face == faces.first || face == faces.second -> null
        this == HORIZONTAL -> if (face.axis.isVertical) null else levelOpening(faces, face)
        face.axis.isVertical == faces.first.axis.isVertical -> Mirror.of(face, faces.second)
        else -> Mirror.of(faces.first, face)
    }

    companion object {
        /** The default faces of a block of [plane]. */
        fun resting(plane: OpticPlane): Mirror = when (plane) {
            HORIZONTAL -> Mirror.of(Direction.NORTH, Direction.EAST)
            VERTICAL -> Mirror.of(Direction.NORTH, Direction.UP)
        }
    }
}
