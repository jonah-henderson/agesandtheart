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
    private val buffers: RenderBuffers,
    /** Ours rather than the game's: two renderers sharing one would collide over `levelRenderState`. */
    val renderState: GameRenderState,
    val centre: ChunkPos,
    private val chunksExpected: Int,
) : AutoCloseable {

    private var chunksArrived = 0

    /** Whether every chunk of the ring has arrived, which is what ends the fade from black. */
    val isWhole: Boolean get() = chunksArrived >= chunksExpected

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
     * Drops the renderer, and with it the section builders the buffers handed out.
     *
     * `RenderBuffers` has nothing to close of its own — its pool is owned by the renderer that was given
     * it — so closing the renderer is the whole of the teardown, and the buffers are held only to keep them
     * alive for exactly as long as it is.
     */
    override fun close() {
        renderer.close()
    }

    companion object {

        /**
         * How many section builders the preview's own [RenderBuffers] gets.
         *
         * **Small on purpose.** The game's own pool is sized for a render distance; a panel meshes a ring
         * of forty-nine chunks once and never grows, so this is the difference between the "large standing
         * allocation" `link-panel-research.md` feared and something a screen can afford to hold.
         */
        private const val SECTION_BUILDERS = 2

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
            val buffers = RenderBuffers(SECTION_BUILDERS)
            val renderer = LevelRenderer(
                minecraft,
                minecraft.entityRenderDispatcher,
                minecraft.blockEntityRenderDispatcher,
                buffers,
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
            renderer.setLevel(level)
            level.chunkSource.updateViewCenter(centre.x, centre.z)
            return PreviewLevel(level, renderer, buffers, renderState, centre, payload.chunksComing)
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
