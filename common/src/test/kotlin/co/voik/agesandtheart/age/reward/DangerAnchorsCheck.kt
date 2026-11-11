package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * That the shipped table scores the Ages it was rated against about as they were rated (Jonah, 2026-10-23).
 *
 * **These are the scale's anchors** ([DangerScoring.ANCHORS]). A retune that moves one of them off its
 * rating is a retune of the scale, not of one hazard, and should be a decision rather than a side effect.
 *
 * Each is the mean over a few seeds, since words that lean draw differently on each.
 */
@Tags(NEEDS_REGISTRIES)
class DangerAnchorsCheck : FunSpec({

    test("the Ages the scale was rated against score near their ratings") {
        val scored = DangerScoring.ANCHORS.map { (sentence, rated) -> Triple(sentence, rated, meanScore(sentence)) }
        val readout = scored.joinToString("\n") { (sentence, rated, score) ->
            "  %-50s rated %.2f  scores %.2f  %s".format(sentence, rated, score, hazardsAtTheFirstSeed(sentence))
        }
        val astray = scored.filter { (_, rated, score) -> abs(score - rated) > TOLERANCE }
        check(astray.isEmpty()) {
            "${astray.size} anchor(s) scored more than $TOLERANCE from their rating:\n$readout"
        }
    }
}) {
    companion object {
        private const val TOLERANCE = 0.15
        private val SEEDS = 1L..5L

        private fun dangerOf(sentence: String, seed: Long): Danger = DangerScoring.dangerOf(sentence.split(" "), seed)

        private fun meanScore(sentence: String): Double = SEEDS.map { dangerOf(sentence, it).score }.average()

        private fun hazardsAtTheFirstSeed(sentence: String): String =
            dangerOf(sentence, SEEDS.first).hazards.joinToString { "%s %.2f".format(it.name, it.value) }
    }
}
