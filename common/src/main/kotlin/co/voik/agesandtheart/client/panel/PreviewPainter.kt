package co.voik.agesandtheart.client.panel

import co.voik.ephemeris.client.OffscreenLevelRender
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.SectionPos
import net.minecraft.util.ARGB
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Vector4f

/**
 * Draws a [PreviewWorld] onto a render target of its own, from a [PanelCamera] already placed: what
 * `GameRenderer.renderLevel` does for the player, assembled by hand, since that is written around the player's camera,
 * render state and window. One painter to each thing that draws a world off-screen, since each keeps its own fog.
 *
 * Three scopes wrap the render and make three different claims: [BorrowedFrame] lends the client's single projection,
 * camera globals and crosshair; `PanelTarget.redirecting` tells vanilla where the window is; and
 * `OffscreenLevelRender.drawing` tells Ephemeris' painters which level and camera the frame is for.
 */
class PreviewPainter {

    private val fog = FogRenderer()

    /** The world's rain fog, eased from frame to frame apart from the player's ([withTheWorldsRainFog]). */
    private var rainFogMultiplier = NO_RAIN_FOG

    /**
     * The world's distant haze as an opaque colour, from the frame last drawn.
     *
     * A level render clears its background to the fog colour at *alpha zero* and only the sky and the terrain write
     * over it, so a picture laid over anything needs something opaque underneath.
     */
    var haze: Int = BEHIND_THE_WORLD
        private set

    /** Forgets the last world's rain fog. */
    fun startOver() {
        rainFogMultiplier = NO_RAIN_FOG
    }

    /**
     * Draws one frame of [world] from [camera] onto [target], its fog ending [shownRadiusChunks] out; [advanceLight]
     * runs the world's light first, which has to come before its sections are meshed.
     */
    fun paint(
        world: PreviewWorld,
        camera: PanelCamera,
        target: RenderTarget,
        shownRadiusChunks: Int,
        delta: DeltaTracker,
        advanceLight: () -> Unit = world::runTheLight,
    ) {
        val state = world.renderState.cameraRenderState
        camera.describeTo(state, target.width, target.height)
        val terrainFog = describeAtmosphere(state, world, camera, shownRadiusChunks, delta)
        try {
            BorrowedFrame.lentTo(target, camera, world.level, state.projectionMatrix, delta).use {
                clear(target)
                OffscreenLevelRender.drawing(world.level, target, camera) {
                    advanceLight()
                    submit(world, camera, target, state, terrainFog, delta)
                }
            }
        } finally {
            // Rotates the fog's ring buffer and fences it, as `GameRenderer` does for its own. Without it every frame
            // writes the same buffer with no fence while the last frame may still be reading.
            fog.endFrame()
            // The two dispatchers are the client's single instances, and `extractLevel` re-`prepare`s them against
            // this camera. Put the player's back rather than leaving anything that reads them later in the frame
            // answering for a camera somewhere else.
            restoreDispatchers()
        }
    }

    /**
     * The camera fields only. `prepare` would also re-set the crosshair entity, which the preview's own extract passed
     * through unchanged because it reads the same global the player's frame does.
     */
    private fun restoreDispatchers() {
        val minecraft = Minecraft.getInstance()
        val playersCamera = minecraft.gameRenderer.mainCamera()
        minecraft.entityRenderDispatcher.camera = playersCamera
        minecraft.blockEntityRenderDispatcher.prepare(playersCamera.position())
    }

    /**
     * Gives the render state the world's fog and returns the buffer the terrain pass wants.
     *
     * The sky is then unclamped again: `setupFog` derives the sky's fog from the same render distance, which for a
     * ring this small would flatten the whole sky into one disc of fog colour with no sun or stars in it.
     */
    private fun describeAtmosphere(
        state: CameraRenderState,
        world: PreviewWorld,
        camera: PanelCamera,
        shownRadiusChunks: Int,
        delta: DeltaTracker,
    ): GpuBufferSlice {
        val partial = delta.getGameTimeDeltaPartialTick(false)
        state.fogData = withTheWorldsRainFog { fog.setupFog(camera, shownRadiusChunks, delta, 0.0f, world.level) }
        state.fogData.skyEnd = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, partial)
        fog.updateBuffer(state.fogData)

