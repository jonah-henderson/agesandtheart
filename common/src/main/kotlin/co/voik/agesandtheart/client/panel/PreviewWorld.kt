package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.client.FissureField
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.Difficulty
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.lighting.LevelLightEngine
import java.util.BitSet

/**
 * A second `ClientLevel` with a renderer of its own, holding chunks the server streamed for it: what a linking panel
 * draws ([PreviewLevel]), and anything else that draws a level off-screen ([PreviewPainter]).
 *
 * A real level rather than a mesh builder because vanilla's meshing wants a `RenderSectionRegion`, whose constructor is
 * package-private and takes a `ClientLevel`, and because a level gets the per-Age sky, the cloud deck and every
 * environment layer by being one (`link-panel-research.md`). It is discarded whole, so nothing here reasons about a
 * level that outlives what it was stood up for.
 */
class PreviewWorld private constructor(
    val level: ClientLevel,
    val renderer: LevelRenderer,
    /** What binds a level to the renderer in 26.2, and what a second level therefore needs its own of. */
    val extractor: LevelExtractor,
    /** Ours rather than the game's: two renderers sharing one would collide over what they extracted. */
    val renderState: LevelRenderState,
) : AutoCloseable {

    /** What a world is stood up from: the level the server named, and what a client cannot work out about it. */
    class Description(
        val dimension: ResourceKey<Level>,
        val dimensionType: Identifier,
        val biomeZoomSeed: Long,
        val seaLevel: Int,
        val gameTime: Long,
        val rain: Float,
        val thunder: Float,
        /** The chunk its chunks are held around, and how far: the client's chunk cache refuses one outside it. */
        val centre: ChunkPos,
        val heldRadiusChunks: Int,
        /** The largest target it is drawn onto, which the renderer sizes its own targets from. */
        val width: Int,
        val height: Int,
        /** A superflat level's horizon is low, and anything else's lower half of the sky is the void's dark. */
        val isFlat: Boolean = false,
    )

    /**
     * Takes one chunk, as `ClientPacketListener.handleLevelChunkWithLight` would, answering whether it fell inside the
     * world.
     *
     * `applyLightData` and `enableChunkLight` are private on the packet listener, so their bodies are reproduced here;
     * both are short and use only public API. [runTheLight] is the part that is not in either of them.
     */
    fun accept(x: Int, z: Int, data: ClientboundLevelChunkPacketData, light: ClientboundLightUpdatePacketData): Boolean {
        val cache = level.chunkSource
        val chunk = cache.replaceWithPacketData(x, z, data)
        if (chunk == null) {
            // The view centre and the ring disagree, which is a bug here rather than a network fault.
            Constants.LOG.warn("Preview chunk {},{} fell outside the preview's own range", x, z)
            return false
        }
        // Its fissures, which nothing else tells about a chunk a preview receives.
        FissureField.stocked(level, chunk)

        val lightEngine = cache.lightEngine
        queueSections(lightEngine, LightLayer.SKY, x, z, light.skyYMask, light.emptySkyYMask, light.skyUpdates)
        queueSections(lightEngine, LightLayer.BLOCK, x, z, light.blockYMask, light.emptyBlockYMask, light.blockUpdates)
        lightEngine.setLightEnabled(ChunkPos(x, z), true)

        for (index in chunk.sections.indices) {
            lightEngine.updateSectionStatus(
                SectionPos.of(chunk.pos, level.getSectionYFromSectionIndex(index)),
                chunk.sections[index].hasOnlyAir(),
            )
        }
        runTheLight()

        // After the light, so the mesh is built from light that exists.
        level.setSectionRangeDirty(x - 1, level.minSectionY, z - 1, x + 1, level.maxSectionY, z + 1)
        return true
    }

    /**
     * Publishes queued light, because nothing else in the client will for this level.
     *
     * `queueSectionData` only stages a `DataLayer`; it becomes readable when `runLightUpdates` drains the queue and
     * swaps the visible map. Vanilla drives that once a frame from `ClientLevel.update`, which only ever runs on
     * `minecraft.level`.
     */
    fun runTheLight() {
        val lightEngine = level.chunkSource.lightEngine
        if (lightEngine.hasLightWork()) lightEngine.runLightUpdates()
    }

    /**
     * Rebuilds every section within [radiusChunks] of [centre], for light a chunk's own arrival could not finish.
     *
     * `markNewInconsistencies` keeps a queued entry whose section has no layer yet, and only a later arrival retries
     * it, so the last chunk's can strand. Done once, when everything has come, because it rebuilds everything.
     */
    fun rebuildAround(centre: ChunkPos, radiusChunks: Int) {
        level.setSectionRangeDirty(
            centre.x - radiusChunks, level.minSectionY, centre.z - radiusChunks,
            centre.x + radiusChunks, level.maxSectionY, centre.z + radiusChunks,
        )
    }

    private fun queueSections(
        lightEngine: LevelLightEngine,
        layer: LightLayer,
        x: Int,
        z: Int,
        present: BitSet,
        empty: BitSet,
        updates: List<ByteArray>,
    ) {
        val data = updates.iterator()
        for (index in 0..<lightEngine.lightSectionCount) {
            val hasData = present.get(index)
            if (!hasData && !empty.get(index)) continue
            lightEngine.queueSectionData(
                layer,
                SectionPos.of(x, lightEngine.minLightSection + index, z),
                if (hasData) DataLayer(data.next().clone()) else DataLayer(),
            )
        }
    }

    /**
     * Releases the renderer, its section buffers and its dispatcher.
     *
     * Both calls are needed and they let go of different things: the extractor's `setLevel(null)` releases the view
     * area's GPU buffers and the section dispatcher, and `LevelRenderer.close` disposes the outline target, sky
     * renderer, sampler and clouds. Dropping either leaks a view area sized to the player's render distance every time
     * a world is stood up.
     *
     * `setLevel(null)` also resets the *shared* entity dispatcher's camera, so the player's is put back after, the same
     * restoration [PreviewPainter] does each frame.
     */
    override fun close() {
        extractor.setLevel(null)
        renderer.close()
        val minecraft = Minecraft.getInstance()
        minecraft.entityRenderDispatcher.camera = minecraft.gameRenderer.mainCamera()
    }

    companion object {

        /** Null when the dimension type the server named is not in the client's registries. */
        fun standUp(description: Description): PreviewWorld? {
            val minecraft = Minecraft.getInstance()
            val connection = minecraft.connection ?: return null
            val dimensionType = connection.registryAccess()
                .lookup(Registries.DIMENSION_TYPE)
                .flatMap { it.get(description.dimensionType) }
                .orElse(null)
            if (dimensionType == null) {
                Constants.LOG.warn("A preview named the dimension type {}, which this client has not got", description.dimensionType)
                return null
            }

            // **The renderer no longer holds a level**: 26.2 split the pass in two, and what is bound to a level is the
            // extractor. It is sized to the largest target it is drawn onto, since it sizes its own targets from it.
            val renderer = LevelRenderer(
                minecraft.entityRenderDispatcher,
                minecraft.blockEntityRenderDispatcher,
                minecraft.modelManager,
                minecraft.textureManager,
                minecraft.atlasManager,
                minecraft.shaderManager,
                minecraft.gameRenderer,
                description.width,
                description.height,
            )
            val renderState = LevelRenderState()
            // **And the renderer has to be told about it, because its constructor was not.** Only the extractor half
            // of 26.2's split takes a render state; `LevelRenderer` reaches for the player's on the last line of its
            // constructor, so a second one built the sanctioned way draws the player's entities, block entities,
            // clouds and dirty sections no matter which level the extractor beside it is filling. See the access
            // widener for the whole of it. Assigned before the renderer is used for anything.
            renderer.levelRenderState = renderState
            val extractor = LevelExtractor(minecraft, renderState, renderer)
            val level = ClientLevel(
                connection,
                ClientLevel.ClientLevelData(Difficulty.PEACEFUL, false, description.isFlat),
                description.dimension,
                @Suppress("UNCHECKED_CAST") (dimensionType as Holder<DimensionType>),
                description.heldRadiusChunks,
                description.heldRadiusChunks,
                extractor,
                false,
                description.biomeZoomSeed,
                description.seaLevel,
            )
            // A hand-built extractor has no sky renderer until told to reload, and would carry a null one into the
            // extraction. The same call builds the entity-outline target.
            extractor.onResourceManagerReload(minecraft.resourceManager)
            extractor.setLevel(level)
            // Nothing ticks a level outside `minecraft.level`, so its clock is set once from the server's; a preview is
            // a glance rather than a window, and does not need it to run.
            level.setTimeFromServer(description.gameTime)
            level.setRainLevel(description.rain)
            level.setThunderLevel(description.thunder)
            level.chunkSource.updateViewCenter(description.centre.x, description.centre.z)
            return PreviewWorld(level, renderer, extractor, renderState)
        }
    }
}
