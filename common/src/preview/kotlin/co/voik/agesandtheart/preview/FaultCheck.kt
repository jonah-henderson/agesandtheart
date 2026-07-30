package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.abs

/**
 * Properties of the two nodes that make a region seam visible — [Fault] and [Rift].
 *
 * **Why these get a check when most shapes do not.** A shape's output is inspectable: you render it and
 * look, which `:common:preview` is for. What these two carry that a picture cannot settle is a *contract
 * with something else* — the seam a [Rift] cuts along has to be the seam the territories actually meet at,
 * and a [Fault] has to displace rock without inventing or losing any. Both are the kind of claim that looks
 * right in a render while being wrong by a constant factor.
 *
 * The one most worth having is [theDistanceMeasureFindsTheSameBoundaryTheMapDraws]. [RegionMap] measures
 * how far a column stands from a seam in *claim* units and converts to blocks by the same proportionate
 * arithmetic `blend` rides on — so a rift asked for sixteen blocks either side could quietly take a quarter
 * of the world, or nothing at all, and a render of one Age would not tell you which. Two independent routes
 * to the same boundary is what settles it: the distance the map reports, against the columns where the
 * winning member actually changes.
 *
 * It also **prints the width a default rift comes out**, because the conversion is proportionate rather
 * than surveyed and that number is the one to tune [Rift.DEFAULT_HALF_WIDTH] against.
 *
 * `CodecCheck` covers the round-trip, for the reason it covers `Choose` and `Chance` — nothing else in the
 * build writes these nodes yet.
 */
fun main() {
    val checks = listOf(
        ::noSeamBothBlendsAndDisplaces,
        ::theFormsAreDrawnInTheProportionsAsked,
        ::aDissolveIsNeverWiderThanAPersonCanStandIn,
        ::aScarpAlwaysDisagreesAcrossASeam,
        ::anUnaskedFaultLeavesTheShapeAlone,
        ::aSingleTerritoryCannotFault,
        ::aScarpDisplacesExactlyItsThrow,
        ::aScarpNeitherAddsNorLosesRock,
        ::theHeightContractFollowsTheThrow,
        ::theDistanceMeasureFindsTheSameBoundaryTheMapDraws,
        ::aRiftTakesOnlyTheBandAlongASeam,
        ::aRiftLeavesEverythingBelowItsFloor,
        ::resizingCarriesTheThrowAndTheBand,
    )
    for (check in checks) check()
    println("Fault/Rift: all ${checks.size} properties hold.")
}

/**
 * **The property that retired a defect.** No seam both frays a boundary and displaces it.
 *
 * A blended boundary interlocks two shapes column by column; a displacement through that fray throws the
 * interlocking columns alternately up and down, so the seam comes out as a strip of one-block spikes as tall
 * as the throw. That was real, it was found by looking at a render, and no assertion caught it.
 *
 * Jonah's fix was to stop layering a fault over an independently-drawn transition *width* and make the
 * softening one of the fault's own **forms** — so the combination is not rare, it is unrepresentable. This is
 * that claim written down, and it is the reason the fix is a fix rather than a mitigation: [Seam.FUZZED] is
 * the only form with a width, and it is the only one that builds no node.
 */
private fun noSeamBothBlendsAndDisplaces() {
    for (seam in Seam.entries) {
        val frays = seam.blendBlocks(REGION_BLOCKS) > 0
        val displaces = seam == Seam.SCARP || seam == Seam.RIFT
        check(!(frays && displaces)) {
            "'${seam.key}' both frays its boundary and displaces it, which is the picket fence"
        }
        check(frays == (seam == Seam.FUZZED)) {
            "'${seam.key}' should ${if (seam == Seam.FUZZED) "fray" else "not fray"} and does the opposite"
        }
    }
}

/**
 * The forms come out in the proportions Jonah asked for — **rift 40 / scarp 40 / sheared 15 / fuzzed 5**.
 *
 * Asserted as a frequency because that is what a weight means. The rare one matters most: a 5% form is
 * precisely what a bug in a weighted draw hides in, since 5 and 0 and 15 all look like "hardly ever" until
 * somebody counts.
 */
