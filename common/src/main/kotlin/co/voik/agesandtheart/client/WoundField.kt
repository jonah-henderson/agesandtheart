package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.Wounds
import co.voik.agesandtheart.location
import co.voik.agesandtheart.math.mix64
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import kotlin.math.pow
import kotlin.math.sin

/**
 * Every wound in sight, drawn as unlit black holes that will not hold still (design §5.1).
 *
 * **One submission for all of them, and that is what a block entity apiece was costing.** A wound used to
 * carry one so a `BlockEntityRenderer` had something to hang on, which made the count a memory and
 * chunk-NBT problem rather than a drawing one — a ceiling, in a register whose whole point is that the
 * number climbs while nobody is choosing it (§5.2.1). What is left scales with what is *in view*, bounded
 * by the render distance rather than by how long the Age has been coming apart.
 *
 * **The batch is one submission, not one scale**, and the difference matters: the geometry is written out
 * per wound regardless, because a cube must be scaled about its own middle and no single matrix does that
 * for a hundred of them. So each wound having its own phase costs nothing structural — it is a different
 * number inside a loop that was already running. (This was first built when every wound shared one scale,
 * on the argument that a shared scale is what made a batch *equal* to a draw apiece. That argument was
 * wrong about why it works, and the lockstep it justified read as one animated object rather than a field
 * of holes.)
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
 * what a hole needs; the texture is fully opaque anyway, so nothing is given up.
 *
 * **The motion is the other half.** A black cube somebody placed and a hole in the world look identical
 * while they are still, so every wound is rescaled each frame on two sine waves whose periods do not divide
 * into each other — which is what makes it read as unstable rather than as a pulse somebody animated.
 */
object WoundField {

