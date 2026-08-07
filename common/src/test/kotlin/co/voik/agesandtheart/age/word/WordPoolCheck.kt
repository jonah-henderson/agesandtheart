package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/**
 * How broad a word is allowed to be, and what breadth may never cost (§4.4).
 *
 * **A word declares a core and a pool.** Every scorched Age was the same scorched Age because `scorching`
 * named evaporation, sunburn and embers outright — three settings, no range, nothing to vary. What ought to
 * vary is not whether a broad word lands but *which of its facets* do, so the core is what makes it that
 * word and the pool is what makes this Age's version of it different from the last.
 *
 * The two properties that make it a spectrum rather than a lottery are below: the core is never in the
 * draw, and what a word *could* do never moves with one.
 */
class WordPoolCheck : FunSpec({

    fun wordAt(tier: Tier, sets: Map<String, String>, pool: Map<String, String>, draws: Int) = Word(
        id = Identifier.fromNamespaceAndPath("agesandtheart", "scorching"),
        tier = tier,
        aspects = setOf(Aspect.ATMOSPHERE),
        query = mapOf("dry" to 1.0),
        sets = sets,
        pool = pool,
        draws = draws,
    )

    val broad = wordAt(
        Tier.RESTRICTIVE,
        sets = mapOf("sunburn" to "always"),
        pool = mapOf("evaporation" to "always", "motes" to "embers", "haze" to "0.15..0.45", "murk" to "0.1..0.4"),
        draws = 2,
    )

    /**
     * **The core is never in the draw**, which is what stops a spectrum becoming a lottery: no roll may
     * produce a `scorching` Age that is not scorched.
     */
    test("every Age gets the whole core, whatever it drew") {
        for (draw in DRAWS) {
            val chosen = broad.setsDrawnAt(draw)
            check(chosen["sunburn"] == "always") { "the core went missing at draw $draw: $chosen" }
            check(chosen.size == broad.sets.size + broad.draws) {
                "drew ${chosen.size - broad.sets.size} facets rather than ${broad.draws} at $draw: $chosen"
            }
        }
    }

    /** And the pool genuinely varies, or the word has breadth on paper and none in play. */
    test("different Ages wear different facets of the same word") {
        val worn = DRAWS.map { draw -> broad.setsDrawnAt(draw).keys.sorted() }.distinct()
        check(worn.size > 1) { "every Age drew the same facets, so the pool is decoration: ${worn.first()}" }
    }

    /**
     * **Deterministic**, which the whole recipe model rests on: an Age rebuilds from its recipe on every
     * open, so a word that drew differently the second time would be a different world under one book.
     */
    test("the same Age draws the same facets every time") {
        for (draw in DRAWS) {
            check(broad.setsDrawnAt(draw) == broad.setsDrawnAt(draw)) { "the draw at $draw is not stable" }
        }
    }

    /**
     * **What a word could do never moves with a draw.** Three questions in the resolver are about a word's
     * nature rather than this Age's roll — whether it belongs in an aspect, whether it steers anything, and
     * whether the world can back it — and a word charged as unbacked because the draw missed the parameter
     * that would have landed is a writer paying for a coin they did not toss.
     */
    test("what a word can do is the same in every Age") {
        val canSet = broad.canSet
        check(canSet.keys == (broad.sets.keys + broad.pool.keys)) { "capability lost the pool: ${canSet.keys}" }
        for (draw in DRAWS) {
            val drawn = broad.copy(sets = broad.setsDrawnAt(draw))
            check(drawn.canSet.keys == canSet.keys) {
                "a drawn word forgot what it could do at $draw: ${drawn.canSet.keys} against ${canSet.keys}"
            }
        }
    }

    /** A word with no pool is exactly the word it was before any of this existed. */
    test("a word with no pool is untouched") {
        val plain = wordAt(Tier.EXACT, sets = mapOf("temperature" to "0.4..0.9"), pool = emptyMap(), draws = 0)
        for (draw in DRAWS) {
            check(plain.setsDrawnAt(draw) == plain.sets) { "a pool-less word moved at draw $draw" }
        }
        check(plain.canSet == plain.sets) { "a pool-less word can do more than it sets" }
    }
})

/** A spread of Ages, small enough to read in a failure and wide enough to shake the draw. */
private val DRAWS = listOf(1L, 2L, 3L, 7L, 42L, 20260806L, -19L, Long.MIN_VALUE)
