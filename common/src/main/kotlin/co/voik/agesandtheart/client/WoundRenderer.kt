package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.WoundBlockEntity
import co.voik.agesandtheart.location
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import kotlin.math.pow
import kotlin.math.sin

/**
 * A wound, drawn as an unlit black hole that will not hold still (design §5.1).
 *
 * **Pure black at every light level, which is what makes it read as absence.** The texture is one colour
 * and every vertex is lit at full bright, so the world's light never reaches it: a wound is as black at
 * noon on a hilltop as it is in a cave, where an ordinary dark block would be a silhouette in one and
 * invisible in the other.
 *
 * **Opaque, not translucent, and that is a bug fix rather than a preference** (walked 2026-08-07). Drawn
 * through `entityTranslucentEmissive` it went into the *translucent* pass, which sorts separately and does
 * not settle depth against everything — so clouds and weather drew straight over the top of it, and a hole
 * in the world had sky in front of it. `entitySolid` draws in the opaque pass and writes depth, which is
 * what a hole needs; the texture is fully opaque anyway, so nothing is given up. Emissiveness never came
 * from the render type — it comes from the light coordinate below.
 *
 * Nothing here is a shader, and the pipeline stays available if a later pass wants the tear to distort
 * what is *behind* it, which this genuinely cannot do (settled 2026-08-07).
 *
 * **The motion is the other half.** A black cube somebody placed and a hole in the world look identical
 * while they are still, so it is rescaled every frame on two sine waves whose periods do not divide into
 * each other — which is what makes it read as unstable rather than as a pulse somebody animated.
 */
class WoundRenderer : BlockEntityRenderer<WoundBlockEntity, BlockEntityRenderState> {

    override fun createRenderState(): BlockEntityRenderState = BlockEntityRenderState()

