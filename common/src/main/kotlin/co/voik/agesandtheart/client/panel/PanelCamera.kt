package co.voik.agesandtheart.client.panel

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.Projection
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

    /**
     * The draw's projection, kept as vanilla's own object rather than as a matrix we write.
     *
     * `Camera` has one of these privately and hands it to `extractRenderState`; an orbit cannot use that
     * path, because `Camera.extractRenderState` reaches for the player and the level it is attached to.
     * So the panel keeps its own, which costs one field and buys every depth convention 26.2 has.
     */
    private val drawProjection = Projection()

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
        // **A level orbit, even where it passes through rock** (Jonah, 2026-09-20). Raising the eye out of
        // whatever it entered kept the view clear one frame at a time, and the cost was the shape of the
        // orbit itself: the ring rose and fell with the ground under it, so the turn lurched wherever the
        // terrain did. A steady circle that sometimes goes inside a hill reads better than a clear view on
        // a path nobody could have drawn.
        val eye = Vec3(eyeX, centre.y + lift, eyeZ)
        setPosition(eye)

        // Aimed at the arrival rather than held at a fixed pitch, so closeness and loft can move the eye
        // without the subject sliding out of the middle.
        val toCentreX = centre.x + 0.5 - eyeX
        val toCentreZ = centre.z + 0.5 - eyeZ
        val overGround = kotlin.math.sqrt(toCentreX * toCentreX + toCentreZ * toCentreZ)
        placedYaw = Mth.wrapDegrees(Math.toDegrees(kotlin.math.atan2(toCentreZ, toCentreX)).toFloat() - QUARTER_TURN)
        placedPitch = Math.toDegrees(kotlin.math.atan2(lift, overGround)).toFloat()
        setRotation(placedYaw, placedPitch)

        setupPerspective(NEAR_PLANE, FAR_PLANE, FIELD_OF_VIEW, width.toFloat(), height.toFloat())
        drawProjection.setupPerspective(NEAR_PLANE, FAR_PLANE, FIELD_OF_VIEW, width.toFloat(), height.toFloat())
        prepareCullFrustum(getViewRotationMatrix(Matrix4f()), cullingProjectionFor(width, height), position())
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
        // **Vanilla's own projection, never one of ours.** 26.2 draws with reversed depth — the near plane
        // is 1 and the far plane 0 — and `Projection.getMatrix` produces it by handing JOML `zFar` as the
        // near argument and `zNear` as the far one, with the backend's clip-space convention alongside. A
        // plainly-written `setPerspective(fov, aspect, near, far)` is the *forward* matrix, which under
        // 26.2's `GREATER_THAN_OR_EQUAL` test lets whatever is furthest away win every pixel: terrain seen
        // through itself, caves and their lava drawn over the ground above them. Asking vanilla is also the
        // only version of this that stays right if they turn the depth round again.
        state.projectionMatrix = drawProjection.getMatrix(Matrix4f())
        state.depthFar = FAR_PLANE
        state.hudFov = FIELD_OF_VIEW
        // The Age's own air reaches the panel as an environment layer rather than as camera fog — which
        // is also what leaves an eye inside rock seeing through it rather than blacked out.
        state.fogType = FogType.NONE
    }

    companion object {
        private const val TWO_PI = 2.0 * Math.PI

        /**
         * The panel's optics, in one place.
         *
         * **These are shared; the two matrices built from them are not.** The frustum's and the draw's
         * must describe the same cone of the world, or the panel culls what it would have drawn — but
         * since 26.2 they are not the *same matrix*, because the draw's depth runs backwards and the
         * frustum's does not. Keeping the optics here and the two constructions apart is what lets both
         * stay true at once.
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

        /**
         * The matrix the **frustum** is built from — which since 26.2 is *not* the one the draw uses.
         *
         * Near and far the ordinary way round, as vanilla's own `createProjectionMatrixForCulling` has
         * them: the frustum wants planes in world terms and does not care which end of the depth range
         * the GPU calls near. What it does care about is [zZeroToOne], because that decides where JOML
         * puts the near plane and so what the frustum thinks it can see.
         *
         * The draw's matrix is [Projection]'s, built in [placeAt] — see [describeTo] for why it cannot
         * be this one.
         */
        fun cullingProjectionFor(width: Int, height: Int): Matrix4f = Matrix4f().setPerspective(
            FIELD_OF_VIEW * Mth.DEG_TO_RAD,
            width.toFloat() / height.toFloat(),
            NEAR_PLANE,
            FAR_PLANE,
            zZeroToOne(),
        )

        /**
         * Whether this GPU's clip space runs its depth 0..1 or −1..1 — a backend fact, not a setting, and
         * the reason it has to be asked at all is that 26.2 can be on Vulkan or on OpenGL.
         */
        private fun zZeroToOne(): Boolean = RenderSystem.getDevice().deviceInfo.isZZeroToOne

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

        /** Where the pitch starts before the first frame places it properly. */
        private const val PITCH_DEGREES = 20.0f
    }
}
