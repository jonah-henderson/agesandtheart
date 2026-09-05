package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.book.panel.PanelRing
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderBuffers
import net.minecraft.client.renderer.state.GameRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.Difficulty
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.lighting.LevelLightEngine
import java.util.BitSet

/**
 * A second `ClientLevel`, standing only while a book is open, holding the ring of chunks a panel draws.
 *
 * A real level rather than a mesh builder because vanilla's meshing wants a `RenderSectionRegion`, whose
 * constructor is package-private and takes a `ClientLevel` — and because a level gets the per-Age sky, the
 * cloud deck and every environment layer by being one (`link-panel-research.md`).
 *
 * It exists for the length of one screen and is discarded whole, so nothing here reasons about a level that
 * outlives its viewer.
 */
class PreviewLevel private constructor(
    val level: ClientLevel,
    val renderer: LevelRenderer,
    /** Ours rather than the game's: two renderers sharing one would collide over `levelRenderState`. */
    val renderState: GameRenderState,
    val camera: PanelCamera,
    val shots: PanelShots,
    /** How far the Age is at odds with itself as the panel shows it, `0..1` (design §7.3). */
    val unsettled: Float,
    private val around: BlockPos,
    val load: RingLoad,
) : AutoCloseable {

    private var redirtiedOnce = false

    /**
     * Takes one chunk of the ring, as `ClientPacketListener.handleLevelChunkWithLight` would.
     *
     * `applyLightData` and `enableChunkLight` are private on the packet listener, so their bodies are
     * reproduced here; both are short and use only public API. [runTheLight] is the part that is not in
     * either of them.
     */
    fun accept(payload: PanelChunkPayload) {
        val cache = level.chunkSource
        val chunk = cache.replaceWithPacketData(
            payload.x,
            payload.z,
            payload.chunk.readBuffer,
            payload.chunk.heightmaps,
            payload.chunk.getBlockEntitiesTagsConsumer(payload.x, payload.z),
        )
        if (chunk == null) {
            // The view centre and the ring disagree, which is a bug here rather than a network fault.
            Constants.LOG.warn("Panel chunk {},{} fell outside the preview's own range", payload.x, payload.z)
            return
        }

        val lightEngine = cache.lightEngine
        val light = payload.light
        queueSections(
            lightEngine, LightLayer.SKY, payload.x, payload.z,
            light.skyYMask, light.emptySkyYMask, light.skyUpdates,
        )
        queueSections(
            lightEngine, LightLayer.BLOCK, payload.x, payload.z,
            light.blockYMask, light.emptyBlockYMask, light.blockUpdates,
        )
        lightEngine.setLightEnabled(ChunkPos(payload.x, payload.z), true)

        for (index in chunk.sections.indices) {
            lightEngine.updateSectionStatus(
                SectionPos.of(chunk.pos, level.getSectionYFromSectionIndex(index)),
                chunk.sections[index].hasOnlyAir(),
            )
        }
        runTheLight()

        // After the light, so the mesh is built from light that exists.
        level.setSectionRangeDirty(
            payload.x - 1, level.minSectionY, payload.z - 1,
            payload.x + 1, level.maxSectionY, payload.z + 1,
        )
        renderer.onChunkReadyToRender(chunk.pos)
        load.took(payload.x, payload.z)
    }

    /**
     * Publishes queued light, because nothing else in the client will for this level.
     *
     * `queueSectionData` only stages a `DataLayer`; it becomes readable when `runLightUpdates` drains the
     * queue and swaps the visible map. Vanilla drives that once a frame from `ClientLevel.update`, which
     * only ever runs on `minecraft.level`.
     */
    private fun runTheLight() {
        val lightEngine = level.chunkSource.lightEngine
        if (lightEngine.hasLightWork()) lightEngine.runLightUpdates()
    }

    /**
     * The same once a frame, for light a chunk's own arrival could not finish.
     *
     * `markNewInconsistencies` keeps a queued entry whose section has no layer yet, and only a later
     * arrival retries it — so the last chunk's can strand. Re-dirtying is deferred until the ring is whole
     * and done once, because it rebuilds everything the panel can see.
     */
    fun advanceLight() {
        runTheLight()
        // Independent of whether there was light work this frame: `markNewInconsistencies` lowers its flag
        // before re-queuing what it could not store, so by the time the ring is whole `hasLightWork` is
        // already false and a re-dirty gated on it would never run at all.
        if (redirtiedOnce || load.wholeness < 1.0f) return
        redirtiedOnce = true
        val centre = PanelRing.centreOf(around)
        val radius = PanelRing.RADIUS_CHUNKS
        level.setSectionRangeDirty(
            centre.x - radius, level.minSectionY, centre.z - radius,
            centre.x + radius, level.maxSectionY, centre.z + radius,
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
     * `LevelRenderer.close` disposes only its outline target, sky renderer, sampler and clouds; the
     * `ViewArea`'s GPU buffers and the `SectionRenderDispatcher` are released by `setLevel(null)` alone, so
     * both calls are needed or every book opened leaks a view area sized to the player's render distance.
     *
     * `setLevel(null)` also resets the *shared* entity dispatcher's camera, so the player's is put back
     * after — the same restoration [PanelRenderer] does each frame.
     *
     * [SHARED_BUFFERS] is deliberately not released: it belongs to no one preview.
     */
    override fun close() {
        renderer.setLevel(null)
        renderer.close()
        val minecraft = Minecraft.getInstance()
        minecraft.entityRenderDispatcher.camera = minecraft.gameRenderer.mainCamera
    }

    companion object {

        private const val SECTION_BUILDERS = 2

        /**
         * One set of buffers for every panel there will be, because they cannot be given back.
         *
         * A `RenderBuffers` holds a pool of off-heap builders with no `close`, and `LevelRenderer.close`
         * does not dispose the buffers it was handed. Only one panel is ever open, so sharing costs nothing.
         */
        private val SHARED_BUFFERS: RenderBuffers by lazy { RenderBuffers(SECTION_BUILDERS) }

        /** Null when the dimension type the server named is not in the client's registries. */
        fun open(payload: PanelLevelPayload): PreviewLevel? {
            val minecraft = Minecraft.getInstance()
            val connection = minecraft.connection ?: return null
            val dimensionType = connection.registryAccess()
                .lookup(Registries.DIMENSION_TYPE)
                .flatMap { it.get(payload.dimensionType) }
                .orElse(null)
            if (dimensionType == null) {
                Constants.LOG.warn(
                    "A panel named the dimension type {}, which this client has not got",
                    payload.dimensionType,
                )
                return null
            }

            val renderState = GameRenderState()
            val renderer = LevelRenderer(
                minecraft,
                minecraft.entityRenderDispatcher,
                minecraft.blockEntityRenderDispatcher,
                SHARED_BUFFERS,
                renderState,
                minecraft.gameRenderer.featureRenderDispatcher,
            )
            val level = ClientLevel(
                connection,
                ClientLevel.ClientLevelData(Difficulty.PEACEFUL, false, false),
                payload.dimension,
                @Suppress("UNCHECKED_CAST") (dimensionType as Holder<DimensionType>),
                PanelRing.HELD_RADIUS_CHUNKS,
                PanelRing.HELD_RADIUS_CHUNKS,
                renderer,
                false,
                payload.biomeZoomSeed,
                payload.seaLevel,
            )
            // A hand-built renderer has no sky renderer until told to reload, and would carry a null one
            // into `extractLevel`. The same call builds the entity-outline target.
            renderer.onResourceManagerReload(minecraft.resourceManager)
            renderer.setLevel(level)
            // Nothing ticks a level outside `minecraft.level`, so the Age's clock is set once from the
            // server's; the panel is a glance rather than a window, and does not need it to run.
            level.setTimeFromServer(payload.gameTime)
            val centre = PanelRing.centreOf(payload.around)
            level.chunkSource.updateViewCenter(centre.x, centre.z)

            val unsettled = PanelDistortion.unsettledAt(payload.instability)
            // A note to self rather than GPU work: the fields are made lazily, inside the render.
            PanelTarget.showing(unsettled)
            return PreviewLevel(
                level = level,
                renderer = renderer,
                renderState = renderState,
                camera = PanelCamera(level, payload.around),
                shots = PanelShots(payload.biomeZoomSeed, unsettled),
                unsettled = unsettled,
                around = payload.around,
                load = RingLoad(payload.around, payload.chunksComing),
            )
        }
    }
}
