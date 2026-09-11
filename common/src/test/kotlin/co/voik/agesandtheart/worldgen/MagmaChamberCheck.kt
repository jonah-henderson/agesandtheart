package co.voik.agesandtheart.worldgen

import io.kotest.core.spec.style.FunSpec

/**
 * **Where the deep magma actually is**, which is the one thing a walk cannot answer on its own.
 *
 * A chamber is nine to twenty-two blocks across and five to eleven tall, sits between y −50 and y +14, and
 * turns up in something under half of 260-block cells. Nothing about that is findable by digging where you
 * happen to stand — so "I could not find one" is not evidence either way, and this file is what turns the
 * question into a number (Jonah, 2026-09-11, walking V3).
 *
 * The reading is the point rather than the assertions. Two of these print coordinates a walk can go and
 * stand on; the rest are floors low enough that only a chamber field producing *nothing* trips them.
 */
class MagmaChamberCheck : FunSpec({

    /** That the field makes any at all — the cheapest possible answer to "are they missing?". */
    test("an Age of magma chambers has some") {
        val found = chambersNear(SEARCH)
        check(found.isNotEmpty()) {
            "no magma chamber anywhere within ${SEARCH * 2}x${SEARCH * 2} blocks of the origin on seed $SEED"
        }
        println("  seed $SEED: ${found.size} chambers on the lattice within ${SEARCH} blocks of the origin")
    }

    /**
     * **Somewhere to go and stand**, printed rather than asserted — the answer a walk actually needs.
     *
     * Reported as the hollow's own middle, so `/tp` lands inside it rather than in the rock beside it.
     */
    test("the nearest few chambers to the origin, for reading") {
        val nearest = chambersNear(SEARCH)
            .sortedBy { (x, _, z) -> x.toLong() * x + z.toLong() * z }
            .take(SOME)
        check(nearest.isNotEmpty()) { "nothing to report, which the check above should have caught" }
        println("  seed $SEED, the nearest magma chambers — dig or /tp to these:")
        for ((x, y, z) in nearest) {
            val away = Math.hypot(x.toDouble(), z.toDouble()).toInt()
            println("    x=$x y=$y z=$z  (${away} blocks out)")
        }
    }

    /**
     * How far apart they really are, which is what decides whether V3 is a fair thing to ask of a walk.
     *
     * The cell is 260 blocks and under half of them hold one, so the honest expectation is one chamber per
     * 150,000 square blocks — about one per four hundred blocks of walking in a straight line.
     */
    test("how much ground a chamber is worth, for reading") {
        val found = chambersNear(SEARCH)
        val ground = (SEARCH * 2.0) * (SEARCH * 2.0)
        val each = if (found.isEmpty()) 0.0 else ground / found.size
        println("  one chamber per ${each.toInt()} square blocks, which is one per ${Math.sqrt(each).toInt()} blocks walked")
    }

    /**
     * **How many of them fall through the floor of the world**, which is the thing a walk would read as
     * "there are no chambers" and never as a bug.
     *
     * An Age's bedrock is at y −64 and the deepest chamber template is authored at −50. [Variation] scales
     * a copy about **y=0**, not about its own middle — it has one pivot for all six templates and cannot
     * have a per-template one — so growing a deep chamber also drags it down: −50 at the largest pose lands
     * at −65, below the floor entirely. What survives is the top half of a hollow sitting on bedrock, and
     * its pool is authored from the *bottom*, so what is lost is the lava.
     */
    test("a chamber that sank through the world floor keeps neither its room nor its lava") {
        val chambers = VolcanoField.chambers(SEED)
        var dry = 0
        var sunk = 0
        val found = chambersNear(SEARCH)
        for ((x, _, z) in found) {
            val hollow = chambers.cones.columnSpans(x, z)
            val pool = chambers.lakes.columnSpans(x, z)
            val roof = hollow.highestSolidY ?: continue
            if (hollow.ranges.first().first < FLOOR) sunk++
            // A pool whose whole body is under the bedrock is a chamber that comes out dry.
            val surface = pool.highestSolidY
            if (roof > FLOOR && (surface == null || surface < FLOOR)) dry++
        }
        println("  of ${found.size} chambers: $sunk reach below the world floor at y=$FLOOR, and $dry come out dry")
        check(dry == 0) {
            "$dry of ${found.size} magma chambers on seed $SEED hold no lava above the world floor — " +
                "a chamber authored at y=-50 is dragged to y=-65 at its largest pose, because Variation " +
                "scales about y=0 rather than about the chamber's own depth"
        }
    }

    /**
     * **And that a chamber holds lava with headroom over it**, which is what makes it worth reaching.
     *
     * Read at the hollow's own axis: the pool is level on top and fills between a third and seven tenths of
     * the height, so a chamber whose lava reached its ceiling would be a solid lump of lava rather than a
     * room.
     */
    test("a chamber holds a pool with room above it") {
        val chambers = VolcanoField.chambers(SEED)
        val (x, _, z) = chambersNear(SEARCH).first()
        val hollow = chambers.cones.columnSpans(x, z)
        val pool = chambers.lakes.columnSpans(x, z)
        val roof = hollow.highestSolidY
        val surface = pool.highestSolidY
        check(roof != null) { "the chamber reported at ($x, $z) has no hollow over its own axis" }
        check(surface != null) { "the chamber at ($x, $z) is dry" }
        check(surface!! < roof!!) { "the lava at ($x, $z) stands at $surface against a roof at $roof" }
        println("  the chamber at ($x, $z): lava to y=$surface under a roof at y=$roof")
    }
}) {
    private companion object {

        /** The seed every volcanic item on the walk list is written on. */
        private const val SEED = 4242L

        /**
         * Stepped at half the smallest chamber's width, so the scan cannot step over one — nine blocks
         * across at its smallest pose, and a coarser lattice would under-report and read as absence.
         */
        private const val STRIDE = 4

        /** Far enough to cross several 260-block cells in every direction. */
        private const val SEARCH = 800

        private const val SOME = 8

        /**
         * Every chamber on the lattice within [reach], as the middle of each hollow found.
         *
         * Deduplicated by clumping: one chamber is many lattice columns, so the columns are grouped by
         * which 260-block cell they fall in — the same cell the placement draws them in, so one cell holds
         * at most one and the grouping cannot merge two.
         */
        private fun chambersNear(reach: Int): List<Triple<Int, Int, Int>> {
            val chambers = VolcanoField.chambers(SEED)
            // Per cell: the thickest column seen, and where it was.
            val byCell = HashMap<Pair<Int, Int>, Pair<Int, Triple<Int, Int, Int>>>()
            for (x in -reach..reach step STRIDE) {
                for (z in -reach..reach step STRIDE) {
                    val hollow = chambers.cones.columnSpans(x, z)
                    val top = hollow.highestSolidY ?: continue
                    val deepest = hollow.ranges.first().first
                    val thickness = top - deepest
                    val cell = Math.floorDiv(x, CELL) to Math.floorDiv(z, CELL)
                    // The thickest column of a hollow is its middle, which is where a walk wants to arrive.
                    val standing = byCell[cell]
                    if (standing == null || thickness > standing.first) {
                        byCell[cell] = thickness to Triple(x, (top + deepest) / 2, z)
                    }
                }
            }
            return byCell.values.map { (_, where) -> where }
        }

        /** `VolcanoField.CHAMBER_CELL`, which is private there and only needed here for the grouping. */
        private const val CELL = 260

        /** An Age's bedrock — `VerticalWindow.DEFAULT.minY`. */
        private const val FLOOR = -64
    }
}
