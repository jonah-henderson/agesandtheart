package co.voik.agesandtheart.client

import co.voik.ephemeris.Rgba
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4fc

/**
 * Geometry that is **light rather than a surface** — it adds itself to whatever is behind it.
 *
 * `RenderTypes.dragonRays()` is vanilla's own arrangement for exactly this and is borrowed whole: a bare
 * position and a colour, blended `SRC_ALPHA, ONE`, which is what "glowing" means and what an ordinary
 * translucent quad cannot do however bright its colour. It needs no texture, so nothing here waits on the
 * asset pass.
 *
 * **It must not write depth, and the sibling that looks right does.** This was `RenderTypes.lightning()`,
 * which is the same blend on `DepthStencilState.DEFAULT` — so every glow stamped the depth buffer, and
 * water drawn afterwards failed its test and simply vanished in the patch behind one (Jonah, walked). The
 * dragon's rays are the same thing with the write turned off. The cost is that they are triangles rather
 * than quads, which is why the shapes below are assembled a triangle at a time.
 *
 * **Every face is wound both ways** because the pipeline culls back faces and neither of these shapes has
 * a front — a ribbon is a ribbon from either side, and which way round it came out would otherwise depend
 * on where the camera happened to be.
 */
object AddedLight {

    /**
     * A tapered trail behind something moving fast, brightest and widest at the head.
     *
     * [travel] is how far it moved this tick — both the direction the trail lies along and, through
     * [TICKS_OF_TRAIL], how long it is, so something coming in twice as fast draws twice the streak with
     * nothing else said.
     */
    fun streak(
        collector: SubmitNodeCollector,
        poseStack: PoseStack,
        travel: Vec3,
        towardCamera: Vec3,
        colour: Rgba,
        headWidth: Double,
    ) {
        val speed = travel.length()
        if (speed < BARELY_MOVING) return
        val back = travel.scale(-ONE_WHOLE / speed)
        val side = acrossFrom(back, towardCamera) ?: return
        val length = speed * TICKS_OF_TRAIL
        collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays()) { pose, buffer ->
            val matrix = pose.pose()
            ribbon(matrix, buffer, Ribbon(back, side, length, headWidth * SPREAD, colour.faded(HALO_KEEPS)))
            ribbon(matrix, buffer, Ribbon(back, side, length, headWidth, colour))
        }
    }

    /**
     * A round bloom facing the camera, so the thing at the middle of it reads as its source.
     *
     * A core and a much wider, much fainter ring, which added together make a falloff rather than a disc:
     * the same two-quad trick the sky's own glow uses, in world space instead of on the vault.
     */
    fun halo(
        collector: SubmitNodeCollector,
        poseStack: PoseStack,
        towardCamera: Vec3,
        colour: Rgba,
        radius: Double,
    ) {
        val facing = towardCamera.normalize()
        val right = acrossFrom(facing, ANY_OTHER_WAY) ?: acrossFrom(facing, OR_THIS_WAY) ?: return
        val up = facing.cross(right)
        collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays()) { pose, buffer ->
            val matrix = pose.pose()
            disc(matrix, buffer, right, up, radius * SPREAD, colour.faded(HALO_KEEPS))
            disc(matrix, buffer, right, up, radius, colour)
        }
    }

    /**
     * A unit vector square to both, or nothing where the two are so nearly in line that there is no such
     * direction — a streak coming straight at the camera, which is the one case with no ribbon to draw.
     */
    private fun acrossFrom(along: Vec3, toward: Vec3): Vec3? {
        val across = along.cross(toward)
        if (across.lengthSqr() < EDGE_ON) return null
        return across.normalize()
    }

    private fun ribbon(pose: Matrix4fc, buffer: VertexConsumer, shape: Ribbon) {
        for (segment in 0..<SEGMENTS) {
            val from = segment.toDouble() / SEGMENTS
            val to = (segment + ONE).toDouble() / SEGMENTS
            shape.quad(pose, buffer, from, to)
            // Swapping the ends reverses the winding, and so draws the same quad seen from the other side.
            shape.quad(pose, buffer, to, from)
        }
    }

    private fun disc(
        pose: Matrix4fc,
        buffer: VertexConsumer,
        right: Vec3,
        up: Vec3,
        radius: Double,
        colour: Rgba,
    ) {
        fun corner(acrossBy: Double, upBy: Double) {
            val at = right.scale(acrossBy * radius).add(up.scale(upBy * radius))
            buffer.addVertex(pose, at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
                .setColor(colour.red, colour.green, colour.blue, colour.alpha)
        }
        // Four triangles: the square, and the square again wound the other way.
        corner(-ONE_WHOLE, -ONE_WHOLE); corner(ONE_WHOLE, -ONE_WHOLE); corner(ONE_WHOLE, ONE_WHOLE)
        corner(-ONE_WHOLE, -ONE_WHOLE); corner(ONE_WHOLE, ONE_WHOLE); corner(-ONE_WHOLE, ONE_WHOLE)

        corner(ONE_WHOLE, ONE_WHOLE); corner(ONE_WHOLE, -ONE_WHOLE); corner(-ONE_WHOLE, -ONE_WHOLE)
        corner(-ONE_WHOLE, ONE_WHOLE); corner(ONE_WHOLE, ONE_WHOLE); corner(-ONE_WHOLE, -ONE_WHOLE)
    }

    /** One trail, laid out so a quad of it is two numbers along its length. */
    private class Ribbon(
        val back: Vec3,
        val side: Vec3,
        val length: Double,
        val headWidth: Double,
        val colour: Rgba,
    ) {
        /** One segment of the trail, as the two triangles a four-cornered piece of it comes to. */
        fun quad(pose: Matrix4fc, buffer: VertexConsumer, from: Double, to: Double) {
            corner(pose, buffer, from, ONE_EDGE)
            corner(pose, buffer, from, THE_OTHER_EDGE)
            corner(pose, buffer, to, THE_OTHER_EDGE)

            corner(pose, buffer, from, ONE_EDGE)
            corner(pose, buffer, to, THE_OTHER_EDGE)
            corner(pose, buffer, to, ONE_EDGE)
        }

        private fun corner(pose: Matrix4fc, buffer: VertexConsumer, along: Double, edge: Double) {
            val left = ONE_WHOLE - along
            val at = back.scale(along * length).add(side.scale(edge * headWidth * left))
            buffer.addVertex(pose, at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
                .setColor(colour.red, colour.green, colour.blue, colour.alpha * (left * left).toFloat())
        }
    }

    private fun Rgba.faded(to: Float) = copy(alpha = alpha * to)

    /** How many ticks of movement a streak lies over, so speed alone decides its length. */
    private const val TICKS_OF_TRAIL = 2.6

    /** How much wider the outer bloom is than the core, and how much of the core's strength it keeps. */
    private const val SPREAD = 3.0
    private const val HALO_KEEPS = 0.28f

    /** Enough for the taper to read; a streak is a shape, not a mesh. */
    private const val SEGMENTS = 8

    private const val BARELY_MOVING = 0.05
    private const val EDGE_ON = 1.0e-6

    /** Anything not along the axis being crossed. The second covers a camera pointed straight up. */
    private val ANY_OTHER_WAY = Vec3(0.0, 1.0, 0.0)
    private val OR_THIS_WAY = Vec3(1.0, 0.0, 0.0)

    private const val ONE_EDGE = 1.0
    private const val THE_OTHER_EDGE = -1.0

    private const val ONE = 1
    private const val ONE_WHOLE = 1.0
}
