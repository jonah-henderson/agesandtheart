package co.voik.agesandtheart.preview

import co.voik.agesandtheart.worldgen.NoiseField
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise
import kotlin.system.measureNanoTime

/**
 * Times [NormalNoise] — the one thing a 3D-noise primitive would do per *block* rather than per column —
 * so the decision to build one is made on a number instead of an intuition.
 *
 * It runs offline for the same reason the terrain preview does: noise reaches for no registry and no
 * chunk, so measuring it needs no server. What it therefore cannot tell us is the true cost in a running
 * generator (allocation pressure, cache contention with everything else in a chunk worker); the
 * calibration row exists to bound that error, by timing the sampling `hills` already does at a rate we
 * have measured in the game.
 *
 * The question it answers: **how much of the column can a 3D field afford to sample?** A heightmap
 * samples once per column, 256 times a chunk. A 3D field samples per block — up to 98,304 — and the
 * strategies in between are what the projection table prices.
 */
fun main() {
    println("Sampling ${SAMPLE_VOLUME} positions per round, ${TIMED_ROUNDS} timed rounds after ${WARMUP_ROUNDS} warm-up.\n")

    println("NormalNoise.getValue, by octave count")
    println("  octaves    ns/sample")
    val costs = OCTAVE_COUNTS.associateWith { octaves ->
        val noise = NormalNoise.create(XoroshiroRandomSource(NOISE_SEED), FIRST_OCTAVE, *halvingAmplitudes(octaves))
        val perSample = timePerSample { x, y, z -> noise.getValue(x, y, z) }
        println("  %7d %12.1f".format(octaves, perSample))
        perSample
    }

    // The anchor. `hills` runs one three-octave heightmap sample per column and generates at ~83 ms/chunk
    // in the game, so this row says what fraction of that figure noise itself can account for.
    val heightmap = NoiseField.hills()
    val perColumn = timePerSample { x, _, z -> heightmap.columnSpans(x.toInt(), z.toInt()).ranges.size.toDouble() }
    println("\nCalibration — `hills` columnSpans (one 3-octave sample + span construction)")
    println("  %.1f ns/column, so its 256 columns cost %.3f ms/chunk".format(perColumn, perColumn * COLUMNS_PER_CHUNK / 1e6))

    println("\nProjected cost of a 3D field, per chunk")
    println("  strategy                              samples/chunk    ${OCTAVE_COUNTS.joinToString("   ") { "%2do".format(it) }}")
    for ((label, samples) in STRATEGIES) {
        val perStrategy = OCTAVE_COUNTS.joinToString("   ") { "%5.1f".format(costs.getValue(it) * samples / 1e6) }
        println("  %-36s %13d    %s".format(label, samples, perStrategy))
    }
    println("\n  (milliseconds per chunk. Budget is vanilla's ~90 ms/chunk; Jonah's rule pulls back near 2x, ~180.)")
}

/**
 * Nanoseconds per sample, taken as the **best** timed round rather than the mean: the fastest round is
 * the one least disturbed by whatever else the machine was doing, and we want the cost of the work
 * itself. [work]'s result is accumulated and consumed so the JIT cannot delete the loop it is timing.
 */
private fun timePerSample(work: (Double, Double, Double) -> Double): Double {
    var sink = 0.0
    var best = Long.MAX_VALUE
    repeat(WARMUP_ROUNDS + TIMED_ROUNDS) { round ->
        val elapsed = measureNanoTime {
            // A contiguous chunk-shaped volume, so the memory access pattern matches the real caller's.
            for (x in 0..<16) {
                for (z in 0..<16) {
                    for (y in 0..<COLUMN_HEIGHT) {
                        sink += work(x.toDouble(), y.toDouble(), z.toDouble())
                    }
                }
            }
        }
        if (round >= WARMUP_ROUNDS) best = minOf(best, elapsed)
    }
    check(sink.isFinite()) { "noise produced no usable value" }
    return best.toDouble() / SAMPLE_VOLUME
}

/** Vanilla's usual shape: each octave half the wavelength and half the weight of the one before. */
private fun halvingAmplitudes(count: Int): DoubleArray =
    DoubleArray(count) { index -> 1.0 / (1 shl index) }

private const val NOISE_SEED = 0x3D_015EL
private const val FIRST_OCTAVE = -7
private const val COLUMN_HEIGHT = 384
private const val COLUMNS_PER_CHUNK = 16 * 16
private const val SAMPLE_VOLUME = COLUMNS_PER_CHUNK * COLUMN_HEIGHT
private const val WARMUP_ROUNDS = 3
private const val TIMED_ROUNDS = 5

private val OCTAVE_COUNTS = listOf(2, 3, 4, 6)

/**
 * How much of each column a 3D field would actually touch. The last two are what the range-of-interest
 * hint buys: a field asked only about the rock an earlier field already placed samples a fraction of the
 * world's height, and the deeper the terrain the more it saves.
 */
private val STRATEGIES = listOf(
    "naive: every block, full column" to SAMPLE_VOLUME,
    "bounded band (y 40..120)" to COLUMNS_PER_CHUNK * 80,
    "within existing rock (~40 blocks)" to COLUMNS_PER_CHUNK * 40,
    "BelowTerrain depth probes (384/chunk)" to 384 * COLUMN_HEIGHT,
)
