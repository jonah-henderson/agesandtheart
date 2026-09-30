package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.phenomena.Tide
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **The tide is the moon's, and `tidal` is a puzzle** (design §7.1.2): aimed at the moon it pulls, and
 * anywhere else — laid bare included — it is only the shore.
 */
@Tags(NEEDS_REGISTRIES)
class TidalCheck : FunSpec({

    fun pulls(vararg pages: String) = SEEDS.map { seed ->
        Tide.pullsIn(resolved(seed, *pages).composition, seed)
    }

    test("a tidal moon pulls") {
        check(pulls("tidal", "moon").all(Tide::isTidal)) { "a tidal moon did not pull: ${pulls("tidal", "moon")}" }
    }

    test("laid bare or on the sea, tidal raises no tide") {
        check(pulls("tidal", "age").none(Tide::isTidal)) { "a tidal Age pulled: ${pulls("tidal", "age")}" }
        check(pulls("tidal", "sea").none(Tide::isTidal)) { "a tidal sea pulled: ${pulls("tidal", "sea")}" }
    }

    test("a pull with no moon to pull with is a contradiction") {
        val flaws = resolved(SEEDS.first(), "tidal", "moonless", "moon").instability.flaws
        check(flaws.any { it.register == Register.TENSION && "tidal" in it.words }) {
            "a tidal moon in a moonless Age went uncharged: $flaws"
        }
    }
}) {
    private companion object {
        val SEEDS = listOf(1L, 2L, 3L, 4L)
    }
}