private fun theFormsAreDrawnInTheProportionsAsked() {
    val counts = Seam.entries.associateWith { 0 }.toMutableMap()
    for (seed in 1L..SEED_SAMPLES) {
        val drawn = Seam.drawn(XoroshiroRandomSource(seed))
        counts[drawn] = counts.getValue(drawn) + 1
    }

    val expected = mapOf(Seam.SCARP to 0.40, Seam.RIFT to 0.40, Seam.SHEARED to 0.15, Seam.FUZZED to 0.05)
    check(expected.keys == Seam.entries.toSet()) {
        "a seam form has no expected share here — add one, or the distribution is only partly checked"
    }
    fun observedShare(seam: Seam) = counts.getValue(seam).toDouble() / SEED_SAMPLES
    for ((seam, share) in expected) {
        check(abs(observedShare(seam) - share) < FREQUENCY_TOLERANCE) {
            "'${seam.key}' came out at ${"%.3f".format(observedShare(seam))} of draws, expected about $share"
        }
    }
    println(
        "  seam forms over $SEED_SAMPLES seeds: " +
            expected.keys.joinToString(" ") { "${it.key} ${"%.3f".format(observedShare(it))}" }
    )
}

/**
 * A dissolve is never wider than [Seam.WIDEST_FUZZ_BLOCKS], **whatever size the territories are**.
 *
 * Jonah's constraint, and it is about a person rather than a territory: *"the wide fuzziness can be
 * absolutely overwhelming to the point of incomprehensibility in game, which isn't fun. No more than 16
 * blocks of transition."* A proportional width alone cannot promise that — at Large Biomes a fortieth of a
 * territory is 64 blocks — so the cap is what actually holds the promise, and the cap is what this checks.
 *
 * The proportional half is checked too, at the small end, because a bare `min` would have been the wrong fix:
 * it would make the fray a *fixed* 16 blocks even where a territory is barely wider than that.
 */
private fun aDissolveIsNeverWiderThanAPersonCanStandIn() {
    // Default, Large Biomes (x4), and a datapack that shrank them — see `BiomeScale`.
    for (regionBlocks in listOf(100, 200, 400, 800, 1600, 6400)) {
        for (seam in Seam.entries) {
            val width = seam.blendBlocks(regionBlocks)
            check(width <= Seam.WIDEST_FUZZ_BLOCKS) {
                "'${seam.key}' frays $width blocks over ${regionBlocks}-block territories, past the cap of " +
                    "${Seam.WIDEST_FUZZ_BLOCKS} a person can still see across"
            }
        }
        val fuzz = Seam.FUZZED.blendBlocks(regionBlocks)
        val proportional = (regionBlocks * Seam.FUZZED.share).toInt()
        check(fuzz == minOf(proportional, Seam.WIDEST_FUZZ_BLOCKS)) {
            "over ${regionBlocks}-block territories the fuzz came out $fuzz, which is neither its share " +
                "($proportional) nor the cap (${Seam.WIDEST_FUZZ_BLOCKS})"
        }
    }
    // The two rules agree exactly at the size an Age written in a default world gets, which is why the number
    // reads as one decision rather than as a fraction quietly overruled.
    check(Seam.FUZZED.blendBlocks(REGION_BLOCKS) == Seam.WIDEST_FUZZ_BLOCKS) {
        "at the default ${REGION_BLOCKS}-block territory the fuzz should be exactly the cap and is " +
            "${Seam.FUZZED.blendBlocks(REGION_BLOCKS)}"
    }
}

/**
 * A scarp's throws alternate, so **adjacent territories always stand at different heights** — and which
 * parity rises is the seed's business, never the order a sentence named its terrains in.
 *
 * The first half is what stops a scarp silently not being one: independent signs per territory would agree
 * half the time for two territories, giving a fault that displaced the whole world uniformly and showed
 * nothing. The second is the resolver's own promise (*word order decides nothing*) applied to geology.
 */
