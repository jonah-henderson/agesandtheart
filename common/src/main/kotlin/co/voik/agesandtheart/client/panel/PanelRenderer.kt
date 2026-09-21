package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.book.panel.PanelRing
import co.voik.ephemeris.client.OffscreenLevelRender
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.mojang.blaze3d.systems.RenderSystem
import org.joml.Vector4f
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.SectionPos
import net.minecraft.util.ARGB
import net.minecraft.world.attribute.EnvironmentAttributes

/**
 * Draws a preview level into the panel's target, once per frame a book is open (design §7.8.1).
 *
 * Assembles by hand what `GameRenderer.renderLevel` assembles for the player, which is written around the
 * player's camera, render state and window. Three scopes wrap the render and make three different claims:
 * [BorrowedFrame] lends the panel the client's single projection, camera globals and crosshair;
 * `PanelTarget.redirecting` tells vanilla where the window is; `OffscreenLevelRender.drawing` tells
 * Ephemeris' painters which level and camera the frame is for.
 */
object PanelRenderer {

    private val fog = FogRenderer()

    /** Which shot the newest field holds, so a cut is noticed once rather than every frame. */
    private var drawn = NO_SHOT

    private const val BEHIND_THE_AGE = 0xFF000000.toInt()

    /** The same black as [BEHIND_THE_AGE], as 26.2's clear wants it: a vector rather than a packed int. */
    private val NOTHING_BEHIND_THE_AGE = Vector4f(0.0f, 0.0f, 0.0f, 1.0f)

    /**
     * **Zero, because 26.2's depth runs backwards.** `DepthStencilState.DEFAULT` compares
     * `GREATER_THAN_OR_EQUAL`, which is reversed-Z: the near plane is 1 and the far plane is 0. Clearing
     * to 1 — the old convention, and what this said through the port — fills the buffer with "something is
     * already here, right against the eye", so what survives the test afterwards is whatever the draw
     * order happens to favour. Every depth clear in vanilla is 0.
     */
    private const val FURTHEST_DEPTH = 0.0

    /** No shot has been drawn since the panel last started over. */
    private const val NO_SHOT = -1

    /**
     * The Age's distant haze as an opaque colour, from the frame last drawn.
     *
     * A level render clears its background to the fog colour at *alpha zero* and only the sky and the
     * terrain write over it, so a panel blitted into a book needs something opaque underneath.
     */
    var haze: Int = BEHIND_THE_AGE
        private set

    /** Forgets what has been drawn, so one book's panel never interlaces with the last one's Age. */
    fun startOver() {
        drawn = NO_SHOT
        PanelTarget.startOver()
    }

    /**
     * Renders one frame of [preview], answering whether anything was drawn.
     *
     * False until a chunk has arrived, which is the ordinary case for the first moments of a book.
     */
    fun draw(preview: PreviewLevel, delta: DeltaTracker): Boolean {
        if (!preview.load.hasAnything) return false

        // Before the target is asked for: turning over is what decides which field the frame lands in.
        val shot = preview.shots.number
        if (shot != drawn) {
            // Never for a book's first shot, which has nothing behind it worth keeping.
            if (drawn != NO_SHOT) PanelTarget.turnOver()
            drawn = shot
        }

        val target = PanelTarget.get()
        val camera = preview.camera
        camera.placeAt(preview.shots.showing(), target.width, target.height)

        val state = preview.renderState.cameraRenderState
        camera.describeTo(state, target.width, target.height)
        val terrainFog = describeAtmosphere(state, preview, delta)

        try {
            return BorrowedFrame.lentTo(target, camera, preview.level, state.projectionMatrix, delta).use {
                clear(target)
                OffscreenLevelRender.drawing(preview.level, target, camera) {
                    submit(preview, camera, state, terrainFog, delta)
                }
            }
        } finally {
            // Rotates the fog's ring buffer and fences it, as `GameRenderer` does for its own. Without it
            // every frame writes the same buffer with no fence while the last frame may still be reading.
            fog.endFrame()
            // The two dispatchers are the client's single instances, and `extractLevel` re-`prepare`s them
            // against the orbit. Put the player's camera back rather than leaving anything that reads them
            // later in the frame answering for a camera over another dimension.
            restoreDispatchers()
        }
    }

    /**
     * The camera fields only. `prepare` would also re-set the crosshair entity, which the panel's own
     * extract passed through unchanged because it reads the same global the player's frame does.
     */
    private fun restoreDispatchers() {
        val minecraft = Minecraft.getInstance()
        val playersCamera = minecraft.gameRenderer.mainCamera()
        minecraft.entityRenderDispatcher.camera = playersCamera
        minecraft.blockEntityRenderDispatcher.prepare(playersCamera.position())
    }

