package co.voik.agesandtheart.client

import co.voik.ephemeris.Rgba
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4fc
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry that is **light rather than a surface** — it adds itself to whatever is behind it.
 *
 * **Every shape here fades to nothing at its own edge**, written as alpha on the rim vertices and
 * interpolated across the triangles. There are no textures anywhere in this file and the vertex format
 * carries a position and a colour, so a soft edge has to come from the geometry or not at all.
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
     *
     * [drawnOn] is how the fog treats it, and the two arrangements are opposites: the default fades the
     * glow out exactly where the fog ends, which is what a meteor wants, while
     * [AgeRenderTypes.lightThroughFog] ignores the fog entirely and leaves the falloff to the caller.
     */
    fun halo(
        collector: SubmitNodeCollector,
        poseStack: PoseStack,
        towardCamera: Vec3,
        colour: Rgba,
        radius: Double,
        drawnOn: RenderType = RenderTypes.dragonRays(),
    ) {
        val facing = towardCamera.normalize()
        val right = acrossFrom(facing, ANY_OTHER_WAY) ?: acrossFrom(facing, OR_THIS_WAY) ?: return
        val up = facing.cross(right)
        collector.submitCustomGeometry(poseStack, drawnOn) { pose, buffer ->
            val matrix = pose.pose()
            bloom(matrix, buffer, right, up, radius * SPREAD, colour.faded(HALO_KEEPS))
            bloom(matrix, buffer, right, up, radius, colour)
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

    /**
     * A round bloom with no edge to it: bright in the middle, nothing at all by the rim.
     *
     * **A fan rather than a quad, and that is what buys the softness.** The alpha is written per *vertex*
     * and the rasteriser interpolates it across each triangle, so a centre at full strength and a rim at
     * zero is a radial gradient with no texture and no shader — which is the only way to get a circle out
     * of a pipeline whose whole vertex format is a position and a colour. This was two flat squares, one
     * inside the other, and read as exactly that: a hard-edged box with a second box around it.
     *
     * Wound both ways, because the pipeline culls back faces and which way a billboard came out depends on
     * where the camera happened to be.
     */
    private fun bloom(
        pose: Matrix4fc,
        buffer: VertexConsumer,
        right: Vec3,
        up: Vec3,
        radius: Double,
        colour: Rgba,
    ) {
        fun vertex(at: Vec3, alpha: Float) {
            buffer.addVertex(pose, at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
                .setColor(colour.red, colour.green, colour.blue, alpha)
        }
        fun rim(step: Int): Vec3 {
            val turn = TURN * step / SIDES
            return right.scale(cos(turn) * radius).add(up.scale(sin(turn) * radius))
        }
        for (step in 0..<SIDES) {
            val from = rim(step)
            val to = rim(step + ONE)
            vertex(MIDDLE, colour.alpha); vertex(from, NONE); vertex(to, NONE)
            vertex(MIDDLE, colour.alpha); vertex(to, NONE); vertex(from, NONE)
        }
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

    /** Enough that a bloom's rim reads as a curve rather than as a polygon at any size it is drawn. */
    private const val SIDES = 20

    private const val TURN = 2.0 * Math.PI
    private const val NONE = 0.0f
    private val MIDDLE = Vec3.ZERO

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
