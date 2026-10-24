package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.Ladder
import co.voik.agesandtheart.age.Manifestation
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * That the shipped table scores the Ages it was rated against about as they were rated (Jonah, 2026-10-23).
 *
 * **These are the scale's anchors**: the Nether and the End in the Art's own words, the plainest Age for each
 * rare material, and the combinations that showed how hazards add. A retune that moves one of them off its
 * rating is a retune of the scale, not of one hazard, and should be a decision rather than a side effect.
 *
 * Each is the mean over a few seeds, since words that lean draw differently on each.
 */
@Tags(NEEDS_REGISTRIES)
class DangerAnchorsCheck : FunSpec({

    test("the Ages the scale was rated against score near their ratings") {
        val scored = ANCHORS.map { (sentence, rated) -> Triple(sentence, rated, meanScore(sentence)) }
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

        /** Each Age as a writer writes it, and what it was rated. */
        private val ANCHORS = listOf(
            "infernal age" to 0.5,
            "dark_void age" to 0.45,
            "age frozen climate blizzard phenomena" to 0.4,
            "age scorching climate volcanic features" to 0.4,
            "age tempest phenomena green aurora" to 0.05,
            "age deep sea deluge phenomena" to 0.6,
            "age meteors phenomena" to 0.65,
            "age monsters lightless sky" to 0.55,
            "age teeming monsters" to 2.0,
            "age frozen climate blizzard meteors phenomena" to 1.0,
            "age lava sea monsters inferno phenomena" to 0.75,
            "age floating landmass meteors phenomena" to 1.0,
            "desolate age" to 0.2,
        )

        init {
            MinecraftRegistries.ensureStoodUp()
        }

        private val table by lazy { DangerTable.load(MinecraftRegistries.shippedData()) }
        private val ladders = Manifestation.entries.associateWith(Ladder::ordinaryFor)

        private fun dangerOf(sentence: String, seed: Long): Danger {
            val resolution = ShippedCorpus.resolved(seed, *sentence.split(" ").toTypedArray())
            return Danger.of(resolution.composition, resolution.instability, seed, table, ladders)
        }

        private fun meanScore(sentence: String): Double = SEEDS.map { dangerOf(sentence, it).score }.average()

        private fun hazardsAtTheFirstSeed(sentence: String): String =
            dangerOf(sentence, SEEDS.first).hazards.joinToString { "%s %.2f".format(it.name, it.value) }
    }
}
