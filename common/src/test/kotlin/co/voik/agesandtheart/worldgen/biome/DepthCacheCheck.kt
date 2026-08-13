package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.field.TerrainField
import io.kotest.core.annotation.Isolate
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.QuartPos
import kotlin.system.measureNanoTime

/**
 * That [BelowTerrain] answers a chunk's depth queries for about the cost of its distinct columns, rather
 * than recomputing each per level.
 *
 * **The bug it guards against is invisible**: indexed on the low bits of the *block* coordinate the cache
 * did nothing at all — those bits are always zero, biomes being sampled per quart cell — and the terrain
 * was identical either way. It showed up only as a benchmark that refused to move.
 *
 * The measurement is a **ratio** rather than a time, so it means the same on any machine. **[Isolate] is
 * load-bearing**: a ratio between two timings taken while fifteen other specs compete for the CPU is not a
 * measurement of anything.
 */
@Isolate
class DepthCacheCheck : FunSpec({

    test("a chunk's depth queries cost about what its distinct columns do") {
        val terrain = CavernField.world()
        val depth = BelowTerrain(terrain)

        // Warm both paths together so neither is measured cold against the other.
        repeat(WARMUP_ROUNDS) { round -> queryChunk(depth, round); readColumns(terrain, round) }

        // **Every round is a different chunk, and that matters.** Repeating one chunk would leave its
        // columns cached from the round before, so the queries would measure pure hits and report a saving
        // no real generation could see. A fresh chunk each round pays what generation actually pays: each
        // of its columns computed once, then answered from the cache for the other ninety-five levels.
        val queried = bestOfFreshChunks { round -> queryChunk(depth, round) }
        val columns = bestOfFreshChunks { round -> readColumns(terrain, round) }
        val ratio = queried.toDouble() / columns

        println("A chunk asks $QUERIES_PER_CHUNK depth queries about $COLUMNS_PER_CHUNK distinct columns.")
        println("  redundancy if uncached : ${QUERIES_PER_CHUNK / COLUMNS_PER_CHUNK}x")
        println("  cost of the queries    : ${queried / 1000} us")
        println("  cost of the columns    : ${columns / 1000} us")
        println("  ratio                  : %.2fx".format(ratio))

        check(ratio < ACCEPTABLE_RATIO) {
            "depth queries cost %.1fx their distinct columns — the column cache is not being hit".format(ratio)
        }
    }
})

/** One chunk's depth queries, in vanilla's own order — x, then y, then z, section by section. */
private fun queryChunk(depth: BelowTerrain, chunk: Int) {
    val originQuartX = chunk * QUART_SPAN
    for (section in 0..<SECTIONS) {
        for (quartX in 0..<QUART_SPAN) {
            for (quartY in 0..<QUART_SPAN) {
                for (quartZ in 0..<QUART_SPAN) {
                    // `BelowTerrain` measures against its own rock and ignores what vanilla sampled, so
                    // the value handed in here is immaterial to what this check is timing.
                    depth.at(
                        QuartPos.toBlock(originQuartX + quartX),
                        QuartPos.toBlock(section * QUART_SPAN + quartY),
                        QuartPos.toBlock(quartZ),
                        UNSAMPLED,
                    )
                }
            }
        }
    }
}

/** The irreducible work: every distinct column the queries above touch, once each. */
private fun readColumns(terrain: TerrainField, chunk: Int) {
    val originQuartX = chunk * QUART_SPAN
    for (quartX in 0..<QUART_SPAN) {
        for (quartZ in 0..<QUART_SPAN) {
            terrain.columnSpans(QuartPos.toBlock(originQuartX + quartX), QuartPos.toBlock(quartZ))
        }
    }
}

private fun bestOfFreshChunks(work: (Int) -> Unit): Long {
    var best = Long.MAX_VALUE
    for (round in 0..<TIMED_ROUNDS) {
        // Offset past the warm-up's chunks, so no round reuses a column an earlier one cached.
        best = minOf(best, measureNanoTime { work(WARMUP_ROUNDS + round) })
    }
    return best
}

private const val QUART_SPAN = 4
private const val SECTIONS = 24
private const val COLUMNS_PER_CHUNK = QUART_SPAN * QUART_SPAN
private const val QUERIES_PER_CHUNK = SECTIONS * QUART_SPAN * QUART_SPAN * QUART_SPAN
private const val WARMUP_ROUNDS = 200
private const val TIMED_ROUNDS = 200

// Generous: the queries do real work of their own (roofOver, the arithmetic, the cache lookup itself),
// so parity is not expected — anything near the uncached redundancy is what this is looking for.
private const val ACCEPTABLE_RATIO = 8.0

/** What vanilla's own depth would have said, which `BelowTerrain` never reads. */
private const val UNSAMPLED = 0.0f
