package co.voik.agesandtheart.client.panel

import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.material.FogType
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * A camera on a fixed orbit about an Age's arrival point (design §7.8.1).
 *
 * Fixed distance, fixed angle, constant rotation — a rough look at where you are going. The chunks such an
 * orbit can see are a known constant set, which is what lets them be force-loaded exactly; a framing camera
 * would make chunk loading open-ended (`link-panel-research.md`).
 *
 * A subclass because `setPosition` and `setRotation` are protected. `prepareCullFrustum` and
 * `setupPerspective` are widened, because the public `update` would align the camera to the player.
 */
class PanelCamera(private val level: ClientLevel, private val centre: BlockPos) : Camera() {

    init {
        setLevel(level)
    }

    /** `Camera.xRot` and `yRot` are private with no getters, and [describeTo] needs both. */
    private var placedYaw: Float = 0.0f
    private var placedPitch: Float = PITCH_DEGREES

    /**
     * Places the camera for one [shot], whose three values are read against the ranges below.
     *
     * The order after placement is what `Camera.update` does and cannot do for this camera: the projection
     * has to be set up before a frustum can be prepared from it, and the frustum before `extractLevel` can
     * cull against it.
     */
    fun placeAt(shot: Shot, width: Int, height: Int) {
        val angle = shot.turns.toDouble() * TWO_PI
        val orbit = Mth.lerp(shot.closeness, FURTHEST_ORBIT, NEAREST_ORBIT).toDouble()
        val eyeX = centre.x + 0.5 + kotlin.math.cos(angle) * orbit
        val eyeZ = centre.z + 0.5 + kotlin.math.sin(angle) * orbit
        val lift = Mth.lerp(shot.loft, LOWEST_ORBIT, HIGHEST_ORBIT).toDouble()
        val eyeY = liftedClear(eyeX, centre.y + lift, eyeZ)
        val eye = Vec3(eyeX, eyeY, eyeZ)
        setPosition(eye)

        // Aimed from where the eye ended up, since a fixed pitch looks past the arrival once the eye has
        // risen to clear a hill.
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
     * Advances the environment probe, which resolves the Age's fog and sky colours and its sun angle.
     *
     * By hand because `Camera.tick` does it only for a camera with an entity, and an orbit has none; an
     * unticked probe answers `defaultValue()` for everything and the panel would wear vanilla's weather
     * rather than the Age's.
     *
     * Once a tick and not once a frame: the probe interpolates its values across a tick by `partialTicks`,
     * so advancing it at frame rate steps the interpolation base several times per tick and makes colours
     * jitter instead of easing — and each advance re-samples the biomes around the eye.
     */
    fun tickProbe() {
        attributeProbe().tick(level, position())
    }

    /**
     * Fills the render state from this camera, which is `GameRenderer`'s job for the player's frame.
     *
     * `initialized` left false makes vanilla treat the state as a frame that never happened.
     */
    fun describeTo(state: CameraRenderState, width: Int, height: Int) {
        val eye = position()
        state.initialized = true
        state.isPanoramicMode = false
        state.pos = eye
        state.blockPos = BlockPos.containing(eye)
        state.xRot = placedPitch
        state.yRot = placedYaw
        state.orientation = rotation()
        state.cullFrustum = cullFrustum
        state.viewRotationMatrix = getViewRotationMatrix(Matrix4f())
        state.projectionMatrix = projectionFor(width, height)
        state.depthFar = FAR_PLANE
        state.hudFov = FIELD_OF_VIEW
        // An orbit sits in open air by construction; the Age's own air reaches the panel as an environment
        // layer rather than as camera fog.
        state.fogType = FogType.NONE
    }

    /**
     * [from] raised until it is not inside something solid, by at most [MOST_OF_A_LIFT].
     *
     * Capped rather than persistent: an Age that is solid all the way up should show rock.
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

        /**
         * The panel's optics, in one place.
         *
         * The cull frustum is prepared from these, so anything that builds a different projection to draw
         * with culls what it would otherwise have rendered.
         */
        const val FIELD_OF_VIEW = 70.0f
        const val NEAR_PLANE = 0.05f

        /**
         * How far the frustum reaches, which is not how far the panel can see.
         *
         * The sky is far geometry — `SkyRenderer` builds its disc at a radius of 512 — so a far plane near
         * the ring's own size clips it away entirely. Hiding the ring's edge is the fog's job. Vanilla's
         * own floor for the same reason is `cloudRange * 16`, which defaults to this.
         */
        const val FAR_PLANE = 2048.0f

        /** The projection both the frustum and the draw must use, for a target of [width] by [height]. */
        fun projectionFor(width: Int, height: Int): Matrix4f = Matrix4f().setPerspective(
            FIELD_OF_VIEW * Mth.DEG_TO_RAD,
            width.toFloat() / height.toFloat(),
            NEAR_PLANE,
            FAR_PLANE,
        )

        /**
         * How far out a shot may stand, either side of half the streamed ring.
         *
         * The far end is what bounds it: a shot from outside the ring would look across the arrival at the
         * ring's own edge, which only the fog over the shown radius hides.
         */
        private const val NEAREST_ORBIT = 16.0f
        private const val FURTHEST_ORBIT = 30.0f

        /** High enough to look down on the arrival rather than stand in it. */
        private const val LOWEST_ORBIT = 8.0f
        private const val HIGHEST_ORBIT = 22.0f

        /** Vanilla's yaw is degrees clockwise from south, where `atan2` is counted from east. */
        private const val QUARTER_TURN = 90.0f

        /** Enough to clear a hillside and not enough to leave a cavern or the streamed ring. */
        private const val MOST_OF_A_LIFT = 12

        /** Where the pitch starts before the first frame places it properly. */
        private const val PITCH_DEGREES = 20.0f
    }
}