private fun aScarpAlwaysDisagreesAcrossASeam() {
    var evenRose = 0
    for (seed in 1L..SEED_SAMPLES) {
        val throws = Fault.alternatingThrows(members = 2, throwBlocks = 32, seed = seed)
        check(throws.toSet() == setOf(32, -32)) { "seed $seed threw $throws rather than one of each way" }
        if (throws.first() > 0) evenRose++
    }
    val share = evenRose.toDouble() / SEED_SAMPLES
    check(abs(share - HALF) < FREQUENCY_TOLERANCE) {
        "the first territory rose ${"%.3f".format(share)} of the time, so the seed is not deciding the parity"
    }

    for (members in 2..5) {
        val throws = Fault.alternatingThrows(members, throwBlocks = 32, seed = 7L)
        for (member in 1..<members) {
            check(throws[member] != throws[member - 1]) {
                "territories ${member - 1} and $member were thrown the same way in $throws"
            }
        }
    }
}

private const val SEED_SAMPLES = 20_000L

// At 20k draws the sampling noise is well inside this, even for the 5% form.
private const val FREQUENCY_TOLERANCE = 0.01

private const val HALF = 0.5

// A territory about the width of one vanilla biome, which is what an Age written in a default world gets.
private const val REGION_BLOCKS = 400

// Wide enough to hold several territories at [REGION_BLOCKS], so a walk crosses real seams.
private const val WALK_RADIUS = 500

// One column per block over that radius would be a million evaluations for no extra confidence.
private const val WALK_STEP = 5

/** Two territories, knife-edged so the winner is the pure argmax and nothing is left to the dither. */
private fun twoTerritories(seed: Long = 0x4E6109L) = RegionMap(
    members = 2,
    scale = REGION_BLOCKS.toDouble(),
    blend = 0,
    originX = 0,
    originZ = 0,
    seed = seed,
)

/** One member owning everything — what an Age naming a single terrain gets. */
private val wholeWorld = RegionMap.whole()

/** One thrown up and one dropped, unequally, so a mistake cannot pass by symmetry. */
private val THROWS = listOf(24, -40)

/** A flat plate, so a faulted column's spans say exactly where the rock was put. */
private val plate: TerrainField = Slab(lowY = 60, highY = 70)

/** Rock that varies column to column, for the properties that must hold of a real landscape too. */
private val landscape: TerrainField = Regions(
    members = listOf(NoiseField.hills(), PillarField.world()),
    map = twoTerritories(),
)

private val walk: List<Pair<Int, Int>> = (-WALK_RADIUS..WALK_RADIUS step WALK_STEP).toList()
    .let { coordinates -> coordinates.flatMap { x -> coordinates.map { z -> x to z } } }

/**
 * The property Phase 4.5 step 9 is accepted against: *must not move — any Age with one territory*, and by
 * extension any Age that named no fault at all.
 *
 * Asserted as **identity of the field tree** rather than as equal output, deliberately. Equal output would
 * pass with a node that shifts by zero, and that node still costs a territory lookup per column and still
 * changes what a recipe writes out. The promise is that a fault nobody asked for is not there.
 */
private fun anUnaskedFaultLeavesTheShapeAlone() {
    val map = twoTerritories()
    check(Fault.of(plate, map, listOf(0, 0)) === plate) { "a throw of zero still built a Fault node" }
    check(Rift.opened(plate, map, floorY = 40, halfWidth = 0.0) === plate) {
        "a rift of no width still built a Rift node"
    }
}

/** A one-member map has no seam, so a throw applied to it would displace the world, not fault it. */
private fun aSingleTerritoryCannotFault() {
    check(Fault.of(plate, wholeWorld, listOf(32)) === plate) { "a one-territory Age was faulted" }
    check(Rift.opened(plate, wholeWorld, floorY = 40) === plate) { "a one-territory Age was riven" }

    // And the node built directly still claims nothing — the guards above are a convenience, where this is
    // the actual promise, since a recipe could name the node outright.
    val rift = Rift(wholeWorld, halfWidth = Rift.DEFAULT_HALF_WIDTH, floorY = 40)
    for ((x, z) in walk) {
        check(rift.columnSpans(x, z).ranges.isEmpty()) { "a rift on a whole-world map cut ($x, $z)" }
    }
}

