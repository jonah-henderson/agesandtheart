package co.voik.agesandtheart.generation

import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.SectionPos
import net.minecraft.util.Util
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
 *
 * **And it never works a column out on the server thread.** One column of an expensive Age is far from cheap: a
 * hollow underground reads 33 columns of the land round it, and the eroded country's undercuts, gullies and
 * weathering each read their neighbours again. Spawning in country it had not seen, the server thread spent a
 * whole minute of one tick on them and the watchdog shut the server down (2026-11-04, a generator-21 eroded Age
 * under a hollow underground). So the fill tells this each column's top as it lays the chunk ([remember]), which
 * covers everything generated while the server runs; and a chunk it was never told of, generated in an earlier
 * session, is worked out whole on a background thread while the spawner takes a stand-in for the moment.
 */
internal class OpenSkyHeights(private val field: TerrainField) {

    private val byChunk = ConcurrentHashMap<Long, IntArray>()

    /** Chunks being worked out in the background, so each is asked for once. */
    private val underway = ConcurrentHashMap.newKeySet<Long>()

    /**
     * The highest solid Y in this column, or **null** where the column has no rock in it at all — or, where it is
     * not known yet, [meanwhile]'s answer, while the whole chunk is worked out in the background.
     */
    fun highestSolidYAt(worldX: Int, worldZ: Int, meanwhile: () -> Int?): Int? {
        val chunk = chunkKey(worldX, worldZ)
        val columns = columnsOf(chunk)
        val remembered = columns[slotOf(worldX, worldZ)]
        if (remembered != UNASKED) return remembered.takeIf { it != NO_ROCK_AT_ALL }
        if (underway.add(chunk)) {
            workOutInTheBackground(chunk, columns, SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ))
        }
        return meanwhile()
    }

    /**
     * Told by the fill, which has each column's spans in hand already: the field's own answer, so nothing is worked
     * out twice. Two writers write the same `Int`, so the race is benign.
     */
    fun remember(worldX: Int, worldZ: Int, highestSolidY: Int?) {
        columnsOf(chunkKey(worldX, worldZ))[slotOf(worldX, worldZ)] = highestSolidY ?: NO_ROCK_AT_ALL
    }

    private fun workOutInTheBackground(chunk: Long, columns: IntArray, chunkX: Int, chunkZ: Int) {
        BACKGROUND.execute {
            try {
                for (slot in 0..<COLUMNS_IN_A_CHUNK) {
                    if (columns[slot] != UNASKED) continue
                    val worldX = SectionPos.sectionToBlockCoord(chunkX) + (slot and WITHIN_A_CHUNK)
                    val worldZ = SectionPos.sectionToBlockCoord(chunkZ) + (slot shr CHUNK_BITS)
                    columns[slot] = field.columnSpans(worldX, worldZ).highestSolidY ?: NO_ROCK_AT_ALL
                }
            } finally {
                underway.remove(chunk)
            }
        }
    }

    private fun columnsOf(chunk: Long): IntArray {
        // Cheaper than an eviction policy and no worse: a spawn radius refills in a few seconds, and the
        // bound only matters at all for somebody travelling far enough to leave one behind.
        if (byChunk.size > MOST_CHUNKS_REMEMBERED) byChunk.clear()
        return byChunk.computeIfAbsent(chunk) { IntArray(COLUMNS_IN_A_CHUNK) { UNASKED } }
    }

    private fun chunkKey(worldX: Int, worldZ: Int): Long =
        SectionPos.asLong(SectionPos.blockToSectionCoord(worldX), 0, SectionPos.blockToSectionCoord(worldZ))

    private fun slotOf(worldX: Int, worldZ: Int): Int =
        ((worldZ and WITHIN_A_CHUNK) shl CHUNK_BITS) or (worldX and WITHIN_A_CHUNK)

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

        /** Vanilla's own pool for work off the server thread. */
        val BACKGROUND = Util.backgroundExecutor().forName("ageOpenSky")
    }
}
