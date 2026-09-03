package co.voik.agesandtheart.worldgen.field

import io.kotest.core.spec.style.FunSpec
import kotlin.math.PI
import kotlin.math.abs

/**
 * A ring of round section, at whatever angle it was left at.
 *
 * **Volume is what checks the rotation**, and it is why this spec is arithmetic rather than a picture. A
 * torus holds `2π²Rr²` however it is turned, so counting the blocks a tipped one lays and comparing
 * against the closed form catches a rotation that stretches, shears or loses part of the ring — none of
 * which a "does it look like a ring" assertion would notice.
 */
class TorusCheck : FunSpec({

    val ring = 45.0
    val tube = 5.0

    fun blocksIn(torus: Torus): Int {
        val reach = kotlin.math.ceil(torus.horizontalReach).toInt()
        var laid = 0
        for (x in -reach..reach) {
            for (z in -reach..reach) {
                laid += torus.columnSpans(x, z).ranges.sumOf { it.last - it.first + 1 }
            }
        }
        return laid
    }

    /** `2π²Rr²`, the closed form a torus of this section holds. */
    val analytic = 2 * PI * PI * ring * tube * tube

    fun flat(tilt: Double = 0.0, turn: Double = 0.0) =
        Torus(0, 0, 0, ring, tube, tilt = tilt, turn = turn)

    test("a ring lying flat holds what a ring holds") {
        val counted = blocksIn(flat())
        check(abs(counted - analytic) / analytic < TOLERANCE) {
            "a ring of $ring by $tube laid $counted blocks where the arithmetic says ${analytic.toInt()}"
        }
    }

    /** The one that catches a rotation doing anything but rotating. */
    test("tipping and turning it changes nothing about how much of it there is") {
        val upright = blocksIn(flat())
        for (tilt in listOf(20.0, 45.0, 90.0, 135.0)) {
            for (turn in listOf(0.0, 30.0, 90.0)) {
                val tipped = blocksIn(flat(tilt, turn))
                check(abs(tipped - upright).toDouble() / upright < TOLERANCE) {
                    "tipped $tilt and turned $turn, the ring laid $tipped blocks against $upright lying flat"
                }
            }
        }
    }

    test("the hole is a hole, and the outside is outside") {
        val torus = flat()
        check(torus.columnSpans(0, 0).ranges.isEmpty()) { "the middle of the ring is solid" }
        check(torus.columnSpans(45, 0).ranges.isNotEmpty()) { "the ring itself is not there" }
        check(torus.columnSpans(60, 0).ranges.isEmpty()) { "it reaches past its own radius" }
    }

    /**
     * A ring on edge is as tall as it is wide, and a flat one is only as tall as its tube. **Measured as
     * the reach from its lowest block to its highest, not as a count of them**: a column through an
     * upright ring meets the tube twice and holds only two tubes' worth of blocks, spread over the whole
     * height of the thing.
     */
    test("standing it on edge stands it up") {
        fun heightAt(torus: Torus, x: Int): Int {
            val spans = torus.columnSpans(x, 0).ranges
            return if (spans.isEmpty()) 0 else spans.last().last - spans.first().first + 1
        }
        val lying = heightAt(flat(), x = 45)
        val standing = heightAt(flat(tilt = 90.0), x = 0)
        val acrossTheRing = 2 * (ring + tube)
        check(standing > acrossTheRing * 0.9 && lying < 3 * tube) {
            "on edge the ring reaches $standing blocks through its middle and lying flat $lying, where " +
                "across it is ${acrossTheRing.toInt()}"
        }
    }

    test("resizing scales what it holds by the cube of the factor") {
        val ordinary = blocksIn(flat())
        val doubled = blocksIn(flat().resized(2.0, 0) as Torus)
        val eightfold = ordinary * 8.0
        check(abs(doubled - eightfold) / eightfold < TOLERANCE) {
            "doubled, a $ordinary-block ring came out $doubled where eight times is ${eightfold.toInt()}"
        }
    }
}) {
    private companion object {
        /** Voxels against a continuum: the surface of a ring this size is a few percent of its volume. */
        const val TOLERANCE = 0.05
    }
}
