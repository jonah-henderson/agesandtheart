package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos

/**
 * **How often a volcano throws, and how hard** — the half of the material a walk cannot judge.
 *
 * You can see a bomb; you cannot time two minutes between them, and you certainly cannot tell a lone tube
 * firing every two minutes from one firing every twenty. Since the whole shape of the design is a curve
 * from "a rare threat in a mine" to "a barrage off a caldera", every point on it is a number nobody would
 * notice being wrong.
 */
class LavaTubesCheck : FunSpec({

    /** Jonah's figure, and the anchor everything else on the curve is read against. */
    test("a tube on its own throws about every two minutes") {
        val apart = LavaTubes.TICKS_BETWEEN_VISITS / LavaTubes.eagernessAmong(ALONE)
        check(apart in NEARLY_TWO_MINUTES..OVER_TWO_MINUTES) {
            "a lone tube throws every ${apart.toInt()} ticks, where two minutes is $TWO_MINUTES"
        }
    }

    /**
     * **And a crowded one throws on every visit, which is the ceiling and not a choice.** No block can
     * fire more often than vanilla visits it, so past this point danger has to come from there being more
     * blocks rather than from each being keener — worth knowing before anyone reaches for the dial.
     */
    test("a tube among its own throws on every visit") {
        check(LavaTubes.eagernessAmong(SURROUNDED) >= EVERY_VISIT) {
            "a fully surrounded tube throws on ${LavaTubes.eagernessAmong(SURROUNDED)} of its visits"
        }
    }

    /** Monotonic, or "a group of them buff each other" is not what the curve says. */
    test("company only ever makes a tube keener") {
        var keenest = 0.0
        for (crowd in 0..SURROUNDED) {
            val here = LavaTubes.eagernessAmong(crowd)
            check(here >= keenest) { "a tube with $crowd beside it was less eager than one with fewer" }
            keenest = here
        }
    }

    /**
     * **A caldera's vent is a barrage and a seam is not**, which is the whole span the material has to
     * cover. Read as the wait between shots from the whole mass: more blocks means more visits, and each
     * visit is likelier, so the two multiply.
     */
    test("the wait between shots runs from minutes to a moment") {
        val alone = waitFor(ONE_TUBE, ALONE)
        val seam = waitFor(A_SEAM, HALF_SURROUNDED)
        val vent = waitFor(A_CALDERA_VENT, SURROUNDED)
        println("  a lone tube: ${alone.toInt()} ticks between shots")
        println("  a seam of $A_SEAM: ${seam.toInt()}")
        println("  a caldera vent of $A_CALDERA_VENT: ${vent.toInt()}")
        check(alone > seam && seam > vent) { "the wait did not shorten with the size of the mass" }
        check(vent < A_MOMENT) { "a caldera vent waits ${vent.toInt()} ticks between shots, which is not a volcano" }
    }

    /**
     * Force is the mass, and it has **no threshold in it any more**: the sixteen-block minimum made the
     * material a cliff, where a tube you dig into alone should still be able to lob something weak at you.
     */
    test("force climbs with the mass from nothing to everything") {
        check(LavaTubes.forceOf(massOf(ONE_TUBE)) > NOTHING) { "a single tube throws with no force at all" }
        check(LavaTubes.forceOf(massOf(A_SEAM)) < LavaTubes.forceOf(massOf(A_CALDERA_VENT))) {
            "a seam threw as hard as a caldera"
        }
        check(LavaTubes.forceOf(massOf(A_CALDERA_VENT)) == EVERYTHING) {
            "a caldera vent throws at ${LavaTubes.forceOf(massOf(A_CALDERA_VENT))} rather than the full force"
        }
    }
}) {
    private companion object {
        private const val ALONE = 0
        private const val HALF_SURROUNDED = 13
        private const val SURROUNDED = 26

        private const val ONE_TUBE = 1
        private const val A_SEAM = 12

        /** Twenty-nine columns three to five deep, which is what the vent feature seats — and past the cap. */
        private const val A_CALDERA_VENT = 100

        private const val TWO_MINUTES = 2400.0
        private const val NEARLY_TWO_MINUTES = 2200.0
        private const val OVER_TWO_MINUTES = 2600.0

        /** A shot every few seconds at most, or a caldera is a curiosity rather than a hazard. */
        private const val A_MOMENT = 40.0

        private const val EVERY_VISIT = 1.0
        private const val NOTHING = 0.0
        private const val EVERYTHING = 1.0

        /** Visits arrive per block, so a mass of [tubes] is visited that many times as often. */
        private fun waitFor(tubes: Int, crowd: Int): Double =
            LavaTubes.TICKS_BETWEEN_VISITS / (tubes * LavaTubes.eagernessAmong(crowd))

        private fun massOf(size: Int): Set<BlockPos> = (0..<size).map { BlockPos(it, 0, 0) }.toSet()
    }
}
