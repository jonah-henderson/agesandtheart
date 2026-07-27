package co.voik.agesandtheart.preview

import co.voik.agesandtheart.worldgen.field.ClaimTilt
import co.voik.agesandtheart.worldgen.field.RegionMap
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import kotlin.math.sqrt

/**
 * Measures the ground a territory actually covers, against the share it was promised.
 *
 * This is the empirical guarantee behind weighted territories, and it has to be measured rather than
 * derived: territory ownership is the loudest of several noise claims, and how much ground a *biased*
 * claim wins is a property of the claim noise's own distribution — which vanilla's [NormalNoise] does not
 * publish. So the mapping from share to bias is built from a measured distribution, and this check is what
 * says the result lands where it was aimed.
 *
 * It also prints the distribution it depends on, because the mapping is only honest for as long as the
 * claim noise is the noise it was measured against. Change `RegionMap`'s octave or amplitudes and the
 * shares below drift — which is exactly what this fails on.
 */
fun main() {
    // NormalNoise reaches for the registries by way of nothing at all, but XoroshiroRandomSource is
    // enough of Minecraft to want the bootstrap; it costs a couple of seconds and makes the rest honest.
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()

    reportClaimDistribution()
    for (shares in SHARE_CASES) reportShares(shares)
}

/**
 * What a single claim's values actually look like.
 *
 * Read the percentiles: the mapping from share to bias needs the top of this distribution resolved, since
 * a rare territory wins precisely where its own claim is unusually loud.
 */
private fun reportClaimDistribution() {
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
    val measured = ClaimTilt.PROBABILITIES.map { probability ->
        samples[((samples.size - 1) * probability).toInt().coerceIn(samples.indices)]
    }
    println("  ClaimTilt's table, as measured here:")
    println(measured.chunked(TABLE_COLUMNS).joinToString("\n") { row ->
        "        " + row.joinToString(", ") { "%.4f".format(it) } + ","
    })
}

/** How much ground each member of a weighted map actually owns. */
private fun reportShares(shares: List<Double>) {
    val map = RegionMap(
        members = shares.size,
        scale = REGION_BLOCKS,
        blend = 0,
        originX = 0,
        originZ = 0,
        seed = 1L,
        shares = shares,
    )
    val owned = IntArray(shares.size)
    var columns = 0
    var sampleX = 0
    while (sampleX < GRID_BLOCKS) {
        var sampleZ = 0
        while (sampleZ < GRID_BLOCKS) {
            owned[map.memberAt(sampleX, sampleZ)]++
            columns++
            sampleZ += STRIDE_BLOCKS
        }
        sampleX += STRIDE_BLOCKS
    }

    val total = shares.sum()
    val report = shares.indices.joinToString("  ") { member ->
        "%.2f%% (asked %.2f%%)".format(
            HUNDRED * owned[member] / columns,
            HUNDRED * shares[member] / total,
        )
    }
    println("Shares ${shares.joinToString("/") { "%.0f".format(it) }}: $report")

    for (member in shares.indices) {
        val measured = owned[member].toDouble() / columns
        val asked = shares[member] / total
        check(measured >= asked * LEAST_OF_WHAT_WAS_ASKED && measured <= asked * MOST_OF_WHAT_WAS_ASKED) {
            "member $member of ${shares.joinToString("/")} owns %.3f%% of the ground, having been promised %.3f%%"
                .format(HUNDRED * measured, HUNDRED * asked)
        }
    }
}

/**
 * The share vectors worth checking: the named ladder against itself, and the extremes that matter.
 *
 * The last case is the one to watch — a rare territory in an Age with two dominant ones is the smallest
 * share the system can produce, and the floor under it is what keeps a word a writer wrote from
 * effectively vanishing.
 */
private val SHARE_CASES = listOf(
    listOf(1.0, 1.0),
    listOf(4.0, 1.0),
    listOf(16.0, 1.0),
    listOf(64.0, 1.0),
    listOf(4.0, 4.0, 1.0),
    listOf(64.0, 16.0, 4.0),
    listOf(64.0, 64.0, 1.0),
)

/** A territory one vanilla biome across, which is what an Age actually uses. */
private const val REGION_BLOCKS = 400.0

// Wide enough that the rarest share is measured over thousands of its own columns rather than a handful,
// and strided so the sample is not dominated by neighbouring columns of the same territory.
private const val GRID_BLOCKS = 96_000
private const val STRIDE_BLOCKS = 48

// Generous by design: "scattered" does not have to be exactly a fifth, and the noise is what it is. What
// the bounds catch is a mapping that is wrong by a factor rather than off by a fifth.
private const val LEAST_OF_WHAT_WAS_ASKED = 0.6
private const val MOST_OF_WHAT_WAS_ASKED = 1.6

private const val HUNDRED = 100.0

/** Twelve to a line, which is how ClaimTilt's own table is laid out. */
private const val TABLE_COLUMNS = 12
