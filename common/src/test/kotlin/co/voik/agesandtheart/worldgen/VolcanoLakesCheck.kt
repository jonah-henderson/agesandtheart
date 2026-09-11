package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Spans
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * **The lava a caldera arrives full of** — the one thing about a volcano that a picture of its heights
 * cannot show, because a lake and the rock around it are the same colour to a heightmap.
 *
 * Two of these are guarantees rather than measurements, and both are the kind that fail silently in the
 * game: a lake whose surface is not level reads as broken rock, and a lake standing higher than the rim
 * beside it **drains through that one column** and takes the whole caldera with it.
 */
class VolcanoLakesCheck : FunSpec({

    /**
     * **A lip may weep; a wall may not fail.**
     *
     * A block of lava with open air beside it is where a crater spills, and a few of those are wanted —
     * the rim is floored two blocks under its own lava exactly so the roll notches it somewhere and a
     * lavafall runs down the flank (Jonah: "would be cinematic"). What is not wanted is a wall that is
     * simply gone, so this is a proportion rather than a prohibition: a lake that is more lip than lake
     * was never contained in the first place.
     *
     * Read at block resolution, because adjacency is the whole claim — two lakes four blocks apart at
     * different heights are two lakes with rock between them, which is what a country full of small
     * craters looks like and not a fault at all.
     */
    test("a crater spills a little and holds the rest") {
        for ((atX, atZ) in someVolcanoes()) {
            var lava = 0
            var open = 0
            for (column in surfacesAround(atX, atZ)) {
                for (range in column.lava.ranges) {
                    for (y in range) {
                        lava++
                        if (touching(column).any { !it.lava.contains(y) && !it.rock.contains(y) }) open++
                    }
                }
            }
            if (lava == 0) continue
            val spilling = open.toDouble() / lava
            println("  the crater near ($atX, $atZ): $open of $lava blocks of lava face open air")
            check(spilling <= MOST_OF_IT_HELD) {
                "the crater near ($atX, $atZ) has open air beside $open of its $lava blocks of lava, " +
                    "which is a wall missing rather than a lip weeping"
            }
        }
    }

    /**
     * A crater that came out dry is a caldera nobody would walk into twice.
     *
     * Counted at **the cone's own axis and at that height only**, so a pond a few tens of blocks away
     * cannot stand in for the caldera that was meant to fill.
     */
    test("every crater actually holds some") {
        for ((atX, atZ) in someVolcanoes()) {
            val itsOwn = surfaceAt(atX, atZ).lakeTop
            check(itsOwn != null) { "the crater at ($atX, $atZ) has no lava standing over its own axis" }
            val wet = surfacesAround(atX, atZ).count { it.lakeTop == itsOwn }
            check(wet >= ENOUGH_TO_BE_A_LAKE) {
                "the crater near ($atX, $atZ) holds lava in only $wet of the columns sampled around it"
            }
        }
    }

    /**
     * **That a crater's lava stands on the crater's own rock**, which is what the obsidian lining converts.
     *
     * `MoltenLining` makes a rock block obsidian where a carried body of lava is over it or beside it, so
     * the bowl it draws is only as good as the assumption that there *is* rock under the lava. That holds
     * by construction — the lake is the crater cut intersected with a slab, and the cut is taken out of the
     * mountain — but it is the one thing the lining cannot check for itself at generation, and a change to
     * either shape could quietly leave the lava sitting on nothing.
     *
     * **A proportion rather than a prohibition, and the exception is the spill.** The rim is floored two
     * blocks under its own lava on purpose, so the roll notches it somewhere and a lavafall runs down the
     * flank — at that notch the lava genuinely has open air beneath it, and nothing should convert. Measured
     * at two columns in a hundred and ninety-seven, against a bound loose enough to keep a spill and tight
     * enough to fail a lake that came loose from its bowl.
     */
    test("a crater's lava stands on the crater's own rock") {
        for ((atX, atZ) in someVolcanoes()) {
            var floored = 0
            var hanging = 0
            for (column in surfacesAround(atX, atZ)) {
                val lowest = column.lava.ranges.firstOrNull()?.first ?: continue
                if (column.rock.contains(lowest - 1)) floored++ else hanging++
            }
            if (floored + hanging == 0) continue
            println("  the crater near ($atX, $atZ): $floored columns of lava on rock, $hanging on nothing")
            check(hanging <= (floored + hanging) * MOSTLY_ON_ITS_FLOOR) {
                "the crater near ($atX, $atZ) has lava standing on nothing in $hanging of " +
                    "${floored + hanging} columns, which is a lake off its bowl rather than a lip weeping"
            }
        }
    }

    /**
     * **How pocked a stretch of volcanic country actually is**, printed rather than asserted — small
     * craters are meant to be met on a walk rather than sought, and that is a density nobody can judge
     * from a rule about cell sizes.
     */
    test("how much of a country holds lava, for reading") {
        val bodies = HashMap<Int, Int>()
        var wet = 0
        var looked = 0
        for (x in -COUNTRY..COUNTRY step STRIDE) {
            for (z in -COUNTRY..COUNTRY step STRIDE) {
                looked++
                val standing = surfaceAt(x, z).lakeTop ?: continue
                wet++
                bodies[standing] = (bodies[standing] ?: 0) + 1
            }
        }
        val across = COUNTRY * 2
        println("  over ${across}x$across blocks: $wet of $looked columns hold lava, in ${bodies.size} bodies")
        println("  the largest: " + bodies.entries.sortedByDescending { it.value }.take(SOME).joinToString {
            "y=${it.key} (${it.value} columns)"
        })
    }

    /**
     * **Where one crater actually overflows**, read column by column rather than off the lattice.
     *
     * The channel through a rim is five blocks wide, so a scan every fourth column steps over most of it
     * and reports a crater as sealed when it is not. This is the reading that settles whether the spill
     * exists at all, and it is worth its cost once.
     */
    test("where a crater overflows, for reading") {
        val (atX, atZ) = someVolcanoes().first()
        var lava = 0
        var open = 0
        var lowest = Int.MAX_VALUE
        for (offsetX in -DOWN_THE_FLANK..DOWN_THE_FLANK) {
            for (offsetZ in -DOWN_THE_FLANK..DOWN_THE_FLANK) {
                val column = surfaceAt(atX + offsetX, atZ + offsetZ)
                for (range in column.lava.ranges) {
                    for (y in range) {
                        lava++
                        if (touching(column).any { !it.lava.contains(y) && !it.rock.contains(y) }) {
                            open++
                            lowest = minOf(lowest, y)
                        }
                    }
                }
            }
        }
        println("  the cone near ($atX, $atZ), every column: $lava blocks of lava, $open facing open air")
        if (open > 0) println("  the lowest of them stands at y=$lowest")
    }

    /** What one volcano actually came out as, printed rather than asserted — the instrument, as ever. */
    test("a crater in cross-section, for reading") {
        val (atX, atZ) = someVolcanoes().first()
        println("  the cone near ($atX, $atZ), west to east through its middle:")
        for (offset in -ACROSS..ACROSS step STRIDE) {
            val column = surfaceAt(atX + offset, atZ)
            val lake = column.lakeTop?.let { "lava to y=$it" } ?: "dry"
            println("    x${offset.toString().padStart(4)}  rock to y=${column.rockTop.toString().padStart(4)}  $lake")
        }
    }
}) {
    private companion object {
        private const val SEED = 11L

        /** The same lattice the vent feature draws its candidates from, so a sample is a column it could pick. */
        private const val STRIDE = 4

        /** Past the widest caldera at its largest pose, so a scan crosses the whole crater and its rim. */
        private const val ACROSS = 60

        /**
         * Columns on the [STRIDE] lattice, so about `pi r^2 / 16` of them — ten is a lake seven or eight
         * blocks across, which the narrowest crater here (a floor radius of six) comfortably beats and a
         * crater that failed to flood could not.
         */
        private const val ENOUGH_TO_BE_A_LAKE = 10

        /** How far out to look for cones to test, and how many is enough to have tested the four shapes. */
        private const val SEARCH = 3000.0
        private const val ENOUGH_CONES = 6

        /** Half a kilometre each way — about one mountain cell, so the reading is one stretch of country. */
        private const val COUNTRY = 512
        private const val SOME = 6

        /** Past a crater at its largest pose and well down the flank, where an overflow channel runs. */
        private const val DOWN_THE_FLANK = 110

        /**
         * How much of a lake's own surface may face open air.
         *
         * A notch or two on a rim is a handful of blocks against the thousands a crater holds, so this is
         * loose by an order of magnitude and still fails a wall that is not there.
         */
        private const val MOST_OF_IT_HELD = 0.02

        /**
         * How much of a lake may stand over open air — the spill notch, and nothing else.
         *
         * An order of magnitude over the two-in-197 measured, for the reason [MOST_OF_IT_HELD] is loose:
         * what this has to fail is a lake that came away from its bowl entirely, not a crater that weeps.
         */
        private const val MOSTLY_ON_ITS_FLOOR = 0.10

        private val BESIDE = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        private val VOLCANOES = VolcanoField.over(SEED)

        private data class Column(val x: Int, val z: Int, val rock: Spans, val lava: Spans) {
            val rockTop: Int get() = rock.highestSolidY ?: NOWHERE
            val lakeTop: Int? get() = lava.highestSolidY
        }

        /** Below any world, so a column with no rock in it never reads as ground. */
        private const val NOWHERE = Int.MIN_VALUE / 2

        private fun surfaceAt(x: Int, z: Int) = Column(
            x = x,
            z = z,
            rock = VOLCANOES.cones.columnSpans(x, z),
            lava = VOLCANOES.lakes.columnSpans(x, z),
        )

        /**
         * The four columns actually **touching** this one.
         *
         * Read at block resolution rather than off the [STRIDE] lattice, because adjacency is the whole
         * claim: two lakes four blocks apart at different heights are two lakes with rock between them,
         * which is what a country full of small craters looks like and not a fault. Only the lattice
         * columns that hold lava pay for this, so it is four reads apiece rather than a fine grid.
         */
        private fun touching(column: Column): List<Column> =
            BESIDE.map { (stepX, stepZ) -> surfaceAt(column.x + stepX, column.z + stepZ) }

        /** Every column on the lattice within [ACROSS] of a cone's axis, read once. */
        private fun surfacesAround(atX: Int, atZ: Int): List<Column> =
            (-ACROSS..ACROSS step STRIDE).flatMap { offsetX ->
                (-ACROSS..ACROSS step STRIDE).map { offsetZ -> surfaceAt(atX + offsetX, atZ + offsetZ) }
            }

        /**
         * Where the nearest cones stand, asked of [VolcanoField.sites] with the random factory an instanced
         * field builds from the same seed — so these are the answers generation gets rather than a guess.
         */
        private fun someVolcanoes(): List<Pair<Int, Int>> {
            val random = XoroshiroRandomSource(SEED).forkPositional()
            val found = mutableListOf<Pair<Int, Int>>()
            VolcanoField.sites(SEED).forEachInstanceNear(0, 0, SEARCH, random) { x, z, _ -> found += x to z }
            return found.sortedBy { (x, z) -> x.toLong() * x + z.toLong() * z }.take(ENOUGH_CONES)
        }
    }
}
