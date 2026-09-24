package co.voik.agesandtheart.worldgen

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The impact structure, checked for the things a render cannot show.
 *
 * **A shelf is the defect this file exists for.** Every crater here is a bowl subtracted from the ground,
 * and a bowl that fails to reach above the ground it is cut into removes a lens out of the middle of the
 * rock and leaves the surface standing on nothing. From above that is invisible — the plan view draws the
 * shelf as ordinary ground — and from a slice it only shows if the cut happens to be on the line. The
 * column knows, so ask the column.
 *
 * **The geometry is checked on `bareWorld`, and that is not a convenience.** Weathering opens overhangs,
 * which is a column in two pieces on purpose, so the shelf assertion cannot be made against the finished
 * shape at all. The last test here is the one that reads the weathered world, and what it asks is whether
 * the weather did what its profile claims rather than what it left behind.
 */
@Tags(NEEDS_LANDFORMS)
class CraterlandsCheck : FunSpec({

    // The structure these numbers were walked at, which is `large`.
    val tuned = CraterlandsField.Steer(size = TUNED_SIZE)
    val bare = CraterlandsField.bareWorld(tuned, salt = 0L)


    fun surfaceAt(x: Int, z: Int): Int =
        bare.columnSpans(x, z).highestSolidY ?: error("($x, $z) holds no rock at all")

    /** A ring of columns at [radius], enough of them that a crater cannot fall between two. */
    fun aroundTheRing(radius: Double, bearings: Int = 96): List<Pair<Int, Int>> =
        (0..<bearings).map { step ->
            val angle = step * TWO_PI / bearings
            (cos(angle) * radius).roundToInt() to (sin(angle) * radius).roundToInt()
        }

    test("no column anywhere is broken into more than one run") {
        val broken = COLUMNS.filter { (x, z) -> bare.columnSpans(x, z).ranges.size != 1 }
        check(broken.isEmpty()) {
            "${broken.size} columns are cut into pieces, the first at ${broken.take(4)} — " +
                "something is standing on nothing"
        }
    }

    test("the basin is a hole rather than the cone it was cut from") {
        // The cone the excavation is taken out of reaches about 200 blocks over the plain at this radius,
        // so anything near that means the ellipsoid stopped short of clearing it.
        for (radius in 40..190 step 15) {
            val tops = aroundTheRing(radius.toDouble()).map { (x, z) -> surfaceAt(x, z) }
            check(tops.max() < CraterlandsField.PLAIN_Y + MOST_A_BASIN_MAY_STAND) {
                "The basin at radius $radius reaches ${tops.max()}, which is the apron still standing in it"
            }
        }
    }

    test("the crest reaches its full height, and is notched right through in places") {
        val crest = aroundTheRing(CraterlandsField.RIM_CREST_RADIUS).map { (x, z) -> surfaceAt(x, z) }
        check(crest.max() == CraterlandsField.RIM_CREST_Y) {
            "The rim crest tops out at ${crest.max()}, not at ${CraterlandsField.RIM_CREST_Y}"
        }
        // The scalloping is the whole reason the rim is not a circle, and a ceiling that never dips below
        // the crest would leave it as one without failing anything else here.
        check(crest.min() <= CraterlandsField.RIM_CREST_Y - DEEP_ENOUGH_TO_BE_A_PASS) {
            "The crest never drops below ${crest.min()}, so the wall has no way through it"
        }
        val inside = aroundTheRing(CraterlandsField.RIM_CREST_RADIUS - 40).map { (x, z) -> surfaceAt(x, z) }
        val outside = aroundTheRing(CraterlandsField.RIM_CREST_RADIUS + 40).map { (x, z) -> surfaceAt(x, z) }
        check(inside.max() < CraterlandsField.RIM_CREST_Y) {
            "The ground 40 blocks inside the crest reaches ${inside.max()}, so there is no inner wall"
        }
        check(outside.max() < CraterlandsField.RIM_CREST_Y) {
            "The ground 40 blocks outside the crest reaches ${outside.max()}, so the crest is not the crest"
        }
    }

    test("the ejecta blanket is smooth and circular where nothing has scalloped it") {
        // **Bounded at both ends, and both bounds are real.** Further out the blanket has fallen to
        // within a few blocks of the plain, so a tall crater rim pokes through its toe; further in it
        // has climbed into the scalloping ceiling's reach. Between the two it is blanket and nothing
        // else, which is where a claim about its being a surface of revolution can be made at all.
        for (radius in 330..350 step 10) {
            val tops = aroundTheRing(radius.toDouble()).map { (x, z) -> surfaceAt(x, z) }
            val spread = tops.max() - tops.min()
            check(spread <= MOST_A_SMOOTH_RING_MAY_VARY) {
                "The blanket at radius $radius runs from ${tops.min()} to ${tops.max()} — " +
                    "$spread blocks of variation around a circle that should have none"
            }
        }
    }

    test("a bowl never comes out without its rim") {
        // Out on the plain, clear of every ring, where a crater is the only thing that can move the ground.
        val plain = (950..1400 step 13).flatMap { x -> (-500..500 step 13).map { z -> x to z } }
        val surfaces = plain.associateWith { (x, z) -> surfaceAt(x, z) }
        val bowls = plain.filter { surfaces.getValue(it) < CraterlandsField.PLAIN_Y - CLEARLY_A_BOWL }
        check(bowls.isNotEmpty()) { "No crater was sampled at all, so this test proved nothing" }

        fun hasARimAround(bowl: Pair<Int, Int>): Boolean {
            val reach = CraterlandsField.CRATER_REACH * CraterlandsField.WIDEST_CRATER
            val nearby = plain.filter { (x, z) ->
                hypot((x - bowl.first).toDouble(), (z - bowl.second).toDouble()) <= reach
            }
            return nearby.any { surfaces.getValue(it) > CraterlandsField.PLAIN_Y + CLEARLY_A_RIM }
        }

        // **A minority genuinely have none, and that is the mechanism working.** Every bowl is subtracted
        // from every rim, so where two craters overlap the younger one eats the older one's rim — which is
        // the superposition the two layers exist to buy. What a desync would look like is different in
        // kind, not degree: shape, size and height would be drawn independently between the two layers, so
        // about half of all bowls would come out larger than the rim placed with them and swallow it.
        val rimless = bowls.filterNot(::hasARimAround)
        val share = rimless.size.toDouble() / bowls.size
        check(share <= MOST_BOWLS_A_NEIGHBOUR_MAY_HAVE_EATEN) {
            "${rimless.size} of ${bowls.size} sampled bowls have no rim around them, the first at " +
                "${rimless.first()} — the two crater layers have come apart"
        }
    }

    test("the peak ring is drawn for some Ages and not for others") {
        fun hasAPeakRing(salt: Long): Boolean {
            val ring = CraterlandsField.bareWorld(tuned, salt = salt)
            return aroundTheRing(CraterlandsField.PEAK_RING_RADIUS).any { (x, z) ->
                (ring.columnSpans(x, z).highestSolidY ?: 0) > CraterlandsField.PLAIN_Y
            }
        }

        val drawn = SALTS.count(::hasAPeakRing)
        check(drawn in 1..<SALTS.size) {
            "The peak ring was drawn for $drawn of ${SALTS.size} Ages, so the draw is not a draw"
        }
    }

    test("the weather works the wall and spares the plain, which is what its profile claims") {
        val weathered = CraterlandsField.world(tuned, salt = 0L)
        fun lostAt(x: Int, z: Int): Int {
            val before = bare.columnSpans(x, z).highestSolidY ?: return 0
            val after = weathered.columnSpans(x, z).highestSolidY ?: return before
            return before - after
        }

        // The upper wall, where the profile gives up nearly all of the plain's protection.
        val wall = (195..215 step 2).flatMap { radius -> aroundTheRing(radius.toDouble(), bearings = 24) }
        val plain = (950..1300 step 15).flatMap { x -> (-350..350 step 15).map { z -> x to z } }

        val wallWorked = wall.count { (x, z) -> lostAt(x, z) > 0 }.toDouble() / wall.size
        val plainWorked = plain.count { (x, z) -> lostAt(x, z) > 0 }.toDouble() / plain.size

        check(wallWorked >= LEAST_OF_A_WALL_THE_WEATHER_TAKES) {
            "The weather touched ${(wallWorked * 100).toInt()}% of the rim wall, which is not sculpting it"
        }
        check(plainWorked <= MOST_OF_A_PLAIN_THE_WEATHER_TAKES) {
            "The weather touched ${(plainWorked * 100).toInt()}% of the plain, which is eating the craters " +
                "rather than roughening them"
        }
        check(wallWorked > plainWorked) {
            "The weather works the plain at least as hard as the wall, so the vertical profile is upside down"
        }
    }

    test("no steer any word can ask for breaks a column") {
        // **The one assertion that has to hold for every combination rather than for the default.** The
        // numeric claims above are about the landform as tuned; this one is about the arithmetic, and a
        // word bending three axes at once is exactly how a derived relation gets broken without anyone
        // noticing — an anchor that no longer clears the plain, a bowl that no longer clears a rim.
        val ends = listOf(-1.0, 0.0, 1.0, null)
        val steers = ends.flatMap { wear ->
            ends.flatMap { relief -> ends.map { spacing -> CraterlandsField.Steer(wear, relief, spacing, TUNED_SIZE) } }
        }
        for (steer in steers) {
            val world = CraterlandsField.bareWorld(steer, salt = 0L)
            val broken = STEER_COLUMNS.filter { (x, z) -> world.columnSpans(x, z).ranges.size != 1 }
            check(broken.isEmpty()) {
                "$steer leaves ${broken.size} columns cut into pieces, the first at ${broken.take(3)}"
            }
        }
    }

}) {
    private companion object {
        const val TWO_PI = 2.0 * Math.PI

        /** `large` on `Terrain.SIZE`, which is where the impact structure was walked. */
        const val TUNED_SIZE = 0.5

        /** Spanning the basin, the blanket and a good stretch of plain, on a grid no crater can hide in. */
        val COLUMNS: List<Pair<Int, Int>> =
            (-1100..1100 step 19).flatMap { x -> (-1100..1100 step 19).map { z -> x to z } }

        val SALTS: List<Long> = (1L..24L).toList()

        /** Coarser than [COLUMNS], since this one is walked sixty-four times over. */
        val STEER_COLUMNS: List<Pair<Int, Int>> =
            (-900..900 step 43).flatMap { x -> (-900..900 step 43).map { z -> x to z } }

        /** The basin floor rises to about a hundred over the plain at its rim; the cone reached two. */
        const val MOST_A_BASIN_MAY_STAND = 120

        /** The plain's own noise, and a block of integer rounding on a circle. */
        const val MOST_A_SMOOTH_RING_MAY_VARY = 2

        /** Far enough under the crest that it is a way through rather than a dip in it. */
        const val DEEP_ENOUGH_TO_BE_A_PASS = 14

        /** Deeper than the plain's own relief can account for, so it is a crater and not a hollow. */
        const val CLEARLY_A_BOWL = 7

        /** And higher than the plain's own relief, so it is a rim. */
        const val CLEARLY_A_RIM = 4

        /** Well under what an independent draw would give — see the note at the assertion. */
        const val MOST_BOWLS_A_NEIGHBOUR_MAY_HAVE_EATEN = 0.15

        /** Enough of the face to read as fluted rather than as occasional damage. */
        const val LEAST_OF_A_WALL_THE_WEATHER_TAKES = 0.2

        /** And little enough of the plain that a crater rim is roughened rather than removed. */
        const val MOST_OF_A_PLAIN_THE_WEATHER_TAKES = 0.15
    }
}
