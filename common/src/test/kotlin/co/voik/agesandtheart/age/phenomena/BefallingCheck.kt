package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
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

    val shipped = mapOf(
        Manifestation.TORN_SEAMS to Price(costs = 2, most = 4),
        Manifestation.WOUNDS to Price(costs = 5, most = 4),
        Manifestation.SANDFALL to Price(costs = 7, most = 4),
        Manifestation.WORSENING_WOUNDS to Price(costs = 9, most = 3),
        Manifestation.COLLAPSE to Price(costs = 14, most = 3),
    )

    fun spendingAt(budget: Int) = Spending.of(budget, shipped, 1L)
    fun sandfall(value: String = Phenomenon.SANDFALL.key, density: Double = Rung.ORDINARY) =
        Claim(value = value, density = density)

    test("a coherent Age gets exactly what its book asked for and nothing else") {
        val befalling = Happenings.befalling(listOf(sandfall()), spendingAt(0), shipped)
        check(befalling == mapOf(Phenomenon.SANDFALL to Rung.ORDINARY)) {
            "a coherent Age was given something other than what it wrote: $befalling"
        }
    }

    test("a coherent Age that wrote nothing has nothing befall it") {
        check(Happenings.befalling(emptyList(), spendingAt(0), shipped).isEmpty()) {
            "an Age with no book and no instability still had something happen to it"
        }
    }

    /** The headline: instability puts a phenomenon into an Age whose book never mentioned one. */
    test("a badly flawed Age gets a sandfall it never wrote") {
        val ruined = spendingAt(RUINED)
        check(Happenings.furyOf(ruined, shipped, Phenomenon.SANDFALL) > 0.0) {
            "a budget of $RUINED bought no sandfall at all, so nothing below means anything"
        }
        val befalling = Happenings.befalling(emptyList(), ruined, shipped)
        check(befalling.keys == setOf(Phenomenon.SANDFALL)) {
            "instability inflicted the wrong set on an Age that wrote nothing: ${befalling.keys}"
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
        val befalling = Happenings.befalling(listOf(sandfall(density = teeming)), spendingAt(RUINED), shipped)
        check(befalling[Phenomenon.SANDFALL] == teeming) {
            "the writer's own rung was lost when instability reached the same phenomenon: $befalling"
        }
    }

    /** Nothing instability has no manifestation for is ever inflicted, however broken the Age. */
    test("instability inflicts only what it has a manifestation for") {
        val everything = spendingAt(EVERYTHING)
        for (phenomenon in Phenomenon.entries.filter { it.inflictedBy == null }) {
            check(Happenings.furyOf(everything, shipped, phenomenon) == 0.0) {
                "$phenomenon was inflicted with no manifestation to buy it"
            }
        }
        check(Happenings.befalling(emptyList(), everything, shipped).keys == setOf(Phenomenon.SANDFALL)) {
            "the most broken Age there is befell something nothing prices"
        }
    }

    /** A claim naming nothing real is skipped rather than crashing the tick. */
    test("a claim that names no phenomenon is ignored") {
        val befalling = Happenings.befalling(listOf(sandfall(value = "not_a_phenomenon")), spendingAt(0), shipped)
        check(befalling.isEmpty()) { "a claim naming nothing became a phenomenon: $befalling" }
    }
}) {
    private companion object {
        /** Enough to reach the sandfall: seams and wounds fill at 28, and a step of sandfall is 7 more. */
        const val RUINED = 35

        /** Past the price of everything on the list. */
        const val EVERYTHING = 200
    }
}
