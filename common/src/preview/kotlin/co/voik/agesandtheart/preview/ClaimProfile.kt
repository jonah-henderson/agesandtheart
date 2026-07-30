package co.voik.agesandtheart.preview

import co.voik.agesandtheart.worldgen.field.ClaimTilt
import co.voik.agesandtheart.worldgen.field.RegionMap
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import kotlin.math.sqrt

/**
 * Reports what a single territory claim's values look like, and reprints `ClaimTilt`'s table.
 *
 * **A tool, not a check.** Run it when `RegionShareCheck` fails after a change to `RegionMap`'s octave or
 * amplitudes — the shares drift because the distribution moved, and the fix is to paste the printed table
 * into `ClaimTilt`.
 *
 * ```
 * ./gradlew :common:claimprofile
 * ```
 */
fun main() {
    // NormalNoise reaches for the registries by way of nothing at all, but XoroshiroRandomSource is
    // enough of Minecraft to want the bootstrap; it costs a couple of seconds and makes the rest honest.
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()

    val map = RegionMap(members = 1, scale = REGION_BLOCKS, blend = 0, originX = 0, originZ = 0, seed = 1L)
    val samples = mutableListOf<Double>()
    var sampleX = 0
    while (sampleX < GRID_BLOCKS) {
        var sampleZ = 0
        while (sampleZ < GRID_BLOCKS) {
            samples += map.claimAt(member = 0, worldX = sampleX, worldZ = sampleZ)
            sampleZ += STRIDE_BLOCKS
        }
        sampleX += STRIDE_BLOCKS
    }
    samples.sort()

    val mean = samples.average()
    val deviation = sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
    println("Claim distribution over ${samples.size} columns: mean %.4f, sd %.4f".format(mean, deviation))

    // Printed at exactly the probabilities ClaimTilt is keyed on, and in Kotlin, so regenerating the table
    // is a paste rather than an arithmetic exercise. The table is measured data; this is where it comes from.
    //
    // Read the percentiles: the mapping from share to bias needs the top of this distribution resolved,
    // since a rare territory wins precisely where its own claim is unusually loud.
    val measured = ClaimTilt.PROBABILITIES.map { probability ->
        samples[((samples.size - 1) * probability).toInt().coerceIn(samples.indices)]
    }
    println("  ClaimTilt's table, as measured here:")
    println(measured.chunked(TABLE_COLUMNS).joinToString("\n") { row ->
        "        " + row.joinToString(", ") { "%.4f".format(it) } + ","
    })
}

/** A territory one vanilla biome across, which is what an Age actually uses. */
private const val REGION_BLOCKS = 400.0

private const val GRID_BLOCKS = 96_000
private const val STRIDE_BLOCKS = 48

/** Twelve to a line, which is how ClaimTilt's own table is laid out. */
private const val TABLE_COLUMNS = 12
