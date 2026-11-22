package co.voik.agesandtheart.optics

import co.voik.agesandtheart.optics.ReflectorBlock.Mirror
import net.minecraft.core.Direction
import net.minecraft.util.StringRepresentable
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/**
 * Which of a reflector, splitter or merger's models: the open frame the world draws, or the mirror plane and the lens on
 * the straight face, which its renderer turns.
 */
enum class OpticPiece(private val serializedName: String) : StringRepresentable {
    FRAME("frame"),
    MIRROR("mirror"),
    LENS("lens"),
    ;

    override fun getSerializedName(): String = serializedName
}

/** A block whose renderer shows a mirror plane between two of its faces, turning when they change. */
interface HoldsAMirror {
    /** The two faces the mirror stands between: for a splitter or merger, its straight face and its turned one. */
    val faces: Mirror

    /** Whether it is half-silvered, and so shows which face is its straight one. */
    val isHalfSilvered: Boolean

    /** The client's record of the mirror turning. Never saved. */
    val turning: MirrorTurning
}

/**
 * A mirror plane easing from where it stood to where its faces now put it, on the client.
 *
 * The plane is modelled standing between the north and east faces; [orientationOf] turns that onto any pair, the axis
 * the plane turns about always taken the positive way along it, so a quarter turn reads as one and never as a tumble.
 */
class MirrorTurning {
    private var shown: Mirror? = null
    private var turnedFrom: Mirror? = null
    private var turnStartedNanos = 0L

    /** How the modelled plane is turned to show [faces] now, part of the way through any turn under way. */
    fun orientationNow(faces: Mirror, nowNanos: Long): Quaternionf {
        val wasShowing = shown
        if (wasShowing == null) {
            shown = faces
        } else if (wasShowing != faces) {
            turnedFrom = wasShowing
            shown = faces
            turnStartedNanos = nowNanos
        }
        val target = orientationOf(faces)
        val from = turnedFrom ?: return target
        val progress = ((nowNanos - turnStartedNanos) / TURN_NANOS).toFloat()
        if (progress >= 1f) {
            turnedFrom = null
            return target
        }
        return Quaternionf(orientationOf(from)).slerp(target, eased(progress))
    }

    companion object {
        private const val TURN_NANOS = 350_000_000.0

        /** Slow at either end, as a mirror swung on a pivot moves. */
        private fun eased(progress: Float): Float = progress * progress * (3f - 2f * progress)

        private val MODELLED: Matrix3f = frameOf(Direction.NORTH, Direction.EAST)

        fun orientationOf(faces: Mirror): Quaternionf =
            Matrix3f(frameOf(faces.first, faces.second)).mul(Matrix3f(MODELLED).transpose()).getNormalizedRotation(Quaternionf())

        /**
         * The plane between [first] and [second] as three axes: the one it turns about, taken the positive way; the
         * way its face looks, out between the two; and the third, square to both.
         */
        private fun frameOf(first: Direction, second: Direction): Matrix3f {
            val aboutRaw = Vector3f(first.unitVec3f).cross(second.unitVec3f)
            val about = Vector3f(abs(aboutRaw.x), abs(aboutRaw.y), abs(aboutRaw.z))
            val facing = Vector3f(first.unitVec3f).add(second.unitVec3f).normalize()
            val across = Vector3f(facing).cross(about)
            return Matrix3f(about, facing, across)
        }

        /** How the lens modelled on the north face is turned onto [face]. */
        fun lensOrientationOf(face: Direction): Quaternionf =
            Quaternionf().rotationTo(Direction.NORTH.unitVec3f, face.unitVec3f)
    }
}
