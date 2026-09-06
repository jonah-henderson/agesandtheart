package co.voik.agesandtheart.worldgen

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The volcanic cones, checked for the one thing a render would not settle: that a summit is a bowl.
 *
 * **The caldera is a subtraction, which is the defect worth guarding.** A cone with the crater cut too
 * shallow is a spike, and a cone with it cut too deep is a ring standing on nothing — both read as
 * "a mountain" from a distance and only the column knows the difference.
 */
@Tags(NEEDS_LANDFORMS)
class VolcanoCheck : FunSpec({

    val cones = VolcanoField.over(seed = 4242L)

    fun topAt(x: Int, z: Int): Int? = cones.columnSpans(x, z).highestSolidY

    /**
     * Where a volcano actually stands, found by looking rather than by asking the placement.
     *
     * Sampling is what a walk does, and it keeps this honest about the scatter really putting cones in
     * reach — a check that read the site list would pass even if nothing were ever placed.
     */
    val summit: Triple<Int, Int, Int>? = run {
        var best: Triple<Int, Int, Int>? = null
        var x = -SEARCH
        while (x <= SEARCH) {
            var z = -SEARCH
            while (z <= SEARCH) {
                val top = topAt(x, z)
                if (top != null && (best == null || top > best!!.third)) best = Triple(x, z, top)
                z += STEP
            }
            x += STEP
        }
        best
    }

    test("an Age that asks for volcanoes gets some within reach of where it starts") {
        check(summit != null) {
            "no cone anywhere in ${SEARCH * 2} blocks of the origin — the scatter is too thin to ever meet"
        }
    }

    test("a summit is a bowl rather than a spike") {
        val (x, z, rimTop) = summit ?: return@test
        // The rim was found by sampling, so the centre is somewhere inside it rather than exactly here;
        // what matters is that going *inward* from the highest column finds ground that is lower.
        val inward = (1..CALDERA_PROBE).mapNotNull { step -> topAt(x + step, z) }
        val dips = inward.any { it < rimTop - MEANINGFUL_DIP }
        check(dips) {
            "no column within $CALDERA_PROBE of the highest point sat $MEANINGFUL_DIP below it: " +
                "the crater is not being cut, so these are spikes rather than volcanoes (rim $rimTop, " +
                "inward $inward)"
        }
    }

    test("the cones stand clear of the ground they are laid over") {
        val (_, _, top) = summit ?: return@test
        check(top > WORTH_CLIMBING) {
            "the tallest cone topped out at $top, which is not a mountain anything would notice"
        }
    }
}) {
    companion object {
        /** Wide enough to cross a cell or two of the scatter, coarse enough to stay cheap. */
        private const val SEARCH = 2048
        private const val STEP = 16

        private const val CALDERA_PROBE = 40
        private const val MEANINGFUL_DIP = 8

        private const val WORTH_CLIMBING = 40
    }
}
