package co.voik.agesandtheart.worldgen

import io.kotest.core.spec.style.FunSpec

/**
 * **Where the deep magma actually is**, which is the one thing a walk cannot answer on its own.
 *
 * A chamber is nine to twenty-two blocks across and five to eleven tall and sits between y −38 and y +14,
 * with no expression on the surface at all. Nothing about that is findable by digging where you happen to
 * stand — so "I could not find one" is not evidence either way, and this file is what turns the question
 * into a number (Jonah, 2026-09-11, walking V3 and then V1).
 *
 * **Every reading here is per amount**, because a quantifier scales the scatter that places them: a written
 * `magma_chamber` is worth twice a bare one and `teeming magma_chamber` eight times. Measured on seed 4242
 * within 800 blocks of the origin — 14 chambers unquantified, 35 written, 119 teeming, which is one per 427,
 * 270 and 146 blocks walked.
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
     * Printed at each rung a book can reach, because **the rungs are the tuning**: a quantifier scales the
     * scatter's cell, so what a writer can ask for is as much a part of "is this findable" as the baseline.
     */
    test("how much ground a chamber is worth at each rung, for reading") {
        println("  seed $SEED, chambers within ${SEARCH} blocks of the origin:")
        for ((said, amount) in RUNGS) {
            val found = chambersNear(SEARCH, amount)
            val ground = (SEARCH * 2.0) * (SEARCH * 2.0)
            val each = if (found.isEmpty()) 0.0 else ground / found.size
            val walked = if (found.isEmpty()) 0 else Math.sqrt(each).toInt()
            println("    ${said.padEnd(22)} ${found.size.toString().padStart(4)} of them, one per $walked blocks walked")
        }
    }

    /**
     * **And that a quantifier reaches them at all**, which it did not until 2026-09-11.
     *
     * `MagmaChambers.askedFor` answered a plain `Boolean`, so the amount was read by the features half and
     * thrown away by the terrain — `teeming magma_chamber` cut exactly as many hollows as `magma_chamber`.
     * This is the assertion that would have caught it.
     */
    test("asking for more chambers gets more chambers") {
        val plain = chambersNear(SEARCH, NAMED).size
        val teeming = chambersNear(SEARCH, TEEMING).size
        check(teeming > plain) {
            "`teeming` gave $teeming chambers against $plain for a plain naming — the amount is not " +
                "reaching the terrain"
        }
        // Area goes as the square of the cell, so the count should track the amount rather than creep.
        val grew = teeming.toDouble() / plain
        check(grew > (TEEMING / NAMED) / 2) {
            "`teeming` is ${TEEMING / NAMED} times the amount of a plain naming and gave only ${grew}x as many"
        }
        println("  a plain naming gives $plain chambers and `teeming` $teeming — ${"%.1f".format(grew)}x")
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
        // The same amount the coordinates below were found at, or the spans are a different field.
        val chambers = MagmaChamberField.chambers(SEED, NAMED)
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
        val chambers = MagmaChamberField.chambers(SEED, NAMED)
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
         * What a book can actually say, and what each is worth. Naming a feature is a rung of its own —
         * `Rung.ORDINARY` is what a hand-composed claim carries and a written one is doubled again.
         */
        private const val NAMED = 2.0
        private const val TEEMING = 8.0

        private val RUNGS = listOf(
            "composed, unquantified" to 1.0,
            "written (`magma_chamber`)" to NAMED,
            "`teeming magma_chamber`" to TEEMING,
        )

        /**
         * Every chamber on the lattice within [reach], as the thickest column of each.
         *
         * **Grouped by what actually touches what, not by the placement's cells.** Grouping by cell is the
         * obvious thing and is wrong in a way that hides: an instance is drawn *within* a cell but its
         * shape is free to reach past the edge, so a chamber near a boundary is counted a second time in
         * the neighbouring cell as a handful of thin edge columns — which inflates the count, and reports a
         * column where the hollow is a couple of blocks tall and its pool, filling from the bottom, does not
         * reach. That read as a dry chamber and was the check's own artefact rather than the world's.
         *
         * Connected components have no such assumption in them: one blob of hollow is one chamber, and two
         * that genuinely grew into each other are honestly one room.
         */
        private fun chambersNear(reach: Int, amount: Double = NAMED): List<Triple<Int, Int, Int>> {
            val chambers = MagmaChamberField.chambers(SEED, amount)
            val hollows = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
            for (x in -reach..reach step STRIDE) {
                for (z in -reach..reach step STRIDE) {
                    val hollow = chambers.cones.columnSpans(x, z)
                    val top = hollow.highestSolidY ?: continue
                    hollows[x to z] = hollow.ranges.first().first to top
                }
            }
            val unvisited = hollows.keys.toMutableSet()
            val found = mutableListOf<Triple<Int, Int, Int>>()
            while (unvisited.isNotEmpty()) {
                val first = unvisited.first()
                val blob = mutableListOf<Pair<Int, Int>>()
                val queue = ArrayDeque(listOf(first))
                unvisited -= first
                while (queue.isNotEmpty()) {
                    val here = queue.removeFirst()
                    blob += here
                    for ((stepX, stepZ) in NEXT_TO) {
                        val beside = (here.first + stepX) to (here.second + stepZ)
                        if (beside in unvisited) {
                            unvisited -= beside
                            queue += beside
                        }
                    }
                }
                // The thickest column of a hollow is its middle, which is where a walk wants to arrive.
                val middle = blob.maxBy { hollows.getValue(it).let { (low, high) -> high - low } }
                val (low, high) = hollows.getValue(middle)
                found += Triple(middle.first, (low + high) / 2, middle.second)
            }
            return found
        }

        private val NEXT_TO = listOf(STRIDE to 0, -STRIDE to 0, 0 to STRIDE, 0 to -STRIDE)

        /** An Age's bedrock — `VerticalWindow.DEFAULT.minY`. */
        private const val FLOOR = -64
    }
}
