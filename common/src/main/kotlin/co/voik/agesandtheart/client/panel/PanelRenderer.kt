package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.Constants
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

    /** What shows where the Age is nothing — opaque black, as a panel with no world behind it should be. */
    private const val BEHIND_THE_AGE = 0xFF000000.toInt()

    /** Vanilla's own clear depth. */
    private const val FURTHEST_DEPTH = 1.0

    private val alreadySaid = mutableSetOf<String>()

    /**
     * One line the first time each distinct thing happens, and never again.
     *
     * **Because this runs every frame and the interesting facts are one-offs.** The panel spent four runs
     * black with nothing to say which of half a dozen steps had stopped; a per-frame log would have been
     * nine thousand lines of the same sentence, which is how the *last* diagnosis went wrong.
     *
     * Returns false so a caller can `return sayOnce(...)` where the answer is "nothing drawn".
     */
    fun sayOnce(what: String): Boolean {
        if (alreadySaid.add(what)) Constants.LOG.info("Panel: {}", what)
        return false
    }

    /** Forgotten when a panel closes, so the next book reports its own story rather than inheriting one. */
    fun forget() = alreadySaid.clear()

    /**
     * Renders one frame of [preview] into [PanelTarget].
     *
     * Returns false having drawn nothing when the preview has no chunks yet, which is the ordinary case
     * for the first moments after a book opens — the fade from black *is* the load (§7.8.1), so there is
     * nothing to hide and nothing to wait for.
     */
    fun draw(preview: PreviewLevel, delta: DeltaTracker): Boolean {
        if (preview.wholeness <= 0.0f) return sayOnce("no chunks have arrived yet")
        val target = PanelTarget.get()
        sayOnce(
            "drawing: target ${target.width}x${target.height}, colour=${target.colorTexture != null}, " +
                "depth=${target.depthTexture != null}, chunks=${"%.0f".format(preview.wholeness * 100)}%"
        )
        val camera = preview.camera
        val turns = ((System.nanoTime() - startedAt) / NANOS_PER_SECOND / SECONDS_PER_TURN) % 1.0f
        camera.placeAt(turns, target.width, target.height)

        val state = preview.renderState.levelRenderState.cameraRenderState
        describe(state, camera, target.width, target.height)

        fog.updateBuffer(state.fogData)
        val terrainFog = fog.getBuffer(FogRenderer.FogMode.WORLD)

        // **Put back whatever was set.** This runs inside a screen's frame, where the GUI's own orthographic
        // projection is in force; leaving a perspective one behind would bend every widget drawn after the
        // book. `RenderSystem` hands the current one back, so the swap is symmetrical.
        val outerProjection = RenderSystem.getProjectionMatrixBuffer()
        val outerType = RenderSystem.getProjectionType()
        RenderSystem.setProjectionMatrix(
            projections.getBuffer(state.projectionMatrix),
            com.mojang.blaze3d.ProjectionType.PERSPECTIVE,
        )
        try {

        // **Clear the panel before drawing into it, colour *and depth*.** `GameRenderer` does exactly this
        // to the main target before every level render, and a target of ours that skipped it kept an
        // uninitialised depth buffer — so every fragment failed the depth test and the panel stayed black
        // while the whole world render ran happily behind it.
        val colour = target.colorTexture
        val depth = target.depthTexture
        if (colour != null && depth != null) {
            RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(colour, BEHIND_THE_AGE, depth, FURTHEST_DEPTH)
        }

        // Extract first, then draw: `extractLevel` is what fills `chunkSectionsToRender`, which the draw
        // then consumes, and it is also what asks the sky and weather renderers about *this* level.
        return OffscreenLevelRender.drawing(preview.level, target, camera) {
            preview.renderer.extractLevel(delta, camera, delta.getGameTimeDeltaPartialTick(false))
            // `extractLevel` is what fills this. Null means it decided there was nothing to draw, which is
            // not a failure and not something to draw a half-frame over.
            val sections = preview.renderState.levelRenderState.chunkSectionsToRender
                ?: return@drawing sayOnce("extractLevel decided there was nothing to draw")
            sayOnce("submitting the level render")
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
        } finally {
            // Null where nothing had set one yet, which is not a state we can put back — and not one a
            // screen's frame can be in, since the GUI sets its own before any of this runs.
            outerProjection?.let { RenderSystem.setProjectionMatrix(it, outerType) }
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
        // The camera's own, so the volume drawn and the volume culled are the same one. Building a second
        // projection here is how they came to disagree, and a wider one silently culls what it would draw.
        state.projectionMatrix = PanelCamera.projectionFor(width, height)
        state.depthFar = PanelCamera.FAR_PLANE
        state.hudFov = PanelCamera.FIELD_OF_VIEW
        // What the eye is *inside*, which decides whether the pass draws water or lava fog over the whole
        // frame. An orbit sits in open air by construction, so this is never anything else — and the Age's
        // own air reaches the panel as an environment layer rather than through here.
        state.fogType = FogType.NONE
    }

}
