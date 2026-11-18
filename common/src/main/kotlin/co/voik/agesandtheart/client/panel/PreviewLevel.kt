package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.book.panel.PanelRing
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.core.BlockPos

/**
 * A linking panel's world: a [PreviewWorld] standing only while a book is open, holding the ring of chunks the panel
 * draws, with the orbit it is seen from, the shots it cuts between and how far the ring has come.
 */
class PreviewLevel private constructor(
    val world: PreviewWorld,
    val camera: PanelCamera,
    val shots: PanelShots,
    /** How far the Age is at odds with itself as the panel shows it, `0..1` (design §7.3). */
    val unsettled: Float,
    private val around: BlockPos,
    override val load: RingLoad,
) : PanelSource, AutoCloseable {

    val level: ClientLevel get() = world.level
    val renderer: LevelRenderer get() = world.renderer
    val extractor: LevelExtractor get() = world.extractor
    val renderState: LevelRenderState get() = world.renderState

    private var rebuiltOnce = false

    override val openedAtNanos: Long = System.nanoTime()

    fun accept(payload: PanelChunkPayload) {
        if (world.accept(payload.x, payload.z, payload.chunk, payload.light)) load.took(payload.x, payload.z)
    }

    /**
     * Light once a frame, and the whole ring rebuilt once it has all come, for light a chunk's own arrival could not
     * finish. Independent of whether there was light work this frame: `markNewInconsistencies` lowers its flag before
     * re-queuing what it could not store, so by the time the ring is whole `hasLightWork` is already false and a
     * rebuild gated on it would never run at all.
     */
    fun advanceLight() {
        world.runTheLight()
        if (rebuiltOnce || load.wholeness < 1.0f) return
        rebuiltOnce = true
        world.rebuildAround(PanelRing.centreOf(around), PanelRing.RADIUS_CHUNKS)
    }

    override fun close() = world.close()

    companion object {

        /**
         * Null when the dimension type the server named is not in the client's registries. [large] is a crystal
         * viewer's panel, drawn several times the size of a book's and rendered finer to match.
         */
        fun open(payload: PanelLevelPayload, large: Boolean): PreviewLevel? {
            val description = PreviewWorld.Description(
                dimension = payload.dimension,
                dimensionType = payload.dimensionType,
                biomeZoomSeed = payload.biomeZoomSeed,
                seaLevel = payload.seaLevel,
                gameTime = payload.gameTime,
                rain = payload.rain,
                thunder = payload.thunder,
                centre = PanelRing.centreOf(payload.around),
                heldRadiusChunks = PanelRing.HELD_RADIUS_CHUNKS,
                width = PanelTarget.SHARPEST_WIDTH,
                height = PanelTarget.SHARPEST_HEIGHT,
            )
            val world = PreviewWorld.standUp(description) ?: return null
            val unsettled = PanelDistortion.unsettledAt(payload.instability)
            // A note to self rather than GPU work: the fields are made lazily, inside the render.
            PanelTarget.showing(unsettled, large)
            return PreviewLevel(
                world = world,
                camera = PanelCamera(world.level, payload.around),
                shots = PanelShots(payload.biomeZoomSeed, unsettled),
                unsettled = unsettled,
                around = payload.around,
                load = RingLoad(payload.around, payload.chunksComing),
            )
        }
    }
}
