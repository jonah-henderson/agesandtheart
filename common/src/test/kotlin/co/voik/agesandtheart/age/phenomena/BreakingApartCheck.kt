package co.voik.agesandtheart.age.phenomena

import io.kotest.core.spec.style.FunSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * **That the pieces of a broken body are born clear of one another.**
 *
 * They were not, and it read as a fault in something else entirely: three fragments jittered off one point
 * overlapped at birth, and — now that a body is a shape and solid to its fellows — spent their lives
 * shoving and getting nowhere, so a break looked like one rock going lumpy rather than three coming apart
 * (Jonah, 2026-09-09).
 *
 * The fix is geometry and so is the risk in it. Set out on a ring of one span the worst turn leaves a
 * hundredth of a block between two boxes, which is clear on paper and would come apart the day the
 * fragment count or the wander moved. This is the arithmetic, held at the extremes rather than trusted.
 */
class BreakingApartCheck : FunSpec({

    /**
     * Every turn of the ring, with each neighbour wandering as far toward the other as it may.
     *
     * Boxes are upright cubes, so what matters is the *widest* separation on either horizontal axis: two
     * cubes clear each other as soon as one axis does, however near the other one is.
     */
    test("no two fragments of a break can touch, at any turn of the ring") {
        val radius = DriftingOre.ringRadiusFor(A_SPAN)
        val leastApart = A_FULL_TURN / DriftingOre.FRAGMENTS - A_FULL_TURN * DriftingOre.WANDER_OFF_THE_RING
        var worst = Double.MAX_VALUE
        for (step in 0..<TURNS_TRIED) {
            val bearing = A_FULL_TURN * step / TURNS_TRIED
            val beside = bearing + leastApart
            val acrossX = abs(radius * cos(bearing) - radius * cos(beside))
            val acrossZ = abs(radius * sin(bearing) - radius * sin(beside))
            worst = min(worst, max(acrossX, acrossZ))
        }
        check(worst > A_SPAN) {
            "two fragments a span wide come within $worst of one another, so a break is born stuck"
        }
    }

    /** And with room to spare, since "clear by a hundredth" is what this went wrong as. */
    test("and they are clear by a margin rather than by arithmetic") {
        val radius = DriftingOre.ringRadiusFor(A_SPAN)
        check(radius >= A_SPAN * ROOM_WORTH_HAVING) {
            "the ring is $radius wide for a span of $A_SPAN, which is too fine a margin to build a break on"
        }
    }
}) {
    private companion object {
        private const val A_SPAN = 1.0
        private const val A_FULL_TURN = PI * 2
        private const val TURNS_TRIED = 720

        /** Enough that a fragment nudged by anything at all is still clear on the next tick. */
        private const val ROOM_WORTH_HAVING = 1.2
    }
}