    override fun submit(
        state: BlockEntityRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val scale = flickerNow()
        poseStack.pushPose()
        // About the block's own middle, so it breathes in place rather than growing off one corner.
        poseStack.translate(MIDDLE, MIDDLE, MIDDLE)
        poseStack.scale(scale, scale, scale)
        poseStack.translate(-MIDDLE, -MIDDLE, -MIDDLE)
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(TEXTURE)) { pose, buffer ->
            for (face in Direction.entries) faceOf(pose, buffer, face)
        }
        poseStack.popPose()
    }

    /**
     * One face of the unit cube.
     *
     * Every face is drawn, including the ones against neighbouring blocks: a wound is a hole, and a hole
     * seen from inside the wall it is in should still be a hole.
     */
    private fun faceOf(pose: PoseStack.Pose, buffer: VertexConsumer, face: Direction) {
        val corners = CORNERS.getValue(face)
        val normal = face.unitVec3f
        for ((x, y, z) in corners) {
            buffer.addVertex(pose, x, y, z)
                .setColor(BLACK)
                .setUv(0.0f, 0.0f)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                // Full bright, which is what makes it unlit: the world's light never darkens it.
                .setLight(FULL_BRIGHT)
                .setNormal(pose, normal.x(), normal.y(), normal.z())
        }
    }

    /**
     * Where the flicker is this instant.
     *
     * Wall-clock rather than the render state's tick, because it must not stop when the game is paused or
     * step in time with anything else on screen: a wound is not part of the world's rhythm. Read straight
     * from the system clock — a client-only draw with no need to agree between two machines.
     */
    private fun flickerNow(): Float {
        val now = System.currentTimeMillis().toDouble()
        val fast = sin(now / FAST_PERIOD)
        val slow = sin(now / SLOW_PERIOD)
        val smooth = (fast * FAST_SHARE + slow * (1.0 - FAST_SHARE) + 1.0) / 2.0
        // **Peaked, not sinusoidal.** A sine spends most of its time in the middle, which reads as
        // breathing; raising it to a power drags it down towards the floor and leaves the top as brief
        // spikes, so the wound sits small and *lunges* (walked 2026-08-07).
        val peaked = smooth.pow(PEAKINESS)
        // And a jitter that resamples several times a second, so no two lunges are the same height and the
        // whole thing never settles into a rhythm an eye can follow.
        val jitter = hashedAt(now.toLong() / JITTER_MILLIS) * JITTER_SHARE
        val swing = (peaked + jitter).coerceIn(0.0, 1.0)
        return (SMALLEST + (LARGEST - SMALLEST) * swing).toFloat()
    }

    /**
     * A repeatable value in `-1..1` for a given step, so the jitter is noise rather than randomness —
     * every frame inside one step agrees, and the size does not shiver at the frame rate.
     */
    private fun hashedAt(step: Long): Double {
        var bits = step * -0x61c8864680b583ebL
        bits = (bits xor (bits ushr 33)) * -0x40a7b892e31b1a47L
        bits = bits xor (bits ushr 29)
        return (bits.toDouble() / Long.MAX_VALUE)
    }

    private companion object {
        val TEXTURE: Identifier = "textures/block/wound.png".location()

        /** Opaque, and every channel at nothing. */
        const val BLACK = -0x1000000

        /**
         * A packed lightmap coordinate at maximum block *and* sky light — what makes the draw unlit.
         *
         * **Spelled out because 26.1 removed `LightTexture`**, which is where this constant used to live.
         * The packing is unchanged: sky in the high half, block in the low, four bits of sub-position
         * under each, so full bright is 15 in both.
         */
        const val BRIGHTEST = 15
        const val SKY_SHIFT = 20
        const val BLOCK_SHIFT = 4
        const val FULL_BRIGHT = (BRIGHTEST shl SKY_SHIFT) or (BRIGHTEST shl BLOCK_SHIFT)

        /** The range design §5.1 asks for: down to nearly gone, up to half again. */
        const val SMALLEST = 0.8
        const val LARGEST = 1.5

        // Deliberately not a ratio of one another, or the two waves would beat in a visible cycle. Both
        // were halved after the walk: what it wanted was faster and more violent, not wider.
        const val FAST_PERIOD = 41.0
        const val SLOW_PERIOD = 157.0

        /** How hard the curve is dragged towards its floor. Above 1 makes the peaks brief and the rest low. */
        const val PEAKINESS = 3.0

        /** How often the jitter takes a new value, in milliseconds — fast enough to be a twitch. */
        const val JITTER_MILLIS = 60L

        /** How much of the size the jitter owns. Enough to break the rhythm, not enough to drown the wave. */
        const val JITTER_SHARE = 0.25

        /** How much of the swing the fast wave owns — most, so it reads as violent rather than breathing. */
        const val FAST_SHARE = 0.7

        const val MIDDLE = 0.5f

        /** The unit cube, wound anticlockwise per face so every one of them faces outward. */
        val CORNERS: Map<Direction, List<Triple<Float, Float, Float>>> = mapOf(
            Direction.DOWN to listOf(t(0, 0, 0), t(0, 0, 1), t(1, 0, 1), t(1, 0, 0)),
            Direction.UP to listOf(t(0, 1, 0), t(1, 1, 0), t(1, 1, 1), t(0, 1, 1)),
            Direction.NORTH to listOf(t(0, 0, 0), t(1, 0, 0), t(1, 1, 0), t(0, 1, 0)),
            Direction.SOUTH to listOf(t(0, 0, 1), t(0, 1, 1), t(1, 1, 1), t(1, 0, 1)),
            Direction.WEST to listOf(t(0, 0, 0), t(0, 1, 0), t(0, 1, 1), t(0, 0, 1)),
            Direction.EAST to listOf(t(1, 0, 0), t(1, 0, 1), t(1, 1, 1), t(1, 1, 0)),
        )

        fun t(x: Int, y: Int, z: Int) = Triple(x.toFloat(), y.toFloat(), z.toFloat())
    }
}
