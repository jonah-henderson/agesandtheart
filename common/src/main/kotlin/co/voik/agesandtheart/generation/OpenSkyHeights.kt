package co.voik.agesandtheart.generation

import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.SectionPos
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the rock stops in each column, remembered a chunk at a time — **for the spawner, and nothing else**.
 *
 * `AgeChunkGenerator.skyIsOpenAt` asks the Age's own field whether a spawn attempt is out under the sky.
 * That is the right question and the field is the right thing to ask: the heightmap beside it would answer
 * for the *world* rather than the rock, so decoration, a risen sea and a torn wound would all start
 * counting as ground, and vanilla's own care about what a mob may stand on would be answering against a
 * different surface than ours. The answer stays the field's; only the cost changes.
 *
 * **And the cost was the whole tick.** Vanilla asks for a spawn position many times per chunk, per
 * category, every tick, across the whole spawn radius — and the Spire's field is lobed ellipsoids under two
 * noise heightmaps and then a weathering pass. Measured on a live session 2026-09-20: `skyIsOpenAt` was
 * **86% of the server thread**, and Perlin and Simplex noise together 83% of it, which is the Age running
 * at about eight ticks a second.
 *
 * **[ColumnMemo][co.voik.agesandtheart.worldgen.field.ColumnMemo] cannot serve this**, which is why there
 * are two caches and not one. That one is 32×32 slots indexed off the low bits of the coordinates — exactly
 * a chunk and the ring a carver reaches into, which is the shape *generation* asks in. Spawning asks in the
 * opposite shape: a few scattered columns in each of hundreds of chunks, coming back to the same column
 * only many ticks later. Against that, a 32×32 window thrashes and every lookup is a miss.
 *
 * So this is keyed by **chunk**, holds every column of one, and keeps enough chunks for a spawn radius.
 */
internal class OpenSkyHeights(private val field: TerrainField) {

    private val byChunk = ConcurrentHashMap<Long, IntArray>()

    /**
     * The highest solid Y in this column, or **null** where the column has no rock in it at all.
     *
     * Two threads racing on one column both compute the field's own answer and write the same `Int`, so the
     * race is benign and needs no guarding — the map is concurrent for its *structure*, not its contents.
     */
    fun highestSolidYAt(worldX: Int, worldZ: Int): Int? {
        val chunk = SectionPos.asLong(SectionPos.blockToSectionCoord(worldX), 0, SectionPos.blockToSectionCoord(worldZ))
        // Cheaper than an eviction policy and no worse: a spawn radius refills in a few seconds, and the
        // bound only matters at all for somebody travelling far enough to leave one behind.
        if (byChunk.size > MOST_CHUNKS_REMEMBERED) byChunk.clear()
        val columns = byChunk.computeIfAbsent(chunk) { IntArray(COLUMNS_IN_A_CHUNK) { UNASKED } }

        val slot = ((worldZ and WITHIN_A_CHUNK) shl CHUNK_BITS) or (worldX and WITHIN_A_CHUNK)
        val remembered = columns[slot]
        if (remembered != UNASKED) return remembered.takeIf { it != NO_ROCK_AT_ALL }

        val highest = field.columnSpans(worldX, worldZ).highestSolidY
        columns[slot] = highest ?: NO_ROCK_AT_ALL
        return highest
    }

    private companion object {
        const val CHUNK_BITS = 4
        const val WITHIN_A_CHUNK = (1 shl CHUNK_BITS) - 1
        const val COLUMNS_IN_A_CHUNK = 1 shl (CHUNK_BITS * 2)

        /**
         * Two Y values no window reaches, so neither can be mistaken for an answer: a world's floor is
         * -64 and its ceiling far below `Int.MAX_VALUE`.
         */
        const val UNASKED = Int.MIN_VALUE
        const val NO_ROCK_AT_ALL = Int.MIN_VALUE + 1

        /**
         * Chunks kept before the lot is forgotten — comfortably more than a spawn radius, which is what
         * decides the hit rate. At a quarter of a kilobyte a chunk this is about half a megabyte per Age,
         * and only for an Age somebody is standing in.
         */
        const val MOST_CHUNKS_REMEMBERED = 2048
    }
}
