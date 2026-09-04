package co.voik.agesandtheart.client.panel

import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * A camera on a fixed orbit about an Age's arrival point (`notes/link-panel-research.md`).
 *
 * **Fixed distance, fixed angle, constant rotation. No fly-by, no framing, no attempt to find a good
 * view** — a rough look at where you are going. That is a taste ruling with a load-bearing consequence:
 * the set of chunks such an orbit can see is *known, constant and small*, so it can be force-loaded
 * exactly and never grows. A free or framing camera makes chunk loading open-ended and turns a bounded
 * feature into an unbounded one.
 *
 * The corollary is worth keeping rather than fixing: an Age whose arrival is underwater, buried or in open
 * sky shows a panel of water, rock or nothing. **The panel says what is there.**
 *
 * A subclass because `setPosition` and `setRotation` are `protected` and reachable no other way. The two
 * widened methods are the frustum and the projection — see the access widener for why `update` could not
 * be used instead.
 */
class PanelCamera(private val level: ClientLevel, private val centre: BlockPos) : Camera() {

    init {
        setLevel(level)
    }

    /**
     * The rotation this camera was last placed at.
     *
     * Kept rather than read back: `Camera.xRot` and `yRot` are private with no getters, and the render
     * state wants both. A camera that places itself already knows them.
     */
    var placedYaw: Float = 0.0f
        private set
    var placedPitch: Float = PITCH_DEGREES
        private set

    /**
     * Places the camera for one frame, [turns] being how far round the orbit has gone (`0..1` is a circle).
     *
     * Everything after the placement is what `Camera.update` does for the player's camera and cannot do for
     * ours: the projection has to be set up before a frustum can be prepared from it, and the frustum has
     * to be prepared before `LevelRenderer.extractLevel` can cull anything against it.
     */
    fun placeAt(turns: Float, width: Int, height: Int) {
        val angle = turns.toDouble() * TWO_PI
        val eyeX = centre.x + 0.5 + kotlin.math.cos(angle) * ORBIT_RADIUS
        val eyeZ = centre.z + 0.5 + kotlin.math.sin(angle) * ORBIT_RADIUS
        // **Lifted out of a block, and no further.** A flat fourteen blocks above the arrival sits *inside*
        // the hill it is passing over on any Age with relief, which draws black and flickers as the orbit
        // sweeps in and out of solid ground.
        //
        // **Chasing the surface instead was worse and is what this replaces.** An arrival at y=-34 with a
        // surface a hundred blocks above put the eye a hundred and forty blocks from what it was looking
        // at, well past a far plane of forty-eight, so the panel went black — and it would have been wrong
        // even in range, since a cavern Age wants a camera *in* the cavern rather than on the roof.
        val eyeY = liftedClear(eyeX, centre.y + ORBIT_HEIGHT, eyeZ)
        val eye = Vec3(eyeX, eyeY, eyeZ)
        setPosition(eye)

        // **Aimed at the arrival rather than tilted by a constant.** Once the eye rises to clear a hill a
        // fixed pitch looks past the thing it came to show, so the angle is worked out from where the
        // camera actually is to where the arrival actually is.
        val toCentreX = centre.x + 0.5 - eyeX
        val toCentreZ = centre.z + 0.5 - eyeZ
        val overGround = kotlin.math.sqrt(toCentreX * toCentreX + toCentreZ * toCentreZ)
        placedYaw = Mth.wrapDegrees(Math.toDegrees(kotlin.math.atan2(toCentreZ, toCentreX)).toFloat() - QUARTER_TURN)
        placedPitch = Math.toDegrees(kotlin.math.atan2(eyeY - centre.y, overGround)).toFloat()
        setRotation(placedYaw, placedPitch)

        setupPerspective(NEAR_PLANE, FAR_PLANE, FIELD_OF_VIEW, width.toFloat(), height.toFloat())
        prepareCullFrustum(getViewRotationMatrix(Matrix4f()), projectionFor(width, height), position())
    }

    /**
     * [from] raised until it is not inside something solid, by at most [MOST_OF_A_LIFT].
     *
     * Capped rather than persistent: an eye that kept climbing would leave the ring it can see, and an Age
     * that is solid all the way up should show rock. The panel says what is there.
     */
    private fun liftedClear(x: Double, from: Double, z: Double): Double {
        val column = BlockPos.containing(x, from, z)
        for (lift in 0..MOST_OF_A_LIFT) {
            val at = column.above(lift)
            if (at.y > level.maxY) break
            if (!level.getBlockState(at).isSolidRender) return from + lift
        }
        return from
    }

    companion object {
        private const val TWO_PI = 2.0 * Math.PI
        private const val BLOCKS_PER_CHUNK = 16

        /**
         * The panel's optics, in one place.
         *
         * **They were in two and disagreed**, which is the kind of fault that shows as geometry ending in
         * a sphere partway through the ring: the cull frustum is prepared from these, and anything that
         * builds a *different* projection to draw with culls what it would otherwise have rendered. One
         * source, read by the camera and by whatever fills the render state.
         */
        const val FIELD_OF_VIEW = 70.0f
        const val NEAR_PLANE = 0.05f

        /**
         * How far the panel can see.
         *
         * Short on purpose, and it is the ring's own radius in blocks: seeing further would show the edge
         * of what was streamed, which reads as the world ending rather than as a small view of it.
         */
        const val FAR_PLANE =
            (co.voik.agesandtheart.book.panel.PanelProtocol.RING_RADIUS_CHUNKS * BLOCKS_PER_CHUNK).toFloat()

        /** The projection both the frustum and the draw must use, for a target of [width] by [height]. */
        fun projectionFor(width: Int, height: Int): Matrix4f = Matrix4f().setPerspective(
            FIELD_OF_VIEW * Mth.DEG_TO_RAD,
            width.toFloat() / height.toFloat(),
            NEAR_PLANE,
            FAR_PLANE,
        )

        /**
         * How far out the orbit sits, in blocks.
         *
         * Chosen against `PanelProtocol.RING_RADIUS_CHUNKS`: the far plane must not see past the chunks
         * that were sent, or a panel shows the ring's own edge as a cliff into nothing.
         */
        private const val ORBIT_RADIUS = 24.0

        /** How high above the arrival the eye sits — enough to look down on it rather than stand in it. */
        private const val ORBIT_HEIGHT = 14.0

        /** Vanilla's yaw is degrees clockwise from south, where `atan2` is counted from east. */
        private const val QUARTER_TURN = 90.0f

        /**
         * How far the eye may climb to get out of a block.
         *
         * Small on purpose. It is enough to clear a hillside and not enough to leave a cavern, and an eye
         * that rose further would be looking at the arrival from outside the ring that was streamed.
         */
        private const val MOST_OF_A_LIFT = 12

        /** Where the pitch starts before the first frame places it properly. */
        private const val PITCH_DEGREES = 20.0f

    }
}
