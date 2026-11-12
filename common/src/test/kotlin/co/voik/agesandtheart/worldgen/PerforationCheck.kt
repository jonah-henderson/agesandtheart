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
})

private const val SEED = 42L
private const val HOLE_SIZE = 2.5
private const val ACROSS = 48
private const val TOLERANCE = 0.05
