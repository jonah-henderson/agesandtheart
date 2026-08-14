package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.abs

/**
 * Properties of the two nodes that make a region seam visible — [Fault] and [Rift].
 *
 * **These get a check where most shapes do not**, because they carry a contract with something else rather
 * than a look: the seam a [Rift] cuts along has to be the seam the territories actually meet at, and a
 * [Fault] has to displace rock without inventing or losing any. Both look right in a render while being
 * wrong by a constant factor.
 *
 * It also **prints the width a default rift comes out**, the conversion being proportionate rather than
 * surveyed, and that is the number to tune [Rift.DEFAULT_HALF_WIDTH] against.
 */
@Tags(NEEDS_LANDFORMS)
class FaultCheck : FunSpec({

    /**
     * **The property that retired a defect.** No seam both frays a boundary and displaces it — a
     * displacement through a fray throws the interlocking columns alternately up and down, so the seam
     * comes out as a strip of one-block spikes. Holding here is what makes it unrepresentable rather than
     * merely rare: [Seam.FUZZED] is the only form with a width, and the only one that builds no node.
     */
    test("no seam both blends and displaces") {
        for (seam in Seam.entries) {
            val frays = seam.blendBlocks(REGION_BLOCKS) > 0
            // The form's own answer, not a list rewritten here: a local one had been left saying that a
            // wall does not displace, which is the case this property exists to rule out.
            val displaces = seam.displaces
            check(!(frays && displaces)) {
                "'${seam.key}' both frays its boundary and displaces it, which is the picket fence"
            }
            check(frays == (seam == Seam.FUZZED)) {
                "'${seam.key}' should ${if (seam == Seam.FUZZED) "fray" else "not fray"} and does the opposite"
            }
        }
    }

    /**
     * The forms come out in their declared proportions — **scarp 30 / rift 30 / wall 20 / sheared 15 / fuzzed 5**.
     * The rare one matters most: a bug in a weighted draw hides in a 5% form, since 5 and 0 and 15 all look
     * like "hardly ever" until somebody counts.
     */
    test("the forms are drawn in the proportions asked") {
        val counts = Seam.entries.associateWith { 0 }.toMutableMap()
        for (seed in 1L..SEED_SAMPLES) {
            val drawn = Seam.drawn(XoroshiroRandomSource(seed))
            counts[drawn] = counts.getValue(drawn) + 1
        }

        val expected = mapOf(
            Seam.SCARP to 0.30,
            Seam.RIFT to 0.30,
            Seam.WALL to 0.20,
            Seam.SHEARED to 0.15,
            Seam.FUZZED to 0.05,
        )
        check(expected.keys == Seam.entries.toSet()) {
            "a seam form has no expected share here — add one, or the distribution is only partly checked"
        }
        fun observedShare(seam: Seam) = counts.getValue(seam).toDouble() / SEED_SAMPLES
        for ((seam, share) in expected) {
            check(abs(observedShare(seam) - share) < FREQUENCY_TOLERANCE) {
                "'${seam.key}' came out at ${"%.3f".format(observedShare(seam))} of draws, expected about $share"
            }
        }
    }

    /**
     * A dissolve is never wider than [Seam.WIDEST_FUZZ_BLOCKS], **whatever size the territories are** — a
     * proportional width alone cannot promise that, a fortieth of a Large Biomes territory being 64 blocks.
     * The proportional half is checked at the small end too, since a bare `min` would fix the fray at 16
     * even where a territory is barely wider.
     */
    test("a dissolve is never wider than a person can stand in") {
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
        // The two rules agree exactly at the size an Age written in a default world gets, which is why the
        // number reads as one decision rather than as a fraction quietly overruled.
        check(Seam.FUZZED.blendBlocks(REGION_BLOCKS) == Seam.WIDEST_FUZZ_BLOCKS) {
            "at the default ${REGION_BLOCKS}-block territory the fuzz should be exactly the cap and is " +
                "${Seam.FUZZED.blendBlocks(REGION_BLOCKS)}"
        }
    }

    /**
     * A scarp's throws alternate, so **adjacent territories always stand at different heights**, and which
     * parity rises is the seed's business. Independent signs would agree half the time for two territories,
     * giving a fault that displaced the whole world uniformly and showed nothing.
     */
    test("a scarp always disagrees across a seam") {
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

    /**
     * An Age with one territory must not move, nor one that named no fault. Asserted as **identity of the
     * field tree** rather than equal output: a node shifting by zero would pass on output while still
     * costing a territory lookup per column and changing what the recipe writes.
     */
    test("an unasked fault leaves the shape alone") {
        val map = twoTerritories()
        check(Fault.of(plate, map, listOf(0, 0)) === plate) { "a throw of zero still built a Fault node" }
        check(Rift.opened(plate, map, floorY = 40, rimY = 72, halfWidth = 0.0) === plate) {
            "a rift of no width still built a Rift node"
        }
    }

    /** A one-member map has no seam, so a throw applied to it would displace the world, not fault it. */
    test("a single territory cannot fault") {
        check(Fault.of(plate, wholeWorld, listOf(32)) === plate) { "a one-territory Age was faulted" }
        check(Rift.opened(plate, wholeWorld, floorY = 40, rimY = 72) === plate) { "a one-territory Age was riven" }

        // And the node built directly still claims nothing — the guards above are a convenience, where this is
        // the actual promise, since a recipe could name the node outright.
        val rift = Rift(wholeWorld, halfWidth = Rift.DEFAULT_HALF_WIDTH, floorY = 40, rimY = 72)
        for ((x, z) in walk) {
            check(rift.columnSpans(x, z).ranges.isEmpty()) { "a rift on a whole-world map cut ($x, $z)" }
        }
    }

    /**
     * Every column comes out exactly its own territory's throw away from where it was — the whole of what a
     * scarp claims to do.
     */
    test("a scarp displaces exactly its throw") {
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
     * A fault *displaces* rock; it never creates or destroys any. Span arithmetic that welded two runs
     * together, or dropped one landing on a boundary, shows up here and nowhere else in this file.
     */
    test("a scarp neither adds nor loses rock") {
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
     * The height a structure is placed against moves with the scarp — which follows from the fault being a
     * field, and is the reason it is one: a scarp the terrain knew about and structures did not would put
     * villages in mid-air.
     */
    test("the height contract follows the throw") {
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

    /**
     * **The one that matters.** [RegionMap.blocksFromSeamAt]'s distance and [RegionMap.memberAt]'s boundary
     * must be the same boundary, measured two independent ways. Both directions are asserted, each
     * catching a different failure: too large a conversion and a rift takes almost nothing, too small and
     * it takes a quarter of the world while a render still looks plausible.
     *
     * Tolerances are loose on purpose — the measure is proportionate rather than surveyed, so it runs a few
     * blocks wide of the truth.
     */
    test("the distance measure finds the same boundary the map draws") {
        val map = twoTerritories()
        val width = WALK_RADIUS * 2 + 1
        fun at(x: Int, z: Int) = (z + WALK_RADIUS) * width + (x + WALK_RADIUS)

        val owner = IntArray(width * width)
        for (x in -WALK_RADIUS..WALK_RADIUS) {
            for (z in -WALK_RADIUS..WALK_RADIUS) owner[at(x, z)] = map.memberAt(x, z)
        }

        // A column beside another territory: the drawn boundary, found without asking the map how far away
        // it is.
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

    /**
     * A rift claims a wedge along a seam and nothing else: deepest at the seam, rising to the rim, and
     * never reaching past the band its rim may wander to.
     */
    test("a rift cuts a V along a seam and nothing else") {
        val map = twoTerritories()
        val floorY = 40
        val rimY = 72
        val rift = Rift(map, halfWidth = Rift.DEFAULT_HALF_WIDTH, floorY = floorY, rimY = rimY)
        val reachOfTheRim = Rift.DEFAULT_HALF_WIDTH + Rift.DEFAULT_RIM_WANDER
        var claimed = 0
        var deepest = Int.MAX_VALUE
        for ((x, z) in walk) {
            val ranges = rift.columnSpans(x, z).ranges
            if (map.blocksFromSeamAt(x, z) > reachOfTheRim) {
                check(ranges.isEmpty()) { "($x, $z) is beyond any wander of the rim and the rift claimed $ranges" }
                continue
            }
            if (ranges.isEmpty()) continue
            claimed++
            val floorHere = ranges.single().first
            check(ranges.size == 1 && floorHere in floorY..rimY) {
                "($x, $z) is in the band and the rift claimed $ranges rather than one run between its floor and rim"
            }
            deepest = minOf(deepest, floorHere)
        }
        check(claimed > 0) { "the walk found no rift band at all — widen it before trusting this" }
        check(deepest == floorY) { "the rift never reached its floor: the deepest cut was y=$deepest, not $floorY" }
    }

    /** The wall form: the rift inverted, standing highest at the seam and nowhere outside its band. */
    test("a wall stands along a seam and nowhere else") {
        val map = twoTerritories()
        val footingY = 60
        val crestY = 108
        val ridge = Ridge(map, halfWidth = Ridge.DEFAULT_HALF_WIDTH, footingY = footingY, crestY = crestY)
        val reachOfTheCrest = Ridge.DEFAULT_HALF_WIDTH + Rift.DEFAULT_RIM_WANDER
        var highest = Int.MIN_VALUE
        for ((x, z) in walk) {
            val ranges = ridge.columnSpans(x, z).ranges
            if (map.blocksFromSeamAt(x, z) > reachOfTheCrest) {
                check(ranges.isEmpty()) { "($x, $z) is beyond the wall's band and it claimed $ranges" }
                continue
            }
            if (ranges.isEmpty()) continue
            val run = ranges.single()
            check(ranges.size == 1 && run.first == footingY && run.last <= crestY) {
                "($x, $z) is in the band and the wall claimed $ranges rather than one run up from its footing"
            }
            highest = maxOf(highest, run.last)
        }
        check(highest == crestY) { "the wall never reached its crest: the highest was y=$highest, not $crestY" }
    }

    /**
     * A rift cuts down to its floor and stops, rock below surviving. What keeps `deep` from meaning "delete
     * the territory": a band reaching the bottom of the span arithmetic leaves a hole with no bottom, and
     * every column in it answers the world's floor to the height contract.
     */
    test("a rift leaves everything below its floor") {
        val floorY = 40
        val deepPlate = Slab(lowY = 0, highY = 90)
        val riven = Rift.opened(deepPlate, twoTerritories(), floorY = floorY, rimY = 72)
        check(riven is Subtract) { "a rift should compose as a Subtract and came out ${riven::class.simpleName}" }
        for ((x, z) in walk) {
            val left = riven.columnSpans(x, z)
            for (y in 0..<floorY) {
                check(left.contains(y)) { "($x, $z) lost the block at y=$y, below the rift's floor" }
            }
        }
    }

    /**
     * **A rift stays dry.** The chasm cuts well below the waterline, so without the sea being told to keep
     * out it fills to the brim — which is what happened, twice, and neither the shape checks nor the
     * server checks could see it because both stop at where the rock is.
     *
     * Outside the chasm the same sea must still fill, or this would pass by draining the Age.
     */
    test("the sea keeps out of a rift and fills everywhere else") {
        val map = twoTerritories()
        val waterline = 63
        val chasm = Rift(map, Rift.DEFAULT_HALF_WIDTH, floorY = 40, rimY = 72)
        val sea = SeaFill(listOf(Blocks.WATER.defaultBlockState()), level = waterline, map = RegionMap.whole(), dry = chasm)

        var keptOut = 0
        var filled = 0
        for ((x, z) in walk) {
            val dryness = sea.drynessAt(x, z)
            val insideTheChasm = !dryness.ranges.isEmpty()
            // A level under the waterline and under the rim, so it is a level the sea would reach.
            val y = 50
            val fills = sea.fillsAt(y, dryness, Spans.EMPTY)
            if (insideTheChasm && dryness.contains(y)) {
                check(!fills) { "($x, $z) is inside the chasm at y=$y and the sea filled it" }
                keptOut++
            } else {
                check(fills) { "($x, $z) is outside the chasm at y=$y and the sea did not fill it" }
                filled++
            }
        }
        check(keptOut > 0) { "the walk never entered the chasm, so this checked nothing" }
        check(filled > 0) { "the walk never left the chasm, so the sea was never asked to fill" }
    }

    /**
     * Resizing carries the throw and the band, so a fault in a resized world stays the same fault. Nothing
     * calls this today, neither node being a sensible instancing template — but a throw left unscaled
     * would move a territory relative to itself.
     */
    test("resizing carries the throw and the band") {
        val map = twoTerritories()
        val resizedFault = Fault(plate, map, THROWS).resized(2.0, pivotY = 0)
        check(resizedFault.throws == listOf(48, -80)) {
            "resizing a fault by two gave throws of ${resizedFault.throws}"
        }
        check(resizedFault.map == map.resized(2.0)) { "resizing a fault left its territories the old size" }

        val resizedRift = Rift(map, halfWidth = 16.0, floorY = 40, rimY = 72).resized(2.0, pivotY = 0)
        check(abs(resizedRift.halfWidth - 32.0) < 1.0e-9) {
            "resizing a rift by two gave a half-width of ${resizedRift.halfWidth}"
        }
        check(resizedRift.floorY == 80) { "resizing a rift about y=0 put its floor at ${resizedRift.floorY}" }
        check(resizedRift.rimY == 144) { "resizing a rift about y=0 put its rim at ${resizedRift.rimY}" }
    }
})

/**
 * How wide a default rift actually comes out, measured by walking straight lines across it. Reported
 * rather than asserted: [Rift.DEFAULT_HALF_WIDTH] is a distance in a proportionate measure, so what it
 * means in blocks is something to read off.
 *
 * **Read the median and the share, never the widest.** A line crossing a seam at a glancing angle runs
 * inside the band for as long as the seam stays parallel to it, so the longest crossing measures the
 * *walk's* geometry rather than the band's. The share of columns inside is artefact-free, being an area.
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

private const val PLATE_TOP = 70

// How near a seam a column that touches another territory must be reported as. Generous against a measure
// that is proportionate rather than surveyed; a factor-of-`scale` error would report four hundred.
private const val NEAR_SEAM_BLOCKS = 12.0

// And how far away a column reported as near a seam may look for one. Larger than [NEAR_SEAM_BLOCKS], so
// the two assertions cannot contradict each other over one column.
private const val NEAR_SEAM_REACH = 20

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
