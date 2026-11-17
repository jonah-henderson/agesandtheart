package co.voik.agesandtheart.imaging.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The light leaving a lit carousel, in the light's own tint ([ProjectorLights]): a cone from its front, a fainter one
 * round a brighter, added to what is behind it,
 * flickering gently, with a faint tinge of red, green or blue now and then drifting round its side. Unfocused it widens
 * a little way out and fades to nothing; with a lens ahead it gathers to the lens's middle, still a hand across where it
 * meets the glass so it never narrows to a beam.
 *
 * Drawn on Ages and the Art's `addedLight`, which writes no depth: a glow that did would hide the clouds, water and
 * anything else translucent drawn after it wherever it stood in front of them.
 */
object ProjectorRays {

    private class Shape(val radiusFrom: Double, val radiusTo: Double, val light: Float)

    private val GATHERED = listOf(Shape(0.34, 0.16, 0.05f), Shape(0.2, 0.08, 0.07f))
    private val SPILLED = listOf(Shape(0.3, 0.62, 0.05f), Shape(0.18, 0.4, 0.07f))

    private const val SPILL = 1.1
    private const val LIGHT_AT_THE_LENS = 0.55f
    private const val LIGHT_SPILLED = 0.0f
    private const val SIDES = 24
    private const val FLICKER = 0.25f
    private const val FRAMES_A_SECOND = 24
    private const val JITTER_SHARE = 0.6
    private const val TINGE_EVERY_MILLIS = 5_200L
    private const val TINGE_FOR_MILLIS = 700L
    private const val TINGE = 0.35f
    private const val TINGED_ARC = 6

    /**
     * Draws the light for the carousel at [carousel] facing [facing], gathered to the lens [toLens] blocks ahead where
     * there is one, with positions taken from the block's corner as a block entity's are.
     */
    fun draw(pose: PoseStack.Pose, buffer: VertexConsumer, carousel: BlockPos, facing: Direction, toLens: Int?, tint: Int, millis: Long) {
        val forward = Vec3.atLowerCornerOf(facing.unitVec3i)
        val right = Vec3.atLowerCornerOf(facing.clockWise.unitVec3i)
        val up = Vec3(0.0, 1.0, 0.0)
        val front = Vec3(0.5, 0.5, 0.5).add(forward.scale(0.5 + 0.01))
        val seed = carousel.asLong()
        val flickered = 1.0f - FLICKER * flicker(millis, seed)
        val isGathered = toLens != null
        val end = if (toLens != null) Vec3(0.5, 0.5, 0.5).add(forward.scale(toLens - 0.5)) else front.add(forward.scale(SPILL))
        val lightAtTheEnd = if (isGathered) LIGHT_AT_THE_LENS else LIGHT_SPILLED
        for (shape in if (isGathered) GATHERED else SPILLED) {
            cone(pose, buffer, front, end, right, up, shape, shape.light * flickered, lightAtTheEnd, tint, millis, seed)
        }
    }

    /** A cone's side from [shape]'s near ring round [from] to its far ring round [to], seen from inside and out. */
    private fun cone(
        pose: PoseStack.Pose, buffer: VertexConsumer, from: Vec3, to: Vec3, right: Vec3, up: Vec3,
        shape: Shape, light: Float, lightAtTheEnd: Float, tint: Int, millis: Long, seed: Long,
    ) {
        val tintRed = (tint shr 16 and 0xFF) / 255.0f
        val tintGreen = (tint shr 8 and 0xFF) / 255.0f
        val tintBlue = (tint and 0xFF) / 255.0f
        val far = light * lightAtTheEnd
        fun ring(centre: Vec3, radius: Double, step: Int): Vec3 {
            val angle = step * Mth.TWO_PI.toDouble() / SIDES
            return centre.add(right.scale(cos(angle) * radius)).add(up.scale(sin(angle) * radius))
        }
        fun corner(at: Vec3, brightness: Float, step: Int) {
            val (red, green, blue) = tinge(millis, step, seed)
            buffer.addVertex(pose, at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
                .setColor(red * tintRed * brightness, green * tintGreen * brightness, blue * tintBlue * brightness, 1.0f)
        }
        for (step in 0..<SIDES) {
            val nearA = ring(from, shape.radiusFrom, step)
            val nearB = ring(from, shape.radiusFrom, step + 1)
            val farA = ring(to, shape.radiusTo, step)
            val farB = ring(to, shape.radiusTo, step + 1)
            // Two triangles a side, each way round, since the render type draws triangles and culls their backs.
            corner(nearA, light, step); corner(nearB, light, step + 1); corner(farB, far, step + 1)
            corner(nearA, light, step); corner(farB, far, step + 1); corner(farA, far, step)
            corner(farB, far, step + 1); corner(nearB, light, step + 1); corner(nearA, light, step)
            corner(farA, far, step); corner(farB, far, step + 1); corner(nearA, light, step)
        }
    }

    /**
     * Nought to one, as an old projector flickers: a new brightness each of its twenty-four frames a second, drawn at
     * random, over a slow drift so it never settles into a beat.
     */
    fun flicker(millis: Long, seed: Long): Float {
        val frame = millis * FRAMES_A_SECOND / 1000
        val jitter = Mth.getSeed(frame.toInt(), (seed shr 32).toInt(), seed.toInt()).let { (it ushr 8 and 0xFFFF) / 65535.0 }
        val t = millis / 1000.0 + (seed and 0xFF) * 0.13
        val drift = (sin(t * 2.3) * 0.6 + sin(t * 3.7 + 1.1) * 0.4 + 1.0) / 2.0
        return (jitter * JITTER_SHARE + drift * (1.0 - JITTER_SHARE)).toFloat()
    }

    /**
     * White, but for a short while every few seconds an arc of the cone's side leans a little toward red, green or
     * blue, the arc moving round from one time to the next.
     */
    private fun tinge(millis: Long, step: Int, seed: Long): Triple<Float, Float, Float> {
        val cycle = (millis + (seed and 0xFFFF)) / TINGE_EVERY_MILLIS
        val into = (millis + (seed and 0xFFFF)) % TINGE_EVERY_MILLIS
        val arcStart = Math.floorMod(cycle * 7 + seed, SIDES.toLong()).toInt()
        val intoTheArc = Math.floorMod(step - arcStart, SIDES)
        if (intoTheArc > TINGED_ARC || into > TINGE_FOR_MILLIS) return Triple(1.0f, 1.0f, 1.0f)
        val acrossTheArc = sin(Math.PI * intoTheArc / TINGED_ARC).toFloat()
        val strength = sin(Math.PI * into / TINGE_FOR_MILLIS).toFloat() * TINGE * acrossTheArc
        return when (Math.floorMod(cycle, 3L)) {
            0L -> Triple(1.0f, 1.0f - strength, 1.0f - strength)
            1L -> Triple(1.0f - strength, 1.0f, 1.0f - strength)
            else -> Triple(1.0f - strength, 1.0f - strength, 1.0f)
        }
    }
}
