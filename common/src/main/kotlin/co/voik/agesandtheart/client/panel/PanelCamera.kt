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
        val eye = Vec3(
            centre.x + 0.5 + kotlin.math.cos(angle) * ORBIT_RADIUS,
            centre.y + ORBIT_HEIGHT,
            centre.z + 0.5 + kotlin.math.sin(angle) * ORBIT_RADIUS,
        )
        setPosition(eye)
        // Looking inwards and slightly down. Vanilla's yaw is degrees clockwise from south, so the bearing
        // back towards the centre is the orbit angle turned a quarter further round.
        //
        // The two-argument `setRotation` and not the three: the one taking a roll is NeoForge's own
        // addition, and `common` compiles against vanilla, where it does not exist. An orbit wants no roll
        // anyway.
        @Suppress("DEPRECATION")
        placedYaw = Mth.wrapDegrees(Math.toDegrees(angle).toFloat() + YAW_TO_FACE_INWARDS)
        placedPitch = PITCH_DEGREES
        setRotation(placedYaw, placedPitch)

        setupPerspective(NEAR_PLANE, FAR_PLANE, FIELD_OF_VIEW, width.toFloat(), height.toFloat())
        prepareCullFrustum(getViewRotationMatrix(Matrix4f()), projectionFor(width, height), position())
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

        /** Degrees added to the orbit bearing to face the centre rather than away from it. */
        private const val YAW_TO_FACE_INWARDS = 90.0f

        /** Looking down towards the arrival, since the eye is above it. */
        private const val PITCH_DEGREES = 20.0f

    }
}