/**
 * Every column comes out exactly its own territory's throw away from where it was — the whole of what a
 * scarp claims to do.
 */
private fun aScarpDisplacesExactlyItsThrow() {
    val map = twoTerritories()
    val faulted = Fault.of(landscape, map, THROWS)
    for ((x, z) in walk) {
        val thrown = THROWS[map.memberAt(x, z)]
        val expected = landscape.columnSpans(x, z).ranges.map { it.first + thrown..it.last + thrown }
        check(faulted.columnSpans(x, z).ranges == expected) {
            "($x, $z) is in territory ${map.memberAt(x, z)} and should have moved $thrown, but came out " +
                "${faulted.columnSpans(x, z).ranges} against an expected $expected"
        }
    }
}

/**
 * A fault *displaces* rock; it never creates or destroys any.
 *
 * Worth asserting separately from the exactness above because it is the property a reader would check by
 * eye and could not: span arithmetic that welded two runs together, or dropped one that landed on a
 * boundary, would show up here and nowhere else in this file.
 */
private fun aScarpNeitherAddsNorLosesRock() {
    val faulted = Fault.of(landscape, twoTerritories(), THROWS)
    for ((x, z) in walk) {
        val before = landscape.columnSpans(x, z)
        val after = faulted.columnSpans(x, z)
        check(after.ranges.size == before.ranges.size) {
            "($x, $z) had ${before.ranges.size} runs of rock and came out with ${after.ranges.size}"
        }
        val thicknessBefore = before.ranges.sumOf { it.last - it.first + 1 }
        val thicknessAfter = after.ranges.sumOf { it.last - it.first + 1 }
        check(thicknessAfter == thicknessBefore) {
            "($x, $z) held $thicknessBefore blocks of rock and came out holding $thicknessAfter"
        }
    }
}

/**
 * The height a structure is placed against moves with the scarp.
 *
 * `AgeChunkGenerator.getBaseHeight` reads `columnSpans().highestSolidY`, so this follows from the fault
 * being a field at all — which is the point. It is asserted anyway because it is the reason the fault is a
 * *shape* rather than a pass over one: a scarp the terrain knew about and structures did not would put
 * villages in mid-air, and nothing else here would notice.
 */
private fun theHeightContractFollowsTheThrow() {
    val map = twoTerritories()
    val faulted = Fault.of(plate, map, THROWS)
    for ((x, z) in walk) {
        val thrown = THROWS[map.memberAt(x, z)]
        val standing = faulted.columnSpans(x, z).highestSolidY
        check(standing == PLATE_TOP + thrown) {
            "($x, $z) should stand at ${PLATE_TOP + thrown} and reports $standing"
        }
    }
}

private const val PLATE_TOP = 70

/**
 * **The one that matters.** The distance [RegionMap.blocksFromSeamAt] reports and the boundary
 * [RegionMap.memberAt] draws must be the same boundary — measured two independent ways.
 *
 * Both directions are asserted, because each catches a different way of being wrong:
 * - every column whose neighbour belongs to another territory reports a *small* distance, which fails if
 *   the conversion out of claim units comes out too large (a rift would then take almost nothing);
 * - every column reporting a small distance really is beside such a column, which fails if it comes out
 *   too small (a rift would take a quarter of the world, and a render of one Age would look plausible).
 *
 * The tolerances are loose on purpose. The measure is proportionate rather than surveyed — it reads a
 * claim's margin at the local rate of the tilt table, not of the noise — so it runs a few blocks wide of
 * the truth. What is checked is that it is right within a few blocks, never to the block. The **printed**
 * band width is what says how wide a rift actually comes out.
 */
