package co.voik.agesandtheart.worldgen

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** Holes take the share of what is laid a porosity asks for, half as much at a half, and none at nought. */
class PerforationCheck : FunSpec({

    fun voidShare(porosity: Double): Double {
        val holes = Perforation(SEED, HOLE_SIZE, porosity)
        var holed = 0
        for (x in 0..<ACROSS) for (y in 0..<ACROSS) for (z in 0..<ACROSS) if (holes.isHoleAt(x, y, z)) holed++
        return holed.toDouble() / (ACROSS * ACROSS * ACROSS)
    }

    test("a porosity of one leaves the most void, a half half of it, and nought none") {
        val full = voidShare(1.0)
        val half = voidShare(0.5)
        check(abs(full - Perforation.MOST_VOID) < TOLERANCE) { "a porosity of one left $full void" }
        check(abs(half - Perforation.MOST_VOID / 2) < TOLERANCE) { "a porosity of a half left $half void" }
        check(voidShare(0.0) == 0.0) { "a porosity of nought left holes" }
    }

    test("the land's holes take a third of the land at the most, and only over the sea") {
        val holes = requireNotNull(PorousLand.holes(1.0, SEED)) { "a porosity of one made no holes" }
        val columns = (0..<COLUMNS).flatMap { x -> (0..<COLUMNS).map { z -> holes.columnSpans(x * STRIDE, z * STRIDE) } }
        val band = (columns.first().ranges.firstOrNull()?.first ?: 0)
        val holed = columns.sumOf { spans -> spans.ranges.sumOf { it.last - it.first + 1 } }
        val share = holed.toDouble() / (columns.size * (BAND_TOP - BAND_FLOOR + 1))
        check(abs(share - Perforation.MOST_VOID) < TOLERANCE) { "the land's holes took $share of it" }
        check(columns.all { spans -> spans.ranges.all { it.first >= BAND_FLOOR } }) { "a hole reached under the sea's lip (from $band)" }
        check(PorousLand.holes(0.0, SEED) == null) { "a porosity of nought made holes" }
    }
})

private const val SEED = 42L
private const val COLUMNS = 24
private const val STRIDE = 7
private const val BAND_FLOOR = 66
private const val BAND_TOP = 255
private const val HOLE_SIZE = 2.5
private const val ACROSS = 48
private const val TOLERANCE = 0.05
