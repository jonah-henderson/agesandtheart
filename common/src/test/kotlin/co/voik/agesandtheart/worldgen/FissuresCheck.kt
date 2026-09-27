package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Fissures
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec

/**
 * The fissured underground, checked for what makes it fissures rather than chambers: tall runs, and
 * nowhere a city could stand. The room a city wants is read as the largest open square in a level slice,
 * since a fissure is long one way and narrow the other.
 */
class FissuresCheck : FunSpec({

    val lowY = -59
    val highY = 288
    val band = highY - lowY + 1

    fun tallestRun(fissures: Fissures): Int {
        var tallest = 0
        for (x in -AREA..AREA step 2) for (z in -AREA..AREA step 2) {
            for (run in fissures.columnSpans(x, z).ranges) tallest = maxOf(tallest, run.last - run.first + 1)
        }
        return tallest
    }

    /** The side of the largest square open all the way across, at [y], over the area checked. */
    fun widestSquareAt(fissures: Fissures, y: Int): Int {
        val side = 2 * AREA + 1
        val open = Array(side) { i -> BooleanArray(side) { j -> fissures.columnSpans(i - AREA, j - AREA).contains(y) } }
        val square = Array(side) { IntArray(side) }
        var widest = 0
        for (i in 0..<side) for (j in 0..<side) {
            if (!open[i][j]) continue
            val fromNeighbours = if (i == 0 || j == 0) 0 else minOf(square[i - 1][j], square[i][j - 1], square[i - 1][j - 1])
            square[i][j] = fromNeighbours + 1
            widest = maxOf(widest, square[i][j])
        }
        return widest
    }

    fun openShareAt(fissures: Fissures, y: Int): Double {
        var open = 0
        var all = 0
        for (x in -AREA..AREA step 2) for (z in -AREA..AREA step 2) {
            all++
            if (fissures.columnSpans(x, z).contains(y)) open++
        }
        return open.toDouble() / all
    }

    test("every open run is inside the band") {
        for (size in SIZES) {
            val fissures = FissuresField.openings(lowY, highY, size, salt = 7L)
            for (x in -AREA..AREA step 7) for (z in -AREA..AREA step 5) {
                for (run in fissures.columnSpans(x, z).ranges) {
                    check(run.first >= lowY && run.last <= highY) { "size $size: ($x, $z) opens $run outside the band" }
                }
            }
        }
    }

    // Measured 2026-09-24 at salt 7: the widest open square at y 0, 63, 120 and 200 was 6..7 at minuscule,
    // 11..13 unsaid and 15..27 colossal; the open share was 2% to 11% of a slice.
    test("nowhere is there room for a city, even colossal") {
        val colossal = FissuresField.openings(lowY, highY, 1.0, salt = 7L)
        val widest = LEVELS.associateWith { widestSquareAt(colossal, it) }
        check(widest.values.all { it < ROOM_FOR_A_CITY }) { "open squares this wide by height: $widest" }
    }

    test("colossal fissures stand nearly the whole band") {
        val tallest = tallestRun(FissuresField.openings(lowY, highY, 1.0, salt = 7L))
        check(tallest >= band * NEARLY_THE_WHOLE_BAND) { "the tallest colossal run is $tallest of $band" }
    }

    test("the fissures stay much taller than they are wide, at every size") {
        for (size in SIZES) {
            val fissures = FissuresField.openings(lowY, highY, size, salt = 7L)
            check(fissures.height >= fissures.width * TALLER_THAN_WIDE) { "size $size: $fissures" }
        }
    }

    test("the fissures leave most of the rock standing") {
        val colossal = FissuresField.openings(lowY, highY, 1.0, salt = 7L)
        val shares = LEVELS.associateWith { openShareAt(colossal, it) }
        check(shares.values.all { it < MOST_OPEN }) { "open share by height: $shares" }
    }

    test("each step up the size axis makes the fissures taller and wider") {
        val all = SIZES.map { FissuresField.openings(lowY, highY, it) }
        check(all.zipWithNext().all { (smaller, larger) -> larger.height > smaller.height && larger.width > smaller.width }) {
            "fissures by size: $all"
        }
    }

    test("the fissures round-trip through their codec") {
        val written: TerrainField = FissuresField.openings(lowY, highY, 1.0, salt = 7L)
        val encoded = TerrainField.CODEC.encodeStart(JsonOps.INSTANCE, written)
            .getOrThrow { failure -> error("the fissures would not encode: $failure") }
        val read = TerrainField.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow { failure -> error("the fissures would not read back: $failure") }
        check(read == written) { "read back as different fissures" }
    }
}) {
    private companion object {
        const val AREA = 300
        val SIZES = listOf(-1.0, -0.5, null, 0.5, 1.0)
        val LEVELS = listOf(0, 63, 120, 200)

        // A D'ni city is about two hundred blocks across; a fissure crossing another is about thirty.
        const val ROOM_FOR_A_CITY = 40
        const val NEARLY_THE_WHOLE_BAND = 0.8
        const val TALLER_THAN_WIDE = 8
        const val MOST_OPEN = 0.25
    }
}
