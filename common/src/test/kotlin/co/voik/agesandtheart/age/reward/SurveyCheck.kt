package co.voik.agesandtheart.age.reward

import io.kotest.core.spec.style.FunSpec

/**
 * That what the survey says an Age holds is what the ground will actually hold.
 *
 * **The coupling is the point, not the words.** [Yield] bands the number [Deposits] places, so the only
 * way the report can lie is if the two stop agreeing — which is what the sweep below is for, and why
 * nothing here restates a vein count of its own.
 *
 * The materials half is not reachable from here: naming one asks the item registry, which is frozen by the
 * time a spec runs (`DepositsCheck` has the argument in full).
 */
class SurveyCheck : FunSpec({

    test("an Age that earns no deposit is reported as holding none") {
        val safe = danger(score = 0.0)
        check(Yield.forVeins(Deposits.veinsPerChunk(safe)) == Yield.NONE) {
            "a safe Age was surveyed as holding something"
        }
    }

    test("an Age nobody wrote is reported as holding none, however dangerous") {
        val found = danger(score = 1.0, authored = false)
        check(Yield.forVeins(Deposits.veinsPerChunk(found)) == Yield.NONE) {
            "a found Age was surveyed as holding a deposit it will never grow"
        }
    }

    test("an Age over the threshold always reads as holding something") {
        val marginal = danger(score = PAYS_ABOVE)
        check(Yield.forVeins(Deposits.veinsPerChunk(marginal)) != Yield.NONE) {
            "an Age that earns a vein was surveyed as barren, which reads as the reward being broken"
        }
    }

    test("a doomed Age reads at the top of the scale, however marginal it scored") {
        val doomed = danger(score = PAYS_ABOVE, terminal = ALL_OF_IT)
        check(Yield.forVeins(Deposits.veinsPerChunk(doomed)) == Yield.IMMENSE) {
            "a doomed Age's tenfold hoard did not reach the top band"
        }
    }

    test("the report never falls as the Age grows more dangerous") {
        val scores = (0..40).map { it / 20.0 }
        val reported = scores.map { Yield.forVeins(Deposits.veinsPerChunk(danger(score = it))) }
        val slipped = reported.zipWithNext().filter { (earlier, later) -> later < earlier }
        check(slipped.isEmpty()) {
            "a dearer Age surveyed as holding less than a cheaper one: $slipped"
        }
    }

    test("every band is reachable from a score something could be written at") {
        val scores = (0..400).map { it / 20.0 }
        val reached = scores.flatMap { score ->
            listOf(danger(score = score), danger(score = score, terminal = ALL_OF_IT))
        }.map { Yield.forVeins(Deposits.veinsPerChunk(it)) }.toSet()
        val unreachable = Yield.entries - reached - Yield.NONE
        check(unreachable.isEmpty()) {
            "these bands describe an amount no Age can hold, so the word would never be read: $unreachable"
        }
    }
}) {
    companion object {
        private const val ALL_OF_IT = 1.0
        private const val PAYS_ABOVE = 0.25

        /** Everything in one contributor, so a test names a score rather than four of them. */
        private val WEIGHTS =
            DangerTable.Weights(materials = 1.0, spawns = 0.0, phenomena = 0.0, lighting = 0.0, features = 0.0)

        private fun danger(score: Double, authored: Boolean = true, terminal: Double = 0.0) = Danger(
            materials = score,
            spawns = 0.0,
            phenomena = 0.0,
            lighting = 0.0,
            features = 0.0,
            terminal = terminal,
            authored = authored,
            weights = WEIGHTS,
            paysAbove = PAYS_ABOVE,
        )
    }
}