private fun theDistanceMeasureFindsTheSameBoundaryTheMapDraws() {
    val map = twoTerritories()
    val width = WALK_RADIUS * 2 + 1
    fun at(x: Int, z: Int) = (z + WALK_RADIUS) * width + (x + WALK_RADIUS)

    val owner = IntArray(width * width)
    for (x in -WALK_RADIUS..WALK_RADIUS) {
        for (z in -WALK_RADIUS..WALK_RADIUS) owner[at(x, z)] = map.memberAt(x, z)
    }

    // A column beside another territory: the drawn boundary, found without asking the map how far away it is.
    val onABoundary = BooleanArray(width * width)
    val inset = (-WALK_RADIUS + NEAR_SEAM_REACH)..(WALK_RADIUS - NEAR_SEAM_REACH)
    for (x in inset) {
        for (z in inset) {
            val mine = owner[at(x, z)]
            onABoundary[at(x, z)] = owner[at(x - 1, z)] != mine || owner[at(x + 1, z)] != mine ||
                owner[at(x, z - 1)] != mine || owner[at(x, z + 1)] != mine
        }
    }
    check(onABoundary.any { it }) { "the walk crossed no seam at all — widen it before trusting this" }

    for (x in inset) {
        for (z in inset) {
            if (!onABoundary[at(x, z)]) continue
            val reported = map.blocksFromSeamAt(x, z)
            check(reported <= NEAR_SEAM_BLOCKS) {
                "($x, $z) sits against another territory and the map calls it " +
                    "${"%.1f".format(reported)} blocks from a seam"
            }
        }
    }

    fun aBoundaryLiesWithinReachOf(x: Int, z: Int): Boolean =
        (-NEAR_SEAM_REACH..NEAR_SEAM_REACH).any { offsetX ->
            (-NEAR_SEAM_REACH..NEAR_SEAM_REACH).any { offsetZ -> onABoundary[at(x + offsetX, z + offsetZ)] }
        }

    val reachInset = (inset.first + NEAR_SEAM_REACH)..(inset.last - NEAR_SEAM_REACH)
    for (x in reachInset) {
        for (z in reachInset) {
            if (map.blocksFromSeamAt(x, z) > NEAR_SEAM_BLOCKS) continue
            check(aBoundaryLiesWithinReachOf(x, z)) {
                "the map calls ($x, $z) within $NEAR_SEAM_BLOCKS blocks of a seam, and the nearest column " +
                    "belonging to another territory is over $NEAR_SEAM_REACH blocks away"
            }
        }
    }

    reportTheBandWidth(map)
}

// How near a seam a column that touches another territory must be reported as. Generous against a measure
// that is proportionate rather than surveyed; a factor-of-`scale` error would report four hundred.
private const val NEAR_SEAM_BLOCKS = 12.0

// And how far away a column reported as near a seam may look for one. Larger than [NEAR_SEAM_BLOCKS], so
// the two assertions cannot contradict each other over one column.
private const val NEAR_SEAM_REACH = 20

/**
 * How wide a default rift actually comes out, measured by walking straight lines across it.
 *
 * Reported rather than asserted, and it is the reason this file is worth running by hand as well as in a
 * build: [Rift.DEFAULT_HALF_WIDTH] is a distance in a proportionate measure, so what it means in blocks is
 * something to read off rather than to reason about. `:common:preview --args=rift` is the picture; this is
 * the number. **Measured 2026-07-29: a half-width of 16 comes out a median of 31 blocks across**, so the
 * conversion is very nearly exact and the constant can be read as the distance it says it is.
 *
 * **Read the median and the share, never the widest.** A straight line crossing a seam at a glancing angle
 * runs along inside the band for as far as the seam stays parallel to it, so the longest crossing measures
 * the *walk's* geometry rather than the band's — 280 blocks against a median of 31, on the same data. The
 * share of columns inside the band is the artefact-free number, being an area rather than a chord.
 */
