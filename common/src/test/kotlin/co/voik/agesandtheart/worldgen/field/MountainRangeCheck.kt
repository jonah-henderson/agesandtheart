package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.worldgen.AlpsField
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Properties of a range built from its own drainage — the things a render cannot settle.
 *
 * The pictures answer whether it looks like mountains (`./gradlew :common:preview --args=alps`, and read
 * the cross-section before the plan). What they cannot answer is whether the surface is **continuous**,
 * which matters more here than for any other field in the toolkit: the ground is the lower envelope of
 * hillslopes rising from reaches drawn out of a *local* scan, so a reach that leaves the scan while it is
 * still the lowest thing over a column drops the ground by however much it was winning by. That shows up
 * as straight-edged facets in a render and is easy to mistake for the landform's own geometry, which is
 * genuinely made of straight-edged facets. A number tells them apart; an eye does not.
 */
@Tags(NEEDS_LANDFORMS)
class MountainRangeCheck : FunSpec({

    val range = AlpsField.bareWorld()
    val water = AlpsField.water() as MountainRange

    /**
     * How deep water may stand over its own floor, **derived from the range rather than chosen** so it
     * cannot drift from what the construction admits. Three things stack: a trunk's own depth; a **cirque**,
     * which is scooped below its outlet and sits at the head of a channel, so the water confined to that
     * channel stands in the bowl; and the room the gullying leaves, since a gully cuts to clear whatever it
     * drains to and that need not be the channel supplying the water.
     */
    val deepestRiver = range.glaciation.cirqueDeepening +
        range.waterDepth * (1.0 + MountainRange.MOST_TRIBUTARIES * range.incisionPerOrder) +
        MountainRange.DEEPEST_RIVER

    /** How wide a trunk's channel is, rim to rim — the bound the water can never spread past. */
    val widestChannel =
        2.0 * range.channelHalfWidth * (1.0 + MountainRange.MOST_TRIBUTARIES * range.channelPerOrder)

    fun topAt(field: TerrainField, worldX: Int, worldZ: Int) = field.columnSpans(worldX, worldZ).highestSolidY

    fun groundAt(worldX: Int, worldZ: Int) = topAt(range, worldX, worldZ) ?: error("no ground at $worldX, $worldZ")

    /**
     * **The one that separates a crease from a step.** Neighbouring columns of any landform here may differ
     * by a lot — a cliff is allowed — but this landform has no cliffs in it by construction: every surface is
     * a hillslope at [MountainRange.hillslopeGrade], a trough floor, a headwall or a summit cap, and the
     * steepest of those is the headwall. Anything past that came from the *scan* rather than from the shape.
     *
     * The bound is generous on purpose, since the gullying cuts at its own scale on top of all that. What it
     * is really looking for is the tens-of-blocks drop that a clipped reach leaves.
     */
    test("the ground never steps between neighbouring columns") {
        // **A row at a time, and the rows in parallel.** The scan along X has to stay sequential — each
        // column is compared with the one before it — but rows share nothing, and this spec's last test is
        // the standing proof that the field answers the same from any thread. Three million columns on one
        // core made this spec alone longer than the other forty-three together.
        fun worstStepAlong(worldZ: Int): Pair<Int, Pair<Int, Int>> {
            var worstHere = 0
            var worstHereAt = 0 to worldZ
            var previous = groundAt(-1500, worldZ)
            for (worldX in -1499..1500) {
                val here = groundAt(worldX, worldZ)
                val step = abs(here - previous)
                if (step > worstHere) {
                    worstHere = step
                    worstHereAt = worldX to worldZ
                }
                previous = here
            }
            return worstHere to worstHereAt
        }

        val perRow = (-1500..1500 step 7).toList().parallelStream().map(::worstStepAlong).toList()
        val (worst, worstAt) = perRow.maxByOrNull { it.first } ?: (0 to (0 to 0))
        check(worst <= BIGGEST_HONEST_STEP) {
            "the ground stepped $worst blocks between two neighbouring columns at $worstAt, " +
                "which is more than the steepest thing in this landform can account for"
        }
    }

    /**
     * **Nothing is hollow underneath.** A field's floor is where its rock *starts*, and setting it to the
     * lowest a valley should reach — rather than to the bottom of the world — leaves every column empty
     * below that: a cavity under the whole Age, and no bedrock either, since the palette's bedrock gradient
     * paints rock and there is none there to paint.
     *
     * Invisible from above and from every cross-section that starts at the ground, which is why it survived
     * a walk. One assertion at one height catches it.
     */
    test("the rock reaches the bottom of the world") {
        for (worldZ in -1200..1200 step 37) {
            for (worldX in -1200..1200 step 41) {
                val rock = range.columnSpans(worldX, worldZ)
                check(rock.contains(AlpsField.WORLD_FLOOR)) {
                    "the column at ($worldX, $worldZ) is empty at the world floor, so the Age is hollow under it"
                }
            }
        }
    }

    /**
     * **The country is ranges *and* basins, and the basins are gently rolling rather than merely lower.**
     *
     * The ranges lie along a network with broad cells between them, so what has to be true is a shape of the
     * *distribution*: a real share of the ground is low, a real share is high, and the low ground is smooth
     * where the high ground is not. Every way this can fail leaves one of those three false — a threshold
     * that fills the cells in leaves nothing low, a relief scale that does not ramp leaves mountains
     * marching across the basins, and a network that never resolves leaves one flat sheet.
     *
     * Deliberately no position in it. The last version of this test read heights at fixed offsets from a
     * single wedge's axis, and the moment the ranges became a network it was measuring wherever it happened
     * to land.
     */
    test("basins take up real ground, and roll gently where the ranges do not") {
        val step = 53
        // **Wider than one cell of the network, and that is a requirement rather than a margin.** A window
        // inside a single cell holds either a basin or a range and passes or fails on where it happened to
        // land; the distribution this asserts only exists over several of them.
        val reach = 6000
        fun roughnessAt(worldX: Int, worldZ: Int) = maxOf(
            abs(groundAt(worldX + step, worldZ) - groundAt(worldX, worldZ)),
            abs(groundAt(worldX, worldZ + step) - groundAt(worldX, worldZ)),
        )

        val sampled = (-reach..reach step step).flatMap { worldZ ->
            (-reach..reach step step).map { worldX -> groundAt(worldX, worldZ) to roughnessAt(worldX, worldZ) }
        }
        val heights = sampled.map { it.first }.sorted()
        fun at(share: Double) = heights[(heights.size * share).toInt().coerceAtMost(heights.size - 1)]

        check(at(HIGH_GROUND) - at(LOW_GROUND) > A_RANGE_WORTH_OF_CLIMB) {
            "the ninetieth percentile stood at y=${at(HIGH_GROUND)} against a tenth at y=${at(LOW_GROUND)}, " +
                "so there is no range against its basins"
        }
        // **Against a share of the country's own span, not against its plain level.** Comparing the median
        // to a constant meant to describe where the basins sit is circular — the two are estimates of the
        // same quantity, so the test can only ever be marginal. What is worth asserting is that most of the
        // ground lies in the *lower part* of the range from basin floor to crest.
        val mostlyLow = AlpsField.BASIN_FLOOR + (AlpsField.CREST_Y - AlpsField.BASIN_FLOOR) * LOW_SHARE
        check(at(THE_MIDDLE) < mostlyLow) {
            "half the ground stands over y=${at(THE_MIDDLE)} against a basin-to-crest quarter at $mostlyLow, " +
                "so the cells have filled in and the basins are gone"
        }

        val basins = sampled.filter { it.first <= at(LOW_GROUND) }.map { it.second }
        val ranges = sampled.filter { it.first >= at(HIGH_GROUND) }.map { it.second }
        check(ranges.average() > basins.average() * ROUGHER_BY) {
            "the ranges fall ${ranges.average()} blocks per stride against the basins' ${basins.average()}, " +
                "so the relief is not ramping and the basins are mountains too"
        }
    }

    /**
     * **The hierarchy is what makes it a landscape rather than a corrugation.** Reaches that have gathered
     * tributaries cut deeper and carry wider valleys, so the deepest valleys must be far deeper than a
     * headwater's own incision — and the shallow end has to survive too, or every reach has become a trunk.
     */
    test("some valleys are cut far deeper than a headwater cuts") {
        val floors = (-1200..1200 step 17).flatMap { worldZ ->
            (-1200..1200 step 17).map { worldX -> groundAt(worldX, worldZ) }
        }
        val deepest = floors.min()
        val highest = floors.max()
        check(highest - deepest > MOUNTAIN_ENOUGH) {
            "the whole core spanned only ${highest - deepest} blocks, which is a hillside and not a range"
        }
    }

    /**
     * **Water cannot stand above its own banks**, however the beds and the hillslopes were derived.
     *
     * Two assertions, because either alone would be useless. The hard bound is what the construction
     * *guarantees*, and it is deliberately loose — every digging term stacking at one column is allowed for.
     * What would actually go wrong is systemic: a cap read off the wrong surface leaves water lying up the
     * hillsides everywhere, which stays well inside the hard bound and is plain in the second reading.
     */
    test("no water stands deeper than the hollow it is in") {
        var wetted = 0
        var deep = 0
        for (worldZ in -900..900 step 13) {
            for (worldX in -900..900 step 13) {
                val surface = topAt(water, worldX, worldZ) ?: continue
                val land = groundAt(worldX, worldZ)
                check(surface <= land + deepestRiver) {
                    "water stood at y=$surface over ground at y=$land at ($worldX, $worldZ)"
                }
                wetted++
                if (surface > land + A_RIVER_YOU_CAN_WADE) deep++
            }
        }
        check(wetted > 0) { "nothing anywhere was wet, so this checked nothing" }
        check(deep * PER_CENT < wetted * A_FEW_PER_CENT) {
            "$deep of $wetted wetted columns stood over $A_RIVER_YOU_CAN_WADE blocks deep, which is a " +
                "landscape under water rather than rivers and tarns in it"
        }
    }

    /**
     * **The water has to sit in a trench, not lie on a floor** — the one property Minecraft's own physics
     * cares about, and the one a picture cannot show at all.
     *
     * A glacial trough's floor is wide and nearly flat, which is what makes it a trough. Fill it to the
     * water's depth and the result is a sheet a hundred blocks across with no bank to hold it: water spreads
     * off the edges, and where two flows meet the game turns flowing water back into source blocks and
     * builds mounds of it downstream. Nothing about that is visible in a heightmap.
     *
     * So the assertion is on how wide the wet ground is — **and the width has to be the lesser of the two
     * axes, not the run along one of them.** A river running east lies unbroken for hundreds of blocks
     * along X, and measuring that calls every river a flood. What separates the two is that a river is
     * narrow *one* way and a sheet is wide *both*.
     */
    test("water runs in channels rather than lying across the valley floors") {
        val reach = 1200
        val step = 4
        val across = (reach * 2) / step + 1
        fun at(index: Int) = index * step - reach

        val wet = Array(across) { alongX ->
            BooleanArray(across) { alongZ ->
                val worldX = at(alongX)
                val worldZ = at(alongZ)
                val surface = topAt(water, worldX, worldZ)
                surface != null && surface > groundAt(worldX, worldZ)
            }
        }

        /** How far the wet ground at this sample runs unbroken, counting both ways along one axis. */
        fun runThrough(alongX: Int, alongZ: Int, stepX: Int, stepZ: Int): Int {
            var spanned = 1
            for (direction in listOf(1, -1)) {
                var x = alongX + stepX * direction
                var z = alongZ + stepZ * direction
                while (x in 0..<across && z in 0..<across && wet[x][z]) {
                    spanned++
                    x += stepX * direction
                    z += stepZ * direction
                }
            }
            return spanned * step
        }

        var widest = 0
        var widestAt = 0 to 0
        var wetted = 0
        for (alongX in 0..<across) {
            for (alongZ in 0..<across) {
                if (!wet[alongX][alongZ]) continue
                wetted++
                val narrowest = minOf(runThrough(alongX, alongZ, 1, 0), runThrough(alongX, alongZ, 0, 1))
                if (narrowest > widest) {
                    widest = narrowest
                    widestAt = at(alongX) to at(alongZ)
                }
            }
        }
        check(wetted > 0) { "nothing anywhere was wet, so this checked nothing" }
        // A run measured on the axes crosses a channel lying diagonally at an angle, so it reads wider than
        // the channel is. Even with that margin this sits far under the several hundred blocks a flooded
        // trough floor gives, which is the failure it exists to catch.
        val widestReading = (widestChannel * A_DIAGONAL).toInt()
        check(widest <= widestReading) {
            "water lay $widest blocks across at $widestAt against a channel of $widestReading, which is a " +
                "sheet over a valley floor rather than a river in a channel"
        }
    }

    /**
     * **A field is a pure function, and this one holds a whole neighbourhood while it works.** Everything it
     * derives lives in locals for exactly this reason; the same trap [Drainage] records, and the same check,
     * because the preview asks from one thread and the chunk workers ask from several.
     */
    test("the answer does not depend on the thread that asked") {
        val columns = (-600..600 step 17).flatMap { worldZ -> (-600..600 step 19).map { it to worldZ } }
        val alone = columns.associateWith { (worldX, worldZ) -> groundAt(worldX, worldZ) }
        val together = ConcurrentHashMap<Pair<Int, Int>, Int>()
        columns.parallelStream().forEach { (worldX, worldZ) -> together[worldX to worldZ] = groundAt(worldX, worldZ) }
        val disagreed = columns.filter { alone[it] != together[it] }
        check(disagreed.isEmpty()) { "${disagreed.size} columns answered differently in parallel, first ${disagreed.first()}" }
    }
}) {
    private companion object {
        /**
         * The steepest honest one-block change. A headwall is the steepest surface here — a hillslope's grade
         * times [AlpsField]'s headwall steepening, which is under one and a half blocks per block — the warp
         * roughly doubles every gradient by moving the sampled point faster than the world, and the gullying
         * cuts at its own scale on top of both. **Measured at three**, so six is a bound with room rather
         * than a number tuned to pass.
         *
         * It earned its place immediately: the glacial trough used to switch on at a hard threshold that a
         * continuously-growing quantity crossed *along* a reach, which ruled a twelve-block wall across the
         * valley. The render showed it as one more straight-edged facet among the many this landform
         * genuinely has, which is exactly why an eye could not have caught it.
         */
        const val BIGGEST_HONEST_STEP = 6

        /**
         * How far up the basin-to-crest span the median must stay under. A quarter is comfortably clear of
         * where a country of ranges-and-basins lands and nowhere near where one of slopes would.
         */
        const val LOW_SHARE = 0.25

        /** Where the basins and the ranges are read off the height distribution, and its middle. */
        const val LOW_GROUND = 0.10
        const val THE_MIDDLE = 0.50
        const val HIGH_GROUND = 0.90

        /** How far the ranges have to stand over the basins before it is worth calling them ranges. */
        const val A_RANGE_WORTH_OF_CLIMB = 120

        /** And how much more steeply a range falls than a basin does, stride for stride. */
        const val ROUGHER_BY = 3

        /** How much the core has to span, floor to crest, to be mountains rather than hills. */
        const val MOUNTAIN_ENOUGH = 120

        /**
         * What a run measured on the axes at worst reads, against one channel's own width: a channel lying
         * diagonally is crossed at an angle, and a **confluence** is several of them overlapping at once.
         * Still far under the several hundred blocks a flooded trough floor gives.
         */
        const val A_DIAGONAL = 4.5

        /** Past this a body of water is a lake or a tarn rather than a river, and those should be rare. */
        const val A_RIVER_YOU_CAN_WADE = 12

        /** How rare. Measured well under one in a hundred; five leaves room without letting a flood pass. */
        const val A_FEW_PER_CENT = 5
        const val PER_CENT = 100

        /**
         * How deep the deepest water may stand over its own floor. Two things reach for it: a trunk river,
         * at `waterDepth * (1 + tributaries * incisionPerOrder)` — under ten here — and a **tarn**, which
         * stands a cirque's overdeepening deeper again because the bowl was scooped below its own outlet.
         * Twenty covers both with room, and is nowhere near a sheet of water standing up a hillside.
         */
        const val THE_DEEPEST_WATER = 20
    }
}
