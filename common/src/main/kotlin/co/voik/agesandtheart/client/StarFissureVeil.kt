package co.voik.agesandtheart.client

import co.voik.agesandtheart.worldgen.fissure.StarFissureFall
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * What somebody falling through a tear sees: the field everywhere, and the Age through the hole they came
 * in by (design §7.8).
 *
 * **A box round the eye rather than a mask over the screen.** 26.1's `DepthStencilState` carries no stencil
 * at all, so there is no masking to be had; what there is instead is geometry, and geometry is the better
 * answer anyway — the hole is cut in the lid at the tear's own height, so perspective shrinks it as the
 * fall goes on and the opening recedes on its own. Nothing animates it.
 *
 * **Closed, and wound inward.** [AgeRenderTypes.starFissureVeil] culls back faces, so every direction meets
 * exactly one of these quads and the lid and the walls never argue over a pixel. The depth test is
 * `ALWAYS_PASS`, which is what puts the field in front of the ground the fall is passing through — under an
 * Age's world there is terrain between the eye and everything. It also *writes* depth, and that half is
 * what keeps everything drawn after it off it; [closeness] is what makes that depth near enough to be worth
 * anything. Through the hole no depth is written, so the Age's own sky and clouds still show.
 *
 * **Winding is load-bearing and easy to get backwards** (walked 2026-09-15: only the two z walls drew).
 * A face is kept when `(v1 - v0) × (v2 - v1)` points *at* the camera, so every quad below is ordered to
 * give a normal pointing into the box. Check any change against that cross product rather than by eye.
 */
object StarFissureVeil {

    /**
     * The tear being fallen through and the opening it leaves, settled **once a tick** rather than per
     * fissure per frame.
     *
     * Both answers cost a search — one up the player's column, one over the band of columns around the tear
     * — and both were being asked by every fissure block entity in view, every frame, for a whole fall. A
     * spreading tear is many block entities, so that was hundreds of searches a frame for an answer that is
     * the same for all of them.
     *
     * Client-only, and cleared on disconnect like everything else this package holds.
     */
    private var showing: Showing? = null

    private data class Showing(val tear: BlockPos, val opening: StarFissureFall.Opening)

    /** Work out whether the veil is up, at the end of each client tick. */
    fun tick(minecraft: Minecraft) {
        val player = minecraft.player
        showing = when {
            player == null || !StarFissureFall.isFalling(player) -> null
            else -> StarFissureFall.tearOfTheFall(player)
                ?.takeIf { StarFissureFall.eyesInside(player, it) }
                ?.let { Showing(it, StarFissureFall.openingAround(player, it)) }
        }
    }

    /**
     * Whether a tear is being fallen through, which is [StarFissureRenderer]'s cue to draw none of them.
     *
     * A tear's own downward face is the one thing that can stand *in* the hole the lid leaves, and being a
     * star fissure it would fill the opening with the same field the opening exists to interrupt.
     */
    fun hidingTheTears(): Boolean = showing != null

    /** Leaving a server — what this holds means nothing on the next one. */
    fun forget() {
        showing = null
    }