    /**
     * Draw them, at the seam where the block entities used to be drawn.
     *
     * The pose arrives camera-relative and untranslated — `LevelRenderer.submitBlockEntities` pushes and
     * pops around each entity, so by its return the stack is back at the world's own transform. Every
     * vertex below is therefore written in camera-relative world coordinates, which is what lets the whole
     * field go into one submission instead of one per position.
     */
    fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, camera: Vec3) {
        val level = Minecraft.getInstance().level ?: return
        val now = System.currentTimeMillis()
        // Gathered before submitting: the lambda below runs inside the buffer's own bookkeeping, and
        // walking the index there would hold it open for the length of the walk.
        val opening = Wounds.openingIn(level)
        val inSight = mutableListOf<BlockPos>()
        Wounds.eachNear(level, camera, drawnFrom()) { inSight.add(it) }
        if (inSight.isEmpty()) return
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(TEXTURE)) { pose, buffer ->
            for (wound in inSight) {
                val scale = flickerAt(wound, now) * tearingOpen(opening, wound, now)
                for (face in Direction.entries) faceOf(pose, buffer, wound, camera, scale, face)
            }
        }
    }

    /**
     * A wound that has just torn itself open, growing from nothing — **so an Age is watched worsening rather than
     * discovered** (§5.2.1).
     *
     * Only a wound this client saw arrive has a moment to grow from; everything read out of a chunk as it
     * loaded is already open, which is what stops a chunk coming into view from bursting.
     *
     * Cubed, so it starts fast and eases into the flicker rather than arriving at full size with a corner
     * in the motion. The scale it grows into is the flicker's own, so there is no seam between the two:
     * the wound is simply small for the first fraction of a second of its life.
     */
    private fun tearingOpen(opening: Map<BlockPos, Long>?, wound: BlockPos, now: Long): Float {
        val began = opening?.get(wound) ?: return FULLY_OPEN
        val since = now - began
        if (since >= Wounds.OPENS_OVER) return FULLY_OPEN
        val left = 1.0f - since.toFloat() / Wounds.OPENS_OVER
        return 1.0f - left * left * left
    }

    private const val FULLY_OPEN = 1.0f

    /**
     * How far wounds are drawn from, in blocks — the render distance, so they behave like the terrain
     * they are holes in rather than fading at a distance of their own.
     */
    private fun drawnFrom(): Double =
        (Minecraft.getInstance().options.renderDistance().get() * SECTION).toDouble()

    /**
     * One face of one wound's cube, in camera-relative coordinates.
     *
     * Every face is drawn, including the ones against neighbouring blocks: a wound is a hole, and a hole
     * seen from inside the wall it is in should still be a hole.
     *
     * The scale is taken about the block's **own** middle rather than through the pose, which is the whole
     * reason the geometry is written out here: one matrix cannot scale a hundred cubes each about itself.
     */
    private fun faceOf(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        wound: BlockPos,
        camera: Vec3,
        scale: Float,
        face: Direction,
    ) {
        val middleX = (wound.x - camera.x + MIDDLE).toFloat()
        val middleY = (wound.y - camera.y + MIDDLE).toFloat()
        val middleZ = (wound.z - camera.z + MIDDLE).toFloat()
        val normal = face.unitVec3f
        for ((cornerX, cornerY, cornerZ) in CORNERS.getValue(face)) {
            buffer.addVertex(
                pose,
                middleX + (cornerX - MIDDLE) * scale,
                middleY + (cornerY - MIDDLE) * scale,
                middleZ + (cornerZ - MIDDLE) * scale,
            )
                .setColor(BLACK)
                .setUv(0.0f, 0.0f)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                // Full bright, which is what makes it unlit: the world's light never darkens it.
                .setLight(FULL_BRIGHT)
                .setNormal(pose, normal.x(), normal.y(), normal.z())
        }
    }

    /**
     * Where one wound's flicker is this instant.
     *
     * Wall-clock rather than a render tick, because it must not stop when the game is paused or step in
     * time with anything else on screen: a wound is not part of the world's rhythm. Read straight from the
     * system clock — a client-only draw with no need to agree between two machines.
     *
     * **Salted by position, because in lockstep they read as one animated object** (Jonah, 2026-08-09,
     * walked). The salt is a phase *offset on the clock* rather than a different waveform, so every wound
     * lunges the same way and none of them lunge together — which is what a field of separate holes should
     * look like. The two periods do not divide into each other, so an offset of a few seconds decorrelates
     * both waves at once.
     *
     * **This does not cost the batching.** The field is one *submission*, not one *scale*: the geometry is
     * written out per wound anyway, since a cube has to be scaled about its own middle and no single matrix
     * can do that for a hundred of them. Giving each its own phase is a different number in a loop that was
     * already running.
     */
    private fun flickerAt(wound: BlockPos, at: Long): Float {
        val now = (at + phaseOf(wound)).toDouble()
        val fast = sin(now / FAST_PERIOD)
        val slow = sin(now / SLOW_PERIOD)
        val smooth = (fast * FAST_SHARE + slow * (1.0 - FAST_SHARE) + 1.0) / 2.0
        // **Peaked, not sinusoidal.** A sine spends most of its time in the middle, which reads as
        // breathing; raising it to a power drags it down towards the floor and leaves the top as brief
        // spikes, so the wound sits small and *lunges* (walked 2026-08-07).
        val peaked = smooth.pow(PEAKINESS)
        // And a jitter that resamples several times a second, so no two lunges are the same height and the
        // whole thing never settles into a rhythm an eye can follow.
        val jitter = hashedAt(now.toLong() / JITTER_MILLIS xor wound.asLong()) * JITTER_SHARE
        val swing = (peaked + jitter).coerceIn(0.0, 1.0)
        return (SMALLEST + (LARGEST - SMALLEST) * swing).toFloat()
    }

    /**
     * A wound's own place in the cycle, in milliseconds, stable for as long as it is at that position.
     *
     * Spread over far more than either period so the two waves are decorrelated together rather than
     * shifted in step, which is what a single short offset would do.
     */
    private fun phaseOf(wound: BlockPos): Long = mix64(wound.asLong()).mod(PHASES_OVER)

    /**
     * A repeatable value in `-1..1` for a given step, so the jitter is noise rather than randomness —
     * every frame inside one step agrees, and the size does not shiver at the frame rate.
     */
    private fun hashedAt(step: Long): Double = mix64(step).toDouble() / Long.MAX_VALUE

    private val TEXTURE: Identifier = "textures/block/wound.png".location()

    /** Opaque, and every channel at nothing. */
    private const val BLACK = -0x1000000

    /**
     * A packed lightmap coordinate at maximum block *and* sky light — what makes the draw unlit.
     *
     * **Spelled out because 26.1 removed `LightTexture`**, which is where this constant used to live.
     * The packing is unchanged: sky in the high half, block in the low, four bits of sub-position
     * under each, so full bright is 15 in both.
     */
    private const val BRIGHTEST = 15
    private const val SKY_SHIFT = 20
    private const val BLOCK_SHIFT = 4
    private const val FULL_BRIGHT = (BRIGHTEST shl SKY_SHIFT) or (BRIGHTEST shl BLOCK_SHIFT)

    /**
     * The range it lunges over — **a full block at its largest, and never more** (Jonah, 2026-08-09,
     * walked: "some rendering glitches on the wounds").
     *
     * It reached half again as wide, which put the cube through whatever was beside it: two wounds close
     * together interpenetrated, and one in a wall fought the wall for the same pixels. Kept in proportion
     * to what it was, so the *shape* of the flicker is unchanged and only its scale moved — it still sits
     * small and lunges, it just stops at the edges of the block it is a hole in.
     */
    private const val SMALLEST = 0.53
    private const val LARGEST = 1.0

    // Deliberately not a ratio of one another, or the two waves would beat in a visible cycle. Both
    // were halved after the walk: what it wanted was faster and more violent, not wider.
    private const val FAST_PERIOD = 41.0
    private const val SLOW_PERIOD = 157.0

    /** How hard the curve is dragged towards its floor. Above 1 makes the peaks brief and the rest low. */
    private const val PEAKINESS = 3.0

    /** How often the jitter takes a new value, in milliseconds — fast enough to be a twitch. */
    private const val JITTER_MILLIS = 60L

    /** How much of the size the jitter owns. Enough to break the rhythm, not enough to drown the wave. */
    private const val JITTER_SHARE = 0.25

    /** How much of the swing the fast wave owns — most, so it reads as violent rather than breathing. */
    private const val FAST_SHARE = 0.7

    private const val MIDDLE = 0.5f

    private const val SECTION = 16

    /** How wide a spread of phases wounds are dealt from, in milliseconds. */
    private const val PHASES_OVER = 100_000L

    /** The unit cube, wound anticlockwise per face so every one of them faces outward. */
    private val CORNERS: Map<Direction, List<Triple<Float, Float, Float>>> = mapOf(
        Direction.DOWN to listOf(t(0, 0, 0), t(0, 0, 1), t(1, 0, 1), t(1, 0, 0)),
        Direction.UP to listOf(t(0, 1, 0), t(1, 1, 0), t(1, 1, 1), t(0, 1, 1)),
        Direction.NORTH to listOf(t(0, 0, 0), t(1, 0, 0), t(1, 1, 0), t(0, 1, 0)),
        Direction.SOUTH to listOf(t(0, 0, 1), t(0, 1, 1), t(1, 1, 1), t(1, 0, 1)),
        Direction.WEST to listOf(t(0, 0, 0), t(0, 1, 0), t(0, 1, 1), t(0, 0, 1)),
        Direction.EAST to listOf(t(1, 0, 0), t(1, 0, 1), t(1, 1, 1), t(1, 1, 0)),
    )

    private fun t(x: Int, y: Int, z: Int) = Triple(x.toFloat(), y.toFloat(), z.toFloat())
}
