package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData

/**
 * Measures the ground a territory actually covers, against the share it was promised — measured rather
 * than derived, because how much ground a *biased* claim wins is a property of the claim noise's
 * distribution, which `NormalNoise` does not publish.
 *
 * **Bound to `ClaimTilt`'s measured table**: that table is honest only while the claim noise is the noise
 * it was measured against, so if `RegionMap`'s octave or amplitudes change these cases drift. **When this
 * fails, run `:common:claimprofile`**, which prints a replacement.
 */
@Tags(NEEDS_REGISTRIES, NEEDS_LANDFORMS)
class RegionShareCheck : FunSpec({

    /**
     * The share vectors worth checking. **The last is the one to watch**: a rare territory beside two
     * dominant ones is the smallest share the system can produce, and the floor under it is what keeps a
     * word a writer wrote from effectively vanishing.
     */
    val shareCases = listOf(
        listOf(1.0, 1.0),
        listOf(4.0, 1.0),
        listOf(16.0, 1.0),
        listOf(64.0, 1.0),
        listOf(4.0, 4.0, 1.0),
        listOf(64.0, 16.0, 4.0),
        listOf(64.0, 64.0, 1.0),
    )

    withData(nameFn = { shares -> "shares ${shares.joinToString("/") { "%.0f".format(it) }} own the ground they were promised" }, shareCases) { shares ->
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
        for (member in shares.indices) {
            val measured = owned[member].toDouble() / columns
            val asked = shares[member] / total
            check(measured >= asked * LEAST_OF_WHAT_WAS_ASKED && measured <= asked * MOST_OF_WHAT_WAS_ASKED) {
                "member $member of ${shares.joinToString("/")} owns %.3f%% of the ground, having been promised " +
                    "%.3f%%. If RegionMap's noise changed, re-run :common:claimprofile and paste ClaimTilt's " +
                    "table afresh.".format(HUNDRED * measured, HUNDRED * asked)
            }
        }
    }
})

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
