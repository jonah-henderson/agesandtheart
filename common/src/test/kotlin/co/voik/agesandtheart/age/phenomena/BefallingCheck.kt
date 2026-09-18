package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.SHIPPED_PRICES
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Rung
import io.kotest.core.spec.style.FunSpec

/**
 * What befalls an Age, from **both** directions — what a book named, and what the Age's instability
 * inflicted on it (design §7.7).
 *
 * **The rule this pins down is that a phenomenon nobody wrote can still happen.** Instability is the way an
 * Age comes apart unpredictably, so a column of sand walking a world that never asked for one is the point
 * rather than a leak (Jonah, 2026-08-31) — and where a book *did* ask, the two compound.
 */
class BefallingCheck : FunSpec({

    /** Far enough past the top of the ladder that every rung is bought, however many there are. */
    val MOST_BROKEN = 20

    fun spendingAt(budget: Int) = Spending.of(budget, ANY_SEED, SHIPPED_PRICES)
    fun sandfall(value: String = Phenomenon.SANDFALL.key, density: Double = Rung.ORDINARY) =
        Claim(value = value, density = density)

    test("a coherent Age gets exactly what its book asked for and nothing else") {
        val befalling = Happenings.befalling(listOf(sandfall()), spendingAt(0))
        check(befalling == mapOf(Phenomenon.SANDFALL to Rung.ORDINARY)) {
            "a coherent Age was given something other than what it wrote: $befalling"
        }
    }

    test("a coherent Age that wrote nothing has nothing befall it") {
        check(Happenings.befalling(emptyList(), spendingAt(0)).isEmpty()) {
            "an Age with no book and no instability still had something happen to it"
        }
    }

    /** The headline: instability puts a phenomenon into an Age whose book never mentioned one. */
    test("a badly flawed Age gets a sandfall it never wrote") {
        val ruined = spendingAt(RUINED)
        check(Happenings.furyOf(ruined, Phenomenon.SANDFALL) > 0.0) {
            "a budget of $RUINED bought no sandfall at all, so nothing below means anything"
        }
        val befalling = Happenings.befalling(emptyList(), ruined)
        // **What it inflicted, never which ones.** The ladder gains rungs as phenomena gain
        // manifestations, so pinning the set here would make every addition a failure in a file that has
        // nothing to do with it. The rule is that instability inflicts only what it can pay for.
        check(befalling.keys.isNotEmpty()) { "a ruined Age had nothing inflicted on it at all" }
        check(befalling.keys.all { Happenings.furyOf(ruined, it) > 0.0 }) {
            "instability inflicted something it bought no fury for: ${befalling.keys}"
        }
        check(befalling[Phenomenon.SANDFALL] == Rung.ORDINARY) {
            "an inflicted sandfall did not come at an ordinary rung: $befalling"
        }
    }

    /**
     * **The rung a writer gave survives, and the fury is on top of it** — the compounding, checked from the
     * side that a naive implementation gets wrong: overwriting the written density with an ordinary one.
     */
    test("a written rung is not overwritten by an inflicted one") {
        val teeming = 4.0
        val befalling = Happenings.befalling(listOf(sandfall(density = teeming)), spendingAt(RUINED))
        check(befalling[Phenomenon.SANDFALL] == teeming) {
            "the writer's own rung was lost when instability reached the same phenomenon: $befalling"
        }
    }

    /** Nothing instability has no manifestation for is ever inflicted, however broken the Age. */
    test("instability inflicts only what it has a manifestation for") {
        val everything = spendingAt(RUINED * MOST_BROKEN)
        val inflicted = Happenings.befalling(emptyList(), everything).keys
        val inflictable = Phenomenon.entries.filter { it.inflictedBy != null }.toSet()
        // A subset rather than an equality: which phenomena are inflictable is a design question that
        // moves, and the rule that survives it is that nothing without a manifestation may arrive.
        check(inflicted.isNotEmpty()) { "the most broken Age there is had nothing inflicted on it" }
        check(inflicted.all { it in inflictable }) {
            "the most broken Age there is befell something nothing prices: ${inflicted - inflictable}"
        }
    }

    /** A claim naming nothing real is skipped rather than crashing the tick. */
    test("a claim that names no phenomenon is ignored") {
        val befalling = Happenings.befalling(listOf(sandfall(value = "not_a_phenomenon")), spendingAt(0))
        check(befalling.isEmpty()) { "a claim naming nothing became a phenomenon: $befalling" }
    }
}) {
    private companion object {
        /**
         * Enough to buy every step of everything, so the sandfall is reached whatever the draw leans
         * toward — which of the phenomena a *moderate* index reaches is the seed's to say.
         */
        const val RUINED = 10_000

        const val ANY_SEED = 7L
    }
}
