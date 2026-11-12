package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.book.panel.PanelMapChunkPayload
import co.voik.agesandtheart.book.panel.PanelRing
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.level.material.MapColor

/** What a panel's picture needs of a map: the part of it in the window, and where the arrival is marked. */
data class MapInView(
    val slot: Int,
    val u0: Float,
    val u1: Float,
    val v0: Float,
    val v1: Float,
    val markX: Int,
    val markY: Int,
)

/**
 * A map panel (`AgeConfig.panelMode` MAP): the ground around where a book would put you, north up, a block to a
 * texel, filled in chunk by chunk as the server reads them and shaded as vanilla's map shades (`MapItem.update`).
 */
class PanelMap(private val arrival: BlockPos, chunksComing: Int, val slot: Int) : PanelSource, AutoCloseable {

    override val openedAtNanos: Long = System.nanoTime()

    override val load = RingLoad(arrival, chunksComing, PanelRing::shownAround)

    private val westEdge = (PanelRing.centreOf(arrival).x - PanelRing.SHOWN_RADIUS_CHUNKS) * CHUNK_WIDTH
    private val northEdge = (PanelRing.centreOf(arrival).z - PanelRing.SHOWN_RADIUS_CHUNKS) * CHUNK_WIDTH

    private val colours = ByteArray(SIDE * SIDE)
    private val heights = ShortArray(SIDE * SIDE)
    private val depths = ByteArray(SIDE * SIDE)
    private val known = BooleanArray(SIDE * SIDE)

    private val texture = DynamicTexture({ "Ages linking panel map" }, SIDE, SIDE, false).also { map ->
        for (x in 0..<SIDE) for (z in 0..<SIDE) map.pixels.setPixel(x, z, UNREAD)
        map.upload()
    }

    val view: GpuTextureView get() = texture.textureView

    /** The window onto the map the panel shows, centred on the arrival and as wide to tall as a panel. */
    val inView: MapInView = run {
        val arrivalX = arrival.x - westEdge + HALF_BLOCK
        val arrivalZ = arrival.z - northEdge + HALF_BLOCK
        val tall = WINDOW_WIDE * PanelPicture.HEIGHT / PanelPicture.WIDTH
        MapInView(
            slot = slot,
            u0 = (arrivalX - WINDOW_WIDE / 2) / SIDE,
            u1 = (arrivalX + WINDOW_WIDE / 2) / SIDE,
            v0 = (arrivalZ - tall / 2) / SIDE,
            v1 = (arrivalZ + tall / 2) / SIDE,
            markX = PanelPicture.WIDTH / 2,
            markY = PanelPicture.HEIGHT / 2,
        )
    }

    /** Takes one chunk's columns, and shades them, and the row south of them that is shaded against them. */
    fun accept(payload: PanelMapChunkPayload) {
        val chunkWest = payload.x * CHUNK_WIDTH - westEdge
        val chunkNorth = payload.z * CHUNK_WIDTH - northEdge
        val isInTheMap = chunkWest in 0..<SIDE && chunkNorth in 0..<SIDE
        if (!isInTheMap) return
        for (localZ in 0..<CHUNK_WIDTH) {
            for (localX in 0..<CHUNK_WIDTH) {
                val column = localX + localZ * CHUNK_WIDTH
                val index = indexOf(chunkWest + localX, chunkNorth + localZ)
                colours[index] = payload.colours[column]
                heights[index] = payload.heights[column]
                depths[index] = payload.depths[column]
                known[index] = true
            }
        }
        val southRow = chunkNorth + CHUNK_WIDTH
        for (z in chunkNorth..minOf(southRow, SIDE - 1)) {
            for (x in chunkWest..<chunkWest + CHUNK_WIDTH) shade(x, z)
        }
        texture.upload()
        load.took(payload.x, payload.z)
    }

    /** A column's colour, lit as vanilla's map lights it: water by its depth, land by the slope from the north. */
    private fun shade(x: Int, z: Int) {
        val index = indexOf(x, z)
        if (!known[index]) return
        val colour = MapColor.byId(colours[index].toInt() and BYTE_MASK)
        val argb = if (colour == MapColor.NONE) UNREAD else colour.calculateARGBColor(brightnessAt(x, z))
        texture.pixels.setPixel(x, z, argb)
    }

    private fun brightnessAt(x: Int, z: Int): MapColor.Brightness {
        val index = indexOf(x, z)
        val checker = (x + z) and 1
        val depth = depths[index].toInt()
        if (depth > 0) {
            val shade = depth * WATER_DEPTH_STEP + checker * WATER_CHECKER
            return when {
                shade < WATER_DARK_BELOW -> MapColor.Brightness.LOW
                shade > WATER_LIGHT_ABOVE -> MapColor.Brightness.HIGH
                else -> MapColor.Brightness.NORMAL
            }
        }
        val north = if (z > 0 && known[indexOf(x, z - 1)]) heights[indexOf(x, z - 1)] else heights[index]
        val slope = (heights[index] - north) + (checker - HALF_BLOCK) * LAND_CHECKER
        return when {
            slope > LAND_LIGHT_ABOVE -> MapColor.Brightness.HIGH
            slope < -LAND_LIGHT_ABOVE -> MapColor.Brightness.LOW
            else -> MapColor.Brightness.NORMAL
        }
    }

    private fun indexOf(x: Int, z: Int) = x + z * SIDE

    override fun close() = texture.close()

    private companion object {
        const val CHUNK_WIDTH = 16
        val SIDE = PanelRing.SHOWN_SIDE * CHUNK_WIDTH

        /**
         * Blocks across the window: a chunk short of the map, so a window centred on an arrival anywhere in the
         * centre chunk stays inside it.
         */
        val WINDOW_WIDE = (SIDE - CHUNK_WIDTH).toFloat()

        const val HALF_BLOCK = 0.5f
        const val BYTE_MASK = 0xFF

        /** The panel's own black, for what has not been read yet and for a column with no colour. */
        const val UNREAD = 0xFF07070C.toInt()

        // Vanilla's map shading, from `MapItem.update` at scale nought.
        const val WATER_DEPTH_STEP = 0.1
        const val WATER_CHECKER = 0.2
        const val WATER_DARK_BELOW = 0.5
        const val WATER_LIGHT_ABOVE = 0.9
        const val LAND_CHECKER = 0.4
        const val LAND_LIGHT_ABOVE = 0.6
    }
}
