package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.book.BookBeingRead
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor

/**
 * A chunk read as a map panel's columns, as vanilla's map reads one (`MapItem.update`): the topmost block that
 * has a map colour, and how deep the liquid over the ground is.
 *
 * Under a ceiling the top is the ceiling, which would map every Nether-like Age as bedrock, so there the
 * reading starts just above the arrival instead and finds the first floor beneath open air.
 */
object PanelMapColumns {

    fun of(level: ServerLevel, chunk: LevelChunk, arrival: BlockPos, book: BookBeingRead, ask: Int): PanelMapChunkPayload {
        val colours = ByteArray(PanelMapChunkPayload.COLUMNS)
        val heights = ShortArray(PanelMapChunkPayload.COLUMNS)
        val depths = ByteArray(PanelMapChunkPayload.COLUMNS)
        val column = BlockPos.MutableBlockPos()
        val chunkX = chunk.pos.minBlockX
        val chunkZ = chunk.pos.minBlockZ
        for (localZ in 0..<CHUNK_WIDTH) {
            for (localX in 0..<CHUNK_WIDTH) {
                val index = localX + localZ * CHUNK_WIDTH
                column.set(chunkX + localX, topOf(level, chunk, localX, localZ, arrival), chunkZ + localZ)
                colours[index] = colourUnder(level, chunk, column).id.toByte()
                heights[index] = column.y.toShort()
                depths[index] = liquidDepthBelow(level, chunk, column).toByte()
            }
        }
        return PanelMapChunkPayload(book, ask, chunk.pos.x, chunk.pos.z, colours, heights, depths)
    }

    /** Where a column's reading starts: the surface, or under a ceiling the first floor below the arrival's air. */
    private fun topOf(level: ServerLevel, chunk: LevelChunk, localX: Int, localZ: Int, arrival: BlockPos): Int {
        if (!level.dimensionType().hasCeiling()) return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ)
        val position = BlockPos.MutableBlockPos(chunk.pos.minBlockX + localX, arrival.y + CEILING_HEADROOM, chunk.pos.minBlockZ + localZ)
        val floor = level.minY
        while (position.y > floor && !chunk.getBlockState(position).isAir) position.move(0, -1, 0)
        while (position.y > floor && chunk.getBlockState(position).isAir) position.move(0, -1, 0)
        return position.y
    }

    /** Down from [column] to the first block with a colour, leaving [column] there. */
    private fun colourUnder(level: ServerLevel, chunk: LevelChunk, column: BlockPos.MutableBlockPos): MapColor {
        var state: BlockState = chunk.getBlockState(column)
        while (column.y > level.minY && state.getMapColor(level, column) == MapColor.NONE) {
            column.move(0, -1, 0)
            state = chunk.getBlockState(column)
        }
        return state.getMapColor(level, column)
    }

    /** How many blocks of liquid stand from [top] down, nought on dry ground, at most [DEEPEST] as a map shades it. */
    private fun liquidDepthBelow(level: ServerLevel, chunk: LevelChunk, top: BlockPos): Int {
        val position = top.mutable()
        var depth = 0
        while (depth < DEEPEST && position.y > level.minY && !chunk.getFluidState(position).isEmpty) {
            depth++
            position.move(0, -1, 0)
        }
        return depth
    }

    private const val CHUNK_WIDTH = 16

    /** Deep enough that every shade of water a map has is reached. */
    private const val DEEPEST = 16

    /** How far above the arrival a reading under a ceiling starts, so a cave's roof over it is not taken for ground. */
    private const val CEILING_HEADROOM = 8
}