private fun reportTheBandWidth(map: RegionMap) {
    val widths = mutableListOf<Int>()
    for (z in -WALK_RADIUS..WALK_RADIUS step WALK_STEP) {
        var run = 0
        for (x in -WALK_RADIUS..WALK_RADIUS) {
            if (map.blocksFromSeamAt(x, z) <= Rift.DEFAULT_HALF_WIDTH) {
                run++
            } else {
                if (run > 0) widths += run
                run = 0
            }
        }
        if (run > 0) widths += run
    }
    check(widths.isNotEmpty()) { "no rift band was crossed at all — widen the walk before trusting this" }

    val sorted = widths.sorted()
    val inTheBand = walk.count { (x, z) -> map.blocksFromSeamAt(x, z) <= Rift.DEFAULT_HALF_WIDTH }
    println(
        "  a rift of half-width ${Rift.DEFAULT_HALF_WIDTH} over ${REGION_BLOCKS}-block territories: " +
            "median ${sorted[sorted.size / 2]} blocks across (${sorted.size} crossings), " +
            "${"%.1f".format(inTheBand * 100.0 / walk.size)}% of the world inside it"
    )
}

/**
 * A rift claims the band along a seam and nothing else, and claims it from its floor upwards.
 *
 * Asserted against the map's own distance rather than against a second notion of "near a seam", because a
 * second notion of it is what this file exists to make unnecessary.
 */
private fun aRiftTakesOnlyTheBandAlongASeam() {
    val map = twoTerritories()
    val floorY = 40
    val rift = Rift(map, halfWidth = Rift.DEFAULT_HALF_WIDTH, floorY = floorY)
    var claimed = 0
    for ((x, z) in walk) {
        val ranges = rift.columnSpans(x, z).ranges
        if (map.blocksFromSeamAt(x, z) > Rift.DEFAULT_HALF_WIDTH) {
            check(ranges.isEmpty()) { "($x, $z) is outside the band and the rift claimed $ranges" }
            continue
        }
        claimed++
        check(ranges.size == 1 && ranges.single().first == floorY) {
            "($x, $z) is in the band and the rift claimed $ranges rather than one run from its floor"
        }
    }
    check(claimed > 0) { "the walk found no rift band at all — widen it before trusting this" }
}

/**
 * A rift cuts down to its floor and stops. Rock below survives, which is what makes the floor a floor.
 *
 * This is the property that keeps `deep` from meaning "delete the territory": a band reaching the bottom of
 * the span arithmetic would leave a hole with no bottom, and every column in it would answer the world's
 * floor to the height contract.
 */
private fun aRiftLeavesEverythingBelowItsFloor() {
    val floorY = 40
    val deepPlate = Slab(lowY = 0, highY = 90)
    val riven = Rift.opened(deepPlate, twoTerritories(), floorY = floorY)
    check(riven is Subtract) { "a rift should compose as a Subtract and came out ${riven::class.simpleName}" }
    for ((x, z) in walk) {
        val left = riven.columnSpans(x, z)
        for (y in 0..<floorY) {
            check(left.contains(y)) { "($x, $z) lost the block at y=$y, below the rift's floor" }
        }
    }
}

/**
 * Resizing carries the throw and the band, so a fault in a resized world stays the same fault.
 *
 * Neither node is a sensible instancing template — a seam runs right across an Age — so nothing calls this
 * today. It is asserted because a throw left unscaled would move a territory relative to itself, exactly
 * the drift `Raised` documents having to avoid, and because the day something does resize a world is not
 * the day to find that out.
 */
private fun resizingCarriesTheThrowAndTheBand() {
    val map = twoTerritories()
    val resizedFault = Fault(plate, map, THROWS).resized(2.0, pivotY = 0)
    check(resizedFault.throws == listOf(48, -80)) {
        "resizing a fault by two gave throws of ${resizedFault.throws}"
    }
    check(resizedFault.map == map.resized(2.0)) { "resizing a fault left its territories the old size" }

    val resizedRift = Rift(map, halfWidth = 16.0, floorY = 40).resized(2.0, pivotY = 0)
    check(abs(resizedRift.halfWidth - 32.0) < 1.0e-9) {
        "resizing a rift by two gave a half-width of ${resizedRift.halfWidth}"
    }
    check(resizedRift.floorY == 80) { "resizing a rift about y=0 put its floor at ${resizedRift.floorY}" }
}