    /**
     * Gives the render state the Age's fog and returns the buffer the terrain pass wants.
     *
     * The shown radius rather than the streamed one, so the fog ends before the outermost ring, which is
     * sent only to let the ring inside it mesh. The sky is then unclamped again: `setupFog` derives the
     * sky's fog from the same render distance, which for a ring this small would flatten the whole sky into
     * one disc of fog colour with no sun or stars in it.
     */
    private fun describeAtmosphere(
        state: CameraRenderState,
        preview: PreviewLevel,
        delta: DeltaTracker,
    ): GpuBufferSlice {
        val partial = delta.getGameTimeDeltaPartialTick(false)
        state.fogData = fog.setupFog(preview.camera, PanelRing.SHOWN_RADIUS_CHUNKS, delta, 0.0f, preview.level)
        state.fogData.skyEnd =
            preview.camera.attributeProbe().getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, partial)
        fog.updateBuffer(state.fogData)

        val colour = state.fogData.color
        haze = ARGB.colorFromFloat(1.0f, colour.x, colour.y, colour.z)
        return fog.getBuffer(FogRenderer.FogMode.WORLD)
    }

    /** Colour *and* depth: an uninitialised depth buffer fails every fragment. */
    private fun clear(target: RenderTarget) {
        val colour = target.colorTexture ?: return
        val depth = target.depthTexture ?: return
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(colour, NOTHING_BEHIND_THE_AGE, depth, FURTHEST_DEPTH)
    }

    /**
     * What `GameRenderer` does to a level renderer each frame, in its order.
     *
     * The order is load-bearing: light before meshing, meshing before extraction, extraction before there
     * is anything to submit.
     */
    private fun submit(
        preview: PreviewLevel,
        camera: PanelCamera,
        state: CameraRenderState,
        terrainFog: GpuBufferSlice,
        delta: DeltaTracker,
    ): Boolean {
        preview.advanceLight()

        // **The section grid is moved to the camera here rather than left to the render**, which is the
        // ordering the two steps below both depend on. `LevelRenderer.render` opens by repositioning the
        // grid and closes by compiling the sections the extract gathered; a grid that moves *between*
        // those two retires the sections at its trailing edge, and `compileSections` then dereferences a
        // lookup for one of them without a null check. The player's camera never moves far enough between
        // an extract and its render for that to bite. An orbit cutting to a new shot does, which is why
        // this crashed a few seconds into a book rather than on the first frame. Doing it first makes the
        // render's own reposition a no-op, so nothing moves under the extract.
        val viewArea = preview.renderer.viewArea()
        viewArea?.repositionCamera(SectionPos.of(camera.position()))

        // **Culling and section compilation are inside the extract now.** 26.2 folded `cullTerrain` and
        // `compileSections` into `LevelExtractor.extract`, which is also where the frustum is applied — so
        // the spectator flag that used to keep an orbit inside terrain from culling the whole Age has no
        // caller-side equivalent, and whether an orbit still draws from inside the rock wants a walk.
        preview.extractor.extract(delta, camera, delta.getGameTimeDeltaPartialTick(false))

        // And the other half: the extract gathers from the *previous* frame's visibility answer, which was
        // computed against wherever the grid was then, so a shot change can still put a retired section in
        // the list. Vanilla's invariant is that every gathered section is one the view area still holds;
        // this is that invariant, restated, because the method that relies on it is not ours to guard.
        // A dropped section keeps its old mesh until something dirties it again, which is the right way to
        // be wrong here — a frame late beats a crash.
        preview.renderState.sectionUpdateRenderStates.removeIf { update ->
            viewArea?.getRenderSectionAt(SectionPos.of(update.sectionNode()).center()) == null
        }
        // Discarded rather than prevented: `extractLevel` takes particles from the global engine, so these
        // are the player's, gathered into our render state. Preventing it would need a Mixin.
        //
        // The list only, never `reset()`: a group's render state is a field of the group itself, so the
        // elements in this list are the same objects the player's frame is about to submit, and clearing
        // them would empty the player's own particles rather than ours.
        preview.renderState.particlesRenderState.particles.clear()

        // **The sections are no longer handed in**: `render` prepares its own chunk renders, so the
        // "nothing to draw" answer the extract used to give the caller is decided inside it now.
        PanelTarget.redirecting {
            preview.renderer.render(
                GraphicsResourceAllocator.UNPOOLED,
                delta,
                false,
                state,
                state.viewRotationMatrix,
                terrainFog,
                state.fogData.color,
                true,
            )
        }
        return true
    }
}