        val colour = state.fogData.color
        haze = ARGB.colorFromFloat(1.0f, colour.x, colour.y, colour.z)
        return fog.getBuffer(FogRenderer.FogMode.WORLD)
    }

    /**
     * Runs [setUp] with the world's rain fog in the atmospheric environment, and gives the player's back.
     *
     * Every `FogRenderer` shares one static list of environments, and the atmospheric one eases its rain fog toward
     * whichever level it was last set up for, so a renderer of the preview's own is not enough to keep the two apart.
     */
    private fun <T> withTheWorldsRainFog(setUp: () -> T): T {
        val atmosphere = FogRenderer.FOG_ENVIRONMENTS.filterIsInstance<AtmosphericFogEnvironment>().firstOrNull()
            ?: return setUp()
        val players = atmosphere.rainFogMultiplier
        atmosphere.rainFogMultiplier = rainFogMultiplier
        try {
            return setUp()
        } finally {
            rainFogMultiplier = atmosphere.rainFogMultiplier
            atmosphere.rainFogMultiplier = players
        }
    }

    /** Colour *and* depth: an uninitialised depth buffer fails every fragment. */
    private fun clear(target: RenderTarget) {
        val colour = target.colorTexture ?: return
        val depth = target.depthTexture ?: return
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorAndDepthTextures(colour, NOTHING_BEHIND_THE_WORLD, depth, FURTHEST_DEPTH)
    }

    /**
     * What `GameRenderer` does to a level renderer each frame, in its order: meshing before extraction, extraction
     * before there is anything to submit.
     */
    private fun submit(
        world: PreviewWorld,
        camera: PanelCamera,
        target: RenderTarget,
        state: CameraRenderState,
        terrainFog: GpuBufferSlice,
        delta: DeltaTracker,
    ) {
        // **The section grid is moved to the camera here rather than left to the render**, which is the ordering the
        // two steps below both depend on. `LevelRenderer.render` opens by repositioning the grid and closes by
        // compiling the sections the extract gathered; a grid that moves *between* those two retires the sections at
        // its trailing edge, and `compileSections` then dereferences a lookup for one of them without a null check.
        // The player's camera never moves far enough between an extract and its render for that to bite. A panel's
        // orbit cutting to a new shot does. Doing it first makes the render's own reposition a no-op.
        val viewArea = world.renderer.viewArea()
        viewArea?.repositionCamera(SectionPos.of(camera.position()))

        // **Culling and section compilation are inside the extract now.** 26.2 folded `cullTerrain` and
        // `compileSections` into `LevelExtractor.extract`, which is also where the frustum is applied.
        world.extractor.extract(delta, camera, delta.getGameTimeDeltaPartialTick(false))

        // And the other half: the extract gathers from the *previous* frame's visibility answer, which was computed
        // against wherever the grid was then, so a moved camera can still put a retired section in the list. Vanilla's
        // invariant is that every gathered section is one the view area still holds; this is that invariant,
        // restated, because the method that relies on it is not ours to guard. A dropped section keeps its old mesh
        // until something dirties it again, which is the right way to be wrong here: a frame late beats a crash.
        world.renderState.sectionUpdateRenderStates.removeIf { update ->
            viewArea?.getRenderSectionAt(SectionPos.of(update.sectionNode()).center()) == null
        }
        // Discarded rather than prevented: `extractLevel` takes particles from the global engine, so these are the
        // player's, gathered into our render state. The list only, never `reset()`: a group's render state is a field
        // of the group itself, so clearing it would empty the player's own particles rather than ours.
        world.renderState.particlesRenderState.particles.clear()

        PanelTarget.redirecting(target) {
            world.renderer.render(
                GraphicsResourceAllocator.UNPOOLED,
                false,
                state,
                terrainFog,
                state.fogData.color,
                true,
                // Whether a post-processing chain is live. A preview has none, and vanilla reads this to decide whether
                // the level needs drawing into a texture it can then run effects over.
                false,
            )
        }
    }

    private companion object {
        const val NO_RAIN_FOG = 0.0f

        const val BEHIND_THE_WORLD = 0xFF000000.toInt()

        /** The same black as [BEHIND_THE_WORLD], as 26.2's clear wants it: a vector rather than a packed int. */
        val NOTHING_BEHIND_THE_WORLD = Vector4f(0.0f, 0.0f, 0.0f, 1.0f)

        /**
         * **Zero, because 26.2's depth runs backwards.** `DepthStencilState.DEFAULT` compares `GREATER_THAN_OR_EQUAL`,
         * which is reversed-Z: the near plane is 1 and the far plane is 0. Clearing to 1 fills the buffer with
         * "something is already here, right against the eye". Every depth clear in vanilla is 0.
         */
        const val FURTHEST_DEPTH = 0.0
    }
}
