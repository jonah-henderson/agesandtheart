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

    fun wordAt(tier: Tier, sets: Map<String, String>, pool: Map<String, String>, draws: String) = Word(
        id = Identifier.fromNamespaceAndPath("agesandtheart", "scorching"),
        tier = tier,
        aspects = setOf(Aspect.AIR),
        leansEverywhere = mapOf("#dry" to 1.0),
        sets = sets,
        pools = if (pool.isEmpty()) emptyList() else listOf(Pool(pool, Draws(draws))),
    )

    val broad = wordAt(
        Tier.RESTRICTIVE,
        sets = mapOf("sunburn" to "always"),
        pool = mapOf("evaporation" to "true", "motes" to "embers", "haze" to "0.15..0.45", "murk" to "0.1..0.4"),
        draws = "2",
    )

    /**
     * **How many, and the three ways of saying it.** A number is that many, a range is any of them, and a
     * repeat is a heavier chance — which is how a count is weighted without a distribution needing a name.
     */
    test("a count reads as every number it might be") {
        check(Draws.read("2") == listOf(2)) { "a plain count stopped being one" }
        check(Draws.read("1..3") == listOf(1, 2, 3)) { "a range did not span" }
        check(Draws.read("0..1") == listOf(0, 1)) { "a pool may draw nothing sometimes" }
        check(Draws.read("1|2|2") == listOf(1, 2, 2)) { "a repeat is a chance, not a duplicate" }
        check(Draws.read("1..2|3") == listOf(1, 2, 3)) { "a range and a listing do not compose" }
        for (nonsense in listOf("", "two", "-1", "3..1", "1..")) {
            check(Draws.read(nonsense) == null) { "'$nonsense' was read as a count" }
        }
        // A trailing separator is tolerated, as it is in a parameter's own `red|blue|` — being stricter
        // here than the `|` a writer already knows would be an inconsistency with nothing behind it.
        check(Draws.read("1|") == listOf(1)) { "a trailing separator stopped being harmless" }
    }

    /**
     * A ranged count actually varies between Ages, and a weighted one leans — asserted over the draw
     * rather than over the parsing, because the parsing is only half of what could be wrong.
     */
    test("a ranged count varies and a weighted one leans") {
        val ranged = wordAt(
            Tier.RESTRICTIVE,
            sets = emptyMap(),
            pool = mapOf("a" to "1", "b" to "2", "c" to "3"),
            draws = "1..3",
        )
        val sizes = DRAWS.map { ranged.setsDrawnAt(it).size }.toSet()
        check(sizes == setOf(1, 2, 3)) { "a 1..3 pool only ever drew $sizes" }

        val leaning = ranged.copy(pools = listOf(Pool(ranged.pools.single().facets, Draws("1|3|3|3"))))
        val threes = DRAWS.count { leaning.setsDrawnAt(it).size == 3 }
        check(threes > DRAWS.count() / 2) { "'1|3|3|3' drew three only $threes times in ${DRAWS.count()}" }
    }

    /**
     * **The core is never in the draw**, which is what stops a spectrum becoming a lottery: no roll may
     * produce a `scorching` Age that is not scorched.
     */
    test("every Age gets the whole core, whatever it drew") {
        for (draw in DRAWS) {
            val chosen = broad.setsDrawnAt(draw)
            check(chosen["sunburn"] == "always") { "the core went missing at draw $draw: $chosen" }
            val many = broad.pools.single().draws.most
            check(chosen.size == broad.sets.size + many) {
                "drew ${chosen.size - broad.sets.size} facets rather than $many at $draw: $chosen"
            }
        }
    }

    /** And the pool genuinely varies, or the word has breadth on paper and none in play. */
    test("different Ages wear different facets of the same word") {
        val worn = DRAWS.map { draw -> broad.setsDrawnAt(draw).keys.sorted() }.distinct()
        check(worn.size > 1) { "every Age drew the same facets, so the pool is decoration: ${worn.first()}" }
        // Adjacent seeds on their own, for the reason given at NEIGHBOURING.
        val nearby = NEIGHBOURING.map { draw -> broad.setsDrawnAt(draw).keys.sorted() }.distinct()
        check(nearby.size > 1) { "Ages a seed apart drew identical facets: ${nearby.first()}" }
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
        check(canSet.keys == broad.required.everything.keys) { "capability lost the pool: ${canSet.keys}" }
        for (draw in DRAWS) {
            val drawn = broad.copy(sets = broad.setsDrawnAt(draw))
            check(drawn.canSet.keys == canSet.keys) {
                "a drawn word forgot what it could do at $draw: ${drawn.canSet.keys} against ${canSet.keys}"
            }
        }
    }

    /**
     * **Alternatives choose the value where the pool chooses the parameter**, and the two compose: a word
     * may offer three skies and take one, offer five facets and wear two, or both.
     */
    test("a value offering alternatives picks one, and not always the same one") {
        // Shaped like the word that broke: alternatives sitting *inside a pool*, so the roll that decides
        // whether the facet appears and the roll that decides which value it takes are drawn from related
        // seeds. Tested apart, each looked fine; together they handed fourteen consecutive Ages one answer.
        val offering = wordAt(
            Tier.RESTRICTIVE,
            sets = mapOf("sunburn" to "always", "temperature" to "0.55..1.0"),
            pool = mapOf(
                "evaporation" to "true",
                "motes" to "embers|flames|ash",
                "haze" to "0.15..0.45",
                "murk" to "0.1..0.4",
                "humidity" to "-0.8..-0.2",
            ),
            draws = "2",
        )
        val worn = NEIGHBOURING.mapNotNull { draw -> offering.setsDrawnAt(draw)["motes"] }
        check(worn.isNotEmpty()) { "the fixture never drew the facet under test" }
        check(worn.all { it in listOf("embers", "flames", "ash") }) { "an alternative escaped un-chosen: $worn" }
        check(worn.distinct().size > 1) { "Ages a seed apart all took the same alternative: $worn" }
    }

    /** A word with no pool is exactly the word it was before any of this existed. */
    test("a word with no pool is untouched") {
        val plain = wordAt(Tier.EXACT, sets = mapOf("temperature" to "0.4..0.9"), pool = emptyMap(), draws = "0")
        for (draw in DRAWS) {
            check(plain.setsDrawnAt(draw) == plain.sets) { "a pool-less word moved at draw $draw" }
        }
        check(plain.canSet == plain.sets) { "a pool-less word can do more than it sets" }
    }
})

/**
 * Ages written a seed apart, **asserted on by themselves and built the way the resolver builds them**.
 *
 * This case has now hidden the same bug three times, and each fixture that missed it was closer than the
 * last. A resolver draw is `seed xor saltOf(sentence)` — consecutive *seeds* xored with one constant — and
 * that is not the same set of numbers as consecutive draws, nor as small integers. `31..44` varied
 * perfectly under a mix that gave fourteen consecutive real Ages one answer, and so did a large base plus
 * `0..13`. Only the actual shape reproduces it.
 *
 * The salt is lifted from an observed run so the arithmetic here is the arithmetic there.
 */
private const val ONE_SENTENCE_SALT = -5_114_719_280_684_135_704L

private val NEIGHBOURING = (31L..44L).map { seed -> seed xor ONE_SENTENCE_SALT }

/** A spread of Ages, small enough to read in a failure and wide enough to shake the draw. */
private val DRAWS = listOf(1L, 2L, 3L, 7L, 42L, 20260806L, -19L, Long.MIN_VALUE) + NEIGHBOURING
