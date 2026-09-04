package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderBuffers
import net.minecraft.client.renderer.state.GameRenderState
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.dimension.DimensionType

/**
 * A second `ClientLevel`, standing only while a book is open, holding the ring of chunks a panel draws.
 *
 * **The whole feature turns on this being cheap to stand up and certain to come down.** It exists for the
 * length of one screen, holds a fixed and known set of chunks, and is discarded whole rather than emptied —
 * so nothing here has to reason about a level that outlives its viewer.
 *
 * **Why a real level and not a mesh builder** (`link-panel-research.md`): vanilla's own meshing wants a
 * `RenderSectionRegion`, whose constructor is package-private *and* takes a `ClientLevel`, so a synthetic
 * block snapshot cannot drive it. And a bespoke builder would have to reimplement the per-Age sky, the
 * cloud deck and every environment layer to make a panel look like the Age it shows, where a real level
 * gets all of them by being a level.
 */
class PreviewLevel private constructor(
    val level: ClientLevel,
    val renderer: LevelRenderer,
    /** Ours rather than the game's: two renderers sharing one would collide over `levelRenderState`. */
    val renderState: GameRenderState,
    /** The orbit this preview is looked at from, made with the level so the two cannot disagree. */
    val camera: PanelCamera,
    private val chunksExpected: Int,
) : AutoCloseable {

    private var chunksArrived = 0

    /** How far along the load is, `0..1` — what the fade actually reads. */
    val wholeness: Float
        get() = if (chunksExpected <= 0) 1.0f else (chunksArrived.toFloat() / chunksExpected).coerceIn(0.0f, 1.0f)

    /**
     * Takes one chunk of the ring, exactly as `ClientPacketListener.handleLevelChunkWithLight` would.
     *
     * The two halves are vanilla's own and are replayed rather than reimplemented: the chunk data goes into
     * the chunk cache, and the light sections are queued into the light engine. `applyLightData` and
     * `enableChunkLight` are private on the packet listener, so their bodies are the one thing here that is
     * copied rather than called — both are short, and both use only public API.
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
            // Out of the cache's range, which means the view centre and the ring disagree — a bug here
            // rather than a network fault, and silent dropping is how it would hide.
            Constants.LOG.warn("Panel chunk {},{} fell outside the preview's own range", payload.x, payload.z)
            return
        }

        val lightEngine = cache.lightEngine
        queueSections(payload, lightEngine, LightLayer.SKY)
        queueSections(payload, lightEngine, LightLayer.BLOCK)
        lightEngine.setLightEnabled(ChunkPos(payload.x, payload.z), true)

        // What `enableChunkLight` does: tell the engine which sections are air, then mark the neighbourhood
        // for meshing. Without the second the chunk arrives and is never drawn.
        val sections = chunk.sections
        for (index in sections.indices) {
            lightEngine.updateSectionStatus(
                SectionPos.of(chunk.pos, level.getSectionYFromSectionIndex(index)),
                sections[index].hasOnlyAir(),
            )
        }
        level.setSectionRangeDirty(
            payload.x - 1, level.minSectionY, payload.z - 1,
            payload.x + 1, level.maxSectionY, payload.z + 1,
        )
        renderer.onChunkReadyToRender(chunk.pos)
        chunksArrived++
    }

    private fun queueSections(
        payload: PanelChunkPayload,
        lightEngine: net.minecraft.world.level.lighting.LevelLightEngine,
        layer: LightLayer,
    ) {
        val present = if (layer == LightLayer.SKY) payload.light.skyYMask else payload.light.blockYMask
        val empty = if (layer == LightLayer.SKY) payload.light.emptySkyYMask else payload.light.emptyBlockYMask
        val data = (if (layer == LightLayer.SKY) payload.light.skyUpdates else payload.light.blockUpdates).iterator()
        for (index in 0..<lightEngine.lightSectionCount) {
            val sectionY = lightEngine.minLightSection + index
            val hasData = present.get(index)
            val isEmpty = empty.get(index)
            if (!hasData && !isEmpty) continue
            lightEngine.queueSectionData(
                layer,
                SectionPos.of(payload.x, sectionY, payload.z),
                if (hasData) DataLayer(data.next().clone()) else DataLayer(),
            )
        }
    }

    /**
     * Drops the renderer. The buffers outlive it deliberately — see [SHARED_BUFFERS].
     *
     * `LevelRenderer.close` disposes its outline target, its sky renderer, its layer sampler and its cloud
     * renderer, and **not** the `RenderBuffers` it was handed. That is the whole reason the buffers are
     * shared rather than made per preview.
     */
    override fun close() {
        renderer.close()
    }

    companion object {

        /**
         * How many section builders the preview's [RenderBuffers] gets.
         *
         * **Small on purpose.** The game's own pool is sized for a render distance; a panel meshes a ring
         * of forty-nine chunks once and never grows, so this is the difference between the "large standing
         * allocation" `link-panel-research.md` feared and something a client can afford to keep.
         */
        private const val SECTION_BUILDERS = 2

        /**
         * One set of buffers for every panel there will ever be, made on first use.
         *
         * **Shared rather than per preview because they cannot be given back.** A `RenderBuffers` holds a
         * `SectionBufferBuilderPool` of off-heap `ByteBufferBuilder`s; the pool has no `close`, the packs
         * inside it are not reachable from outside, and `LevelRenderer.close` does not dispose the buffers
         * it was handed. So one per book opened is native memory that nothing can reclaim — where one for
         * the life of the client is a bounded cost paid once. Only one panel is ever open (§7.8.1), so
         * sharing costs nothing in contention.
         */
        private val SHARED_BUFFERS: RenderBuffers by lazy { RenderBuffers(SECTION_BUILDERS) }

        /**
         * Stands one up from what the server said.
         *
         * Null when the dimension type it names is not in the client's registries, which should not happen
         * and is a dropped panel rather than a crash if it does.
         */
        fun open(payload: PanelLevelPayload): PreviewLevel? {
            val minecraft = Minecraft.getInstance()
            val connection = minecraft.connection ?: return null
            val dimensionType = connection.registryAccess()
                .lookup(Registries.DIMENSION_TYPE)
                .flatMap { it.get(payload.dimensionType) }
                .orElse(null)
            if (dimensionType == null) {
                Constants.LOG.warn("A panel named the dimension type {}, which this client has not got", payload.dimensionType)
                return null
            }

            val centre = ChunkPos(
                SectionPos.blockToSectionCoord(payload.around.x),
                SectionPos.blockToSectionCoord(payload.around.z),
            )
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
                ClientLevel.ClientLevelData(net.minecraft.world.Difficulty.PEACEFUL, false, dimensionType.value().hasSkyLight()),
                payload.dimension,
                dimensionType as net.minecraft.core.Holder<DimensionType>,
                RING_RADIUS_FOR_CACHE,
                RING_RADIUS_FOR_CACHE,
                renderer,
                false,
                payload.biomeZoomSeed,
                payload.seaLevel,
            )
            // **A hand-built `LevelRenderer` has no sky renderer until it is told to reload.** The game's own
            // is a resource-reload listener, so vanilla never constructs one without a reload following;
            // ours is made here and would otherwise carry a null `skyRenderer` into `extractLevel` and
            // throw. The same call builds the entity-outline target.
            renderer.onResourceManagerReload(minecraft.resourceManager)
            renderer.setLevel(level)
            level.chunkSource.updateViewCenter(centre.x, centre.z)
            val camera = PanelCamera(level, payload.around)
            return PreviewLevel(level, renderer, renderState, camera, payload.chunksComing)
        }

        /**
         * The chunk cache's radius.
         *
         * One wider than the ring the server sends, because `ClientChunkCache` refuses a chunk outside its
         * range and a cache sized exactly to the ring rejects its own corners on a rounding difference.
         */
        private const val RING_RADIUS_FOR_CACHE = co.voik.agesandtheart.book.panel.PanelProtocol.RING_RADIUS_CHUNKS + 1
    }
}