    /** Draw it, at the seam the wounds are drawn from — the pose is camera-relative and untranslated. */
    fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, camera: Vec3) {
        val (tear, opening) = showing ?: return
        val lidY = tear.y + A_BLOCK
        val eye = Eye(camera, closeness(lidY - camera.y))
        val floorY = camera.y - DROPS_BELOW
        val leastX = tear.x + HALF_A_BLOCK - REACHES_OUT
        val mostX = tear.x + HALF_A_BLOCK + REACHES_OUT
        val leastZ = tear.z + HALF_A_BLOCK - REACHES_OUT
        val mostZ = tear.z + HALF_A_BLOCK + REACHES_OUT
        val searchedLeastX = opening.leastX.toDouble()
        val searchedMostX = opening.mostX + A_BLOCK
        val searchedLeastZ = opening.leastZ.toDouble()
        val searchedMostZ = opening.mostZ + A_BLOCK
        collector.submitCustomGeometry(poseStack, AgeRenderTypes.starFissureVeil) { pose, buffer ->
            // The lid, as a frame of four around every column the tear could have taken...
            overhead(pose, buffer, eye, lidY, leastX, mostX, leastZ, searchedLeastZ)
            overhead(pose, buffer, eye, lidY, leastX, mostX, searchedMostZ, mostZ)
            overhead(pose, buffer, eye, lidY, leastX, searchedLeastX, searchedLeastZ, searchedMostZ)
            overhead(pose, buffer, eye, lidY, searchedMostX, mostX, searchedLeastZ, searchedMostZ)
            // ...and then one quad for each it did not, so the hole left is the tear's own shape.
            for (x in opening.leastX..opening.mostX) {
                for (z in opening.leastZ..opening.mostZ) {
                    if (opening.isTorn(x, z)) continue
                    overhead(pose, buffer, eye, lidY, x.toDouble(), x + A_BLOCK, z.toDouble(), z + A_BLOCK)
                }
            }
            // And everything else.
            underfoot(pose, buffer, eye, floorY, leastX, mostX, leastZ, mostZ)
            acrossX(pose, buffer, eye, mostX, floorY, lidY, leastZ, mostZ, facingLess = true)
            acrossX(pose, buffer, eye, leastX, floorY, lidY, leastZ, mostZ, facingLess = false)
            acrossZ(pose, buffer, eye, mostZ, floorY, lidY, leastX, mostX, facingLess = true)
            acrossZ(pose, buffer, eye, leastZ, floorY, lidY, leastX, mostX, facingLess = false)
        }
    }

    /** Where the eye is, and how much nearer it than it stands the box is drawn. */
    private data class Eye(val at: Vec3, val closeness: Double)

    /**
     * How much nearer the eye the whole box is drawn than it stands.
     *
     * **A uniform scale about the eye leaves the picture alone.** Every vertex keeps its direction, so the
     * projection — and with it the opening's perspective, which is the whole illusion — is pixel for pixel
     * what it was. What it does change is the two things read off a vertex rather than off the screen.
     *
     * The **depth** written comes in to just past the near plane, so the water and the dropped items drawn
     * after the field can no longer pass the test in front of it; the field is drawn with the solid
     * features, which is before translucent terrain and before an item entity's own target is composited,
     * and a wall standing sixteen blocks out loses to anything nearer than that.
     *
     * And the **fog** collapses with it: `rendertype_end_portal` fogs by the distance to the vertex, so a
     * foggy Age was washing the field it is being seen through.
     *
     * Held off the near plane by [NEAR_ENOUGH], which binds only in the first ticks of a fall — where the
     * lid is still level with the eye there is nothing between the two to hide anyway.
     */
    private fun closeness(lidAbove: Double): Double =
        maxOf(DRAWN_AT, NEAR_ENOUGH / lidAbove).coerceAtMost(NO_FURTHER_THAN_IT_STANDS)

    /** A lid quad, whose normal must point down at whoever is under it. */
    private fun overhead(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        eye: Eye,
        y: Double,
        leastX: Double,
        mostX: Double,
        leastZ: Double,
        mostZ: Double,
    ) {
        if (mostX <= leastX || mostZ <= leastZ) return
        corner(pose, buffer, eye, leastX, y, leastZ)
        corner(pose, buffer, eye, mostX, y, leastZ)
        corner(pose, buffer, eye, mostX, y, mostZ)
        corner(pose, buffer, eye, leastX, y, mostZ)
    }

    /** The floor, whose normal must point up. */
    private fun underfoot(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        eye: Eye,
        y: Double,
        leastX: Double,
        mostX: Double,
        leastZ: Double,
        mostZ: Double,
    ) {
        corner(pose, buffer, eye, leastX, y, leastZ)
        corner(pose, buffer, eye, leastX, y, mostZ)
        corner(pose, buffer, eye, mostX, y, mostZ)
        corner(pose, buffer, eye, mostX, y, leastZ)
    }

    /** A wall across x, facing back towards the middle — [facingLess] for the one on the far side. */
    private fun acrossX(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        eye: Eye,
        x: Double,
        leastY: Double,
        mostY: Double,
        leastZ: Double,
        mostZ: Double,
        facingLess: Boolean,
    ) {
        if (facingLess) {
            corner(pose, buffer, eye, x, leastY, leastZ)
            corner(pose, buffer, eye, x, leastY, mostZ)
            corner(pose, buffer, eye, x, mostY, mostZ)
            corner(pose, buffer, eye, x, mostY, leastZ)
        } else {
            corner(pose, buffer, eye, x, leastY, leastZ)
            corner(pose, buffer, eye, x, mostY, leastZ)
            corner(pose, buffer, eye, x, mostY, mostZ)
            corner(pose, buffer, eye, x, leastY, mostZ)
        }
    }

    /** A wall across z, facing back towards the middle — [facingLess] for the one on the far side. */
    private fun acrossZ(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        eye: Eye,
        z: Double,
        leastY: Double,
        mostY: Double,
        leastX: Double,
        mostX: Double,
        facingLess: Boolean,
    ) {
        if (facingLess) {
            corner(pose, buffer, eye, leastX, leastY, z)
            corner(pose, buffer, eye, leastX, mostY, z)
            corner(pose, buffer, eye, mostX, mostY, z)
            corner(pose, buffer, eye, mostX, leastY, z)
        } else {
            corner(pose, buffer, eye, leastX, leastY, z)
            corner(pose, buffer, eye, mostX, leastY, z)
            corner(pose, buffer, eye, mostX, mostY, z)
            corner(pose, buffer, eye, leastX, mostY, z)
        }
    }

    /** One vertex, camera-relative and drawn in towards the eye — the format is position and nothing else. */
    private fun corner(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        eye: Eye,
        x: Double,
        y: Double,
        z: Double,
    ) {
        buffer.addVertex(
            pose,
            ((x - eye.at.x) * eye.closeness).toFloat(),
            ((y - eye.at.y) * eye.closeness).toFloat(),
            ((z - eye.at.z) * eye.closeness).toFloat(),
        )
    }

    /**
     * How far the veil stands out from the tear's column.
     *
     * Wider than [StarFissureFall] follows a tear sideways, so the hole can never reach the edge of the lid
     * and leave a gap where the frame runs out.
     */
    private const val REACHES_OUT = 16.0

    /** How far under the eye its floor is laid, which is further than the fall ever gets to see. */
    private const val DROPS_BELOW = 24.0

    /** What fraction of its own size the box is drawn at, once the lid is far enough overhead to allow it. */
    private const val DRAWN_AT = 0.02

    /** How far in front of the 0.05 near plane the lid is kept, in blocks. */
    private const val NEAR_ENOUGH = 0.15

    /** Drawing it further off than it stands would move the picture, so the scale never goes above one. */
    private const val NO_FURTHER_THAN_IT_STANDS = 1.0

    private const val HALF_A_BLOCK = 0.5
    private const val A_BLOCK = 1.0
}
