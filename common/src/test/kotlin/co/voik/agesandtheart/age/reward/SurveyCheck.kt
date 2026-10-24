package co.voik.agesandtheart.age.reward

import io.kotest.core.spec.style.FunSpec

/**
 * That what the survey says an Age holds is what the ground will actually hold.
 *
 * **The coupling is the point, not the words.** [Deposits] lays whatever [Yield] an Age reads at, so the report
 * and the ground share one answer; what is pinned here is that it moves the right way with danger.
 *
 * The materials half is not reachable from here: naming one asks the item registry, which is frozen by the
 * time a spec runs (`DepositsCheck` has the argument in full).
 */
class SurveyCheck : FunSpec({

    test("an Age that earns no deposit is reported as holding none") {
        val safe = danger(score = 0.0)
        check(Yield.of(safe) == Yield.NONE) {
            "a safe Age was surveyed as holding something"
        }
    }

    test("an Age over the threshold always reads as holding something") {
        val marginal = danger(score = ScoredDanger.LINES.paysAt)
        check(Yield.of(marginal) != Yield.NONE) {
            "an Age that earns a vein was surveyed as barren, which reads as the reward being broken"
        }
    }

    test("a doomed Age reads as its massive layers, however marginal it scored") {
        val doomed = danger(score = ScoredDanger.LINES.paysAt, terminal = ALL_OF_IT)
        check(Yield.of(doomed) == Yield.MASSIVE) { "a doomed Age's hoard read as ${Yield.of(doomed)}" }
    }

    test("the report never falls as the Age grows more dangerous") {
        val scores = (0..80).map { it / 20.0 }
        val reported = scores.map { Yield.of(danger(score = it)) }
        val slipped = reported.zipWithNext().filter { (earlier, later) -> later < earlier }
        check(slipped.isEmpty()) {
            "a dearer Age surveyed as holding less than a cheaper one: $slipped"
        }
    }

    test("every band is reachable from a score something could be written at") {
        val scores = (0..400).map { it / 20.0 }
        val reached = scores.flatMap { score ->
            listOf(danger(score = score), danger(score = score, terminal = ALL_OF_IT))
        }.map { Yield.of(it) }.toSet()
        val unreachable = Yield.entries - reached - Yield.NONE
        check(unreachable.isEmpty()) {
            "these bands describe an amount no Age can hold, so the word would never be read: $unreachable"
        }
    }
}) {
    companion object {
        private const val ALL_OF_IT = 1.0

        private fun danger(score: Double, terminal: Double = 0.0) = ScoredDanger.of(score, terminal)
    }
}
