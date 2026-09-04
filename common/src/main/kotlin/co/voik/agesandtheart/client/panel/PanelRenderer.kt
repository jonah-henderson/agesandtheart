package co.voik.agesandtheart.client.panel

import co.voik.ephemeris.client.OffscreenLevelRender
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.DeltaTracker
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.util.Mth
import net.minecraft.world.level.material.FogType
import org.joml.Matrix4f

/**
 * Draws a preview level into the panel's target, once per frame a book is open (design §7.8.1).
 *
 * **This assembles by hand what `GameRenderer.renderLevel` assembles for the player**, because that method
 * is written around `mainCamera`, the player's `GameRenderState` and the window — none of which a panel
 * has. What it needs turns out to be reachable: `CameraRenderState` is a class of public fields,
 * `GraphicsResourceAllocator.UNPOOLED`, `FogRenderer()` and `ProjectionMatrixBuffer(String)` are all
 * public, and the camera's own frustum comes from the two widened methods.
 *
 * **Two scopes wrap the call and they are not the same claim.** `PanelTarget.redirecting` lies to *vanilla*
 * about where the window is, because `renderLevel` hard-codes `getMainRenderTarget()`.
 * `OffscreenLevelRender.drawing` tells *Ephemeris' painters* which level and camera this frame is for, so
 * the Age's own sky, clouds and horizon are drawn rather than the player's. Neither substitutes for the
 * other.
 *
 * **Unverified against a running client.** Every signature here was read off the 26.1.2 sources and the
 * whole file compiles, but no part of it has been seen to draw anything — see the note at the foot of
 * `notes/link-panel-research.md` for what a first walk should look for.
 */
object PanelRenderer {

    private val fog = FogRenderer()
    private val projections = ProjectionMatrixBuffer("ages linking panel")

    /** How long one full turn of the orbit takes, in seconds. Slow: this is a look, not a fly-by. */
    private const val SECONDS_PER_TURN = 24.0f

    private const val NANOS_PER_SECOND = 1_000_000_000.0f

    private val startedAt = System.nanoTime()

    /**
     * Renders one frame of [preview] into [PanelTarget].
     *
     * Returns false having drawn nothing when the preview has no chunks yet, which is the ordinary case
     * for the first moments after a book opens — the fade from black *is* the load (§7.8.1), so there is
     * nothing to hide and nothing to wait for.
     */
    fun draw(preview: PreviewLevel, delta: DeltaTracker): Boolean {
        if (preview.wholeness <= 0.0f) return false
        val target = PanelTarget.get()
        val camera = preview.camera
        val turns = ((System.nanoTime() - startedAt) / NANOS_PER_SECOND / SECONDS_PER_TURN) % 1.0f
        camera.placeAt(turns, target.width, target.height)

        val state = preview.renderState.levelRenderState.cameraRenderState
        describe(state, camera, target.width, target.height)

        fog.updateBuffer(state.fogData)
        val terrainFog = fog.getBuffer(FogRenderer.FogMode.WORLD)
        RenderSystem.setProjectionMatrix(
            projections.getBuffer(state.projectionMatrix),
            com.mojang.blaze3d.ProjectionType.PERSPECTIVE,
        )

        // Extract first, then draw: `extractLevel` is what fills `chunkSectionsToRender`, which the draw
        // then consumes, and it is also what asks the sky and weather renderers about *this* level.
        return OffscreenLevelRender.drawing(preview.level, target, camera) {
            preview.renderer.extractLevel(delta, camera, delta.getGameTimeDeltaPartialTick(false))
            // `extractLevel` is what fills this. Null means it decided there was nothing to draw, which is
            // not a failure and not something to draw a half-frame over.
            val sections = preview.renderState.levelRenderState.chunkSectionsToRender ?: return@drawing false
            PanelTarget.redirecting {
                preview.renderer.renderLevel(
                    GraphicsResourceAllocator.UNPOOLED,
                    delta,
                    false,
                    state,
                    state.viewRotationMatrix,
                    terrainFog,
                    state.fogData.color,
                    true,
                    sections,
                )
            }
            true
        }
    }

    /**
     * Fills the render state from the camera, which is `GameRenderer`'s job for the player's frame.
     *
     * Every field here is one `renderLevel` or something under it reads. `initialized` is the flag that
     * says so: left false, vanilla treats the state as a frame that never happened.
     */
    private fun describe(
        state: net.minecraft.client.renderer.state.level.CameraRenderState,
        camera: PanelCamera,
        width: Int,
        height: Int,
    ) {
        val eye = camera.position()
        state.initialized = true
        state.isPanoramicMode = false
        state.pos = eye
        state.blockPos = net.minecraft.core.BlockPos.containing(eye)
        state.xRot = camera.placedPitch
        state.yRot = camera.placedYaw
        state.orientation = camera.rotation()
        state.cullFrustum = camera.cullFrustum
        state.viewRotationMatrix = camera.getViewRotationMatrix(Matrix4f())
        state.projectionMatrix = Matrix4f().setPerspective(
            FIELD_OF_VIEW * Mth.DEG_TO_RAD,
            width.toFloat() / height.toFloat(),
            NEAR_PLANE,
            FAR_PLANE,
        )
        state.depthFar = FAR_PLANE
        state.hudFov = FIELD_OF_VIEW
        // What the eye is *inside*, which decides whether the pass draws water or lava fog over the whole
        // frame. An orbit sits in open air by construction, so this is never anything else — and the Age's
        // own air reaches the panel as an environment layer rather than through here.
        state.fogType = FogType.NONE
    }

    private const val FIELD_OF_VIEW = 70.0f
    private const val NEAR_PLANE = 0.05f
    private const val FAR_PLANE = 128.0f
}
