package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.RUNS_OCCASIONALLY_TAG
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.reading.Fate
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.reading.Writing
import co.voik.agesandtheart.age.reading.Written
import co.voik.agesandtheart.age.words.ShippedWords
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.book.FoundBookKind
import co.voik.agesandtheart.location
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * Every found book the grammars write, read in the new model (`notes/the-word-rework.md`, step 8) and judged by the rules
 * [problemsWith] states: a book that is not unstable must read whole, nothing dangling and nothing clashing. A book naming
 * the pack's own worldgen (`palm_beach`, `torchflowers`) is judged on a server, where those are words.
 */
@Tags(NEEDS_REGISTRIES)
class FoundBooksCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val writing by lazy { Writing(words, ShippedCorpus.vocabulary, WorldMembership(ShippedCorpus.vocabulary)) { false } }

    fun pagesOf(kind: FoundBookKind, seed: Long): List<String> {
        val grammar = ShippedCorpus.vocabulary.generation.grammar(kind.grammar) ?: error("the pack ships no '${kind.grammar}' grammar")
        return grammar.expand(Random(seed))
    }

    fun awaitsAServer(pages: List<String>) = pages.any(ShippedCorpus.vocabulary::awaitsAServer)

    /** The books of [seeds] this corpus can judge, each judged; one naming the pack's own worldgen waits for a server. */
    fun judgedAt(seeds: LongRange): List<JudgedBook> = FoundBookKind.entries.flatMap { kind ->
        seeds.map { seed -> kind to seed }
    }.mapNotNull { (kind, seed) ->
        val pages = pagesOf(kind, seed).takeUnless(::awaitsAServer) ?: return@mapNotNull null
        judged(kind, seed, pages, writing.written(pages, seed), words)
    }

    /** Written once and read by every property below, since writing them is the whole cost of this spec. */
    val books by lazy { judgedAt(1L..BOOKS_DRAWN) }

    test("every book the Art writes is one worth finding") {
        // A grammar gone wholly server-only would pass here by never being asked.
        check(books.isNotEmpty()) { "every found book names worldgen only a server has" }
        val problems = books.flatMap(::problemsWith)
        check(problems.isEmpty()) { "${problems.size} problems, the first of them:\n  ${problems.take(10).joinToString("\n  ")}" }
    }

    /** The same, over the many more books it takes to reach the rarer of a grammar's sentences, now and then. */
    test("every book of a long draw is one worth finding").config(tags = setOf(RUNS_OCCASIONALLY_TAG)) {
        val problems = judgedAt(BOOKS_DRAWN + 1..BOOKS_DRAWN_BEFORE_A_RELEASE).flatMap(::problemsWith)
        check(problems.isEmpty()) { "${problems.size} problems, the first of them:\n  ${problems.take(10).joinToString("\n  ")}" }
    }

    /** Nothing else teaches the grammar, so between them the advanced books must use every structure page there is. */
    test("the advanced books teach every structure page") {
        val written = books.filter { it.kind == FoundBookKind.ADVANCED }.flatMap { it.pages }.toSet()
        val structurePages = words.all.filter { it.defaultSense?.behaviour != null }.map(words::pageFor)
        val untaught = structurePages.filterNot { it in written }
        check(untaught.isEmpty()) { "no advanced book teaches ${untaught.joinToString()}" }
    }

    /** Deterministic, or a failing seed could not be reproduced — the first thing anyone will want. */
    test("the same seed writes and reads the same book") {
        for (kind in FoundBookKind.entries) {
            val pages = pagesOf(kind, 1L)
            check(pages == pagesOf(kind, 1L)) { "the same seed wrote two different ${kind.key} books" }
            check(writing.written(pages, 1L) == writing.written(pages, 1L)) { "the same seed read the ${kind.key} book two ways" }
        }
    }
})

/** [pages], written as a [kind] book at [seed], as the facts [problemsWith] judges. */
private fun judged(kind: FoundBookKind, seed: Long, pages: List<String>, written: Written, words: Words): JudgedBook {
    fun behaviourOf(page: String) = words.word(page)?.defaultSense?.behaviour?.path
    fun isAQuantifier(page: String): Boolean {
        val offers = words.word(page)?.senses.orEmpty().flatMap { it.offers }
        return offers.isNotEmpty() && offers.all { it.concept == AMOUNT }
    }
    fun isAModifier(page: String): Boolean {
        val behaviour = behaviourOf(page)
        return (behaviour != null && behaviour != AGE) || isAQuantifier(page)
    }
    val unread = written.reading.pages.filter { it.fate == Fate.Unknown }.map { it.written }
    // A page of an unstable book overruled by the one it contradicts dangles, which is what an unstable book shows.
    val dangling = if (kind.isCoherent) written.drawn.dangling.map { it.written } else emptyList()
    val instability = written.resolution.instability
    return JudgedBook(
        kind = kind,
        seed = seed,
        pages = pages,
        unplaced = unread + dangling,
        supplied = emptyList(),
        modifiers = pages.filter(::isAModifier),
        hasAnAge = pages.any { behaviourOf(it) == AGE },
        cost = written.resolution.cost,
        instability = instability.index,
        flaws = instability.flaws.map { it.toString() },
        failure = null,
        rewards = emptyList(),
    )
}

private const val AGE = "age"
private val AMOUNT = "amount".location()
/** Enough to draw every common sentence of each grammar, in the loop; the rarer ones wait for `occasionalTest`. */
private const val BOOKS_DRAWN = 500L

/** Enough that every sentence of each grammar is drawn many times. */
private const val BOOKS_DRAWN_BEFORE_A_RELEASE = 20_000L
