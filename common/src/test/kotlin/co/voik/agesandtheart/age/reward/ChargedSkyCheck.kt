package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Spending
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **That the word written for the charged sky actually reaches the gate that pays for it.**
 *
 * `electromagnetic` exists for one purpose: to make an Age the kind that puts ore up in its sky. Nothing
 * connects the two but a pair of thresholds, and both halves are easy to move for good reasons — the word
 * is a set of dials in a datapack, the gate is a pair of numbers against the spec — so the day they stop
 * meeting, nothing else fails. A writer would simply write the word and look up at an empty sky.
 *
 * Read across several seeds, because both halves are ranges: a word that cleared the gate on the seed
 * somebody happened to test with is the failure this is shaped to catch.
 */
@Tags(NEEDS_REGISTRIES)
class ChargedSkyCheck : FunSpec({

    fun composed(seed: Long, vararg pages: String): AgeComposition = resolved(seed, *pages).composition

    fun charges(seed: Long, vararg pages: String): Boolean =
        EarlyGameRareMaterials.growsArcCrystal(composed(seed, *pages), seed, Spending.NOTHING, emptyMap())

    /**
     * **The sentence a writer is meant to write**, and it says the storms outright.
     *
     * `electromagnetic` admits the two phenomena and leans on both, but admitting is not claiming — the
     * word's own note says a writer who wants the tempests guaranteed writes for them, and this is that
     * sentence rather than a hope about the other one.
     */
    test("a tempestuous electromagnetic Age is charged") {
        for (seed in SEEDS) {
            check(charges(seed, "electromagnetic", "tempests", "weather")) {
                "`electromagnetic tempests weather` put no ore in the sky at seed $seed"
            }
        }
    }

    /** And the storms on their own are not it, or the word this is all named after would be decorative. */
    test("tempests alone are not a charged sky") {
        for (seed in SEEDS) {
            check(!charges(seed, "tempests", "weather")) {
                "a plain tempest Age counted as charged at seed $seed"
            }
        }
    }
}) {
    private companion object {
        /** Both halves of the gate are ranges, so one seed proves nothing about the next. */
        private val SEEDS = listOf(1L, 4242L, 90210L, -77L, 1_234_567L)
    }
}
