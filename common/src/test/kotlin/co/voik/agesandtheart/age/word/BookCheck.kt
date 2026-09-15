package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.read
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.word.generation.GenerationGrammar
import co.voik.agesandtheart.age.word.grammar.Production
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * The whole pipeline, at scale, from both ends.
 *
 * **Books the Art writes** come out of the shipped `book` grammar (§4.5), and they are content: a found book
 * is a worked example a player calibrates against, so one that arrived unreadable or self-contradictory
 * would teach exactly the wrong lesson and its reader would have no way to tell. Coherence is a property of
 * *how the grammar is authored* rather than of anything code does — the alternatives an author writes hang
 * together, or they do not — which is why what checks it is a property here and not machinery there.
 *
 * **Rows of pages nobody would write** come out of the corpus at random, and they are the other half: the
 * grammar reaches a few dozen words on purpose, where the corpus is eleven hundred, and the resolver has to
 * survive all of them. Nonsense is the point — a random row exercises repair, contradiction and the two
 * failure channels, which a coherent book is written precisely to avoid.
 *
 * §4.3's **two failure channels are the sharp end**: garbling a book must cost vagueness and never
 * instability, and contradicting yourself must cost instability and never a parse error. Those are the
 * design's promise, and they are only checkable by damaging a book that was known good first.
 *
 * Offline the corpus is blocks and the authored words. Biomes and structure sets arrive with a server's
 * dynamic registries, so the populative half of the language — and with it `only`, `except` and the rungs —
 * is exercised by `scripts/checks/quantifiers.txt` rather than here.
 */
@Tags(NEEDS_REGISTRIES)
class BookCheck : FunSpec({

    val bookGrammar: GenerationGrammar by lazy {
        vocabulary.generation.grammar(BOOK_GRAMMAR) ?: error("the pack ships no '$BOOK_GRAMMAR' grammar")
    }

    /**
     * Written once and read by every property below. Generating and resolving are the whole cost of this
     * spec, and doing either per property multiplies a four-minute-loop saving away for nothing.
     */
    val written by lazy { (1L..BOOKS_DRAWN).map { seed -> seed to bookGrammar.expand(Random(seed)) } }
    val resolved by lazy {
        written.map { (seed, pages) ->
            val read = read(pages)
            Triple(seed, pages, runCatching { Resolver.resolve(vocabulary, read, seed) })
        }
    }

    /** Rows of pages drawn from the whole corpus — nonsense on purpose, and never meant to be otherwise. */
    val fuzzed by lazy {
        val sayable = vocabulary.authoredWords.map { it.name } + vocabulary.grammarWords.map { it.name }
        val derived = vocabulary.derivedWords.map { it.name }
        (1L..ROWS_FUZZED).map { seed ->
            val random = Random(seed)
            // Mostly authored, because those are the words that carry structure — subjects, joiners, rungs.
            // A row drawn evenly from the corpus would be eleven materials in a row and nothing else.
            fun page() = if (random.nextInt(DERIVED_IN) == 0) derived.random(random) else sayable.random(random)
            // The `age` page first, because a row without one is not a book and is refused before the
            // parser sees it (§4.3.1) — fuzzing those would only ever re-test the one rule that refuses.
            // Everything after it is as random as before, `age` being in `sayable` and free to recur.
            seed to (listOf(NUCLEUS_PAGE) + List(random.nextInt(1, LONGEST_ROW)) { page() })
        }
    }

    /**
     * A book the Art writes says something. The failure this guards is the quiet one — a grammar that
     * expanded to nothing would pass every property below by never testing anything.
     */
    test("a book the Art writes is a book") {
        // Two pages is the floor and it is a real one: an aiming page with nothing after it changes no
        // world, so a book at that length has spent ink to say nothing at all.
        val silent = written.filter { (_, pages) -> pages.size < 2 }
        check(silent.isEmpty()) { "${silent.size} of $BOOKS_DRAWN books said nothing: $silent" }
        // Deterministic, or a failing seed could not be reproduced — the first thing anyone will want.
        check(bookGrammar.expand(Random(1L)) == bookGrammar.expand(Random(1L))) {
            "the same seed wrote two different books"
        }
        // The nucleus is structure rather than content, so no property below would notice it missing —
        // and a found book without one is the one shape of book a player must never be taught to copy.
        val headless = written.filterNot { (_, pages) -> pages.any { vocabulary.grammarWord(it)?.production == Production.NUCLEUS } }
        check(headless.isEmpty()) { "${headless.size} books had no Age to hang on: ${headless.take(3)}" }
    }

    /**
     * **Well-formed means read whole, and read as written.** A found book is what *teaches* the grammar
     * (§4.5), so a page the Art had to move or supply is a lesson in nonsense — and repair makes that
     * invisible from `dropped` alone, since it places what it can and drops nothing.
     */
    test("every book the Art writes is read whole and needs no repair") {
        for ((seed, pages) in written) {
            val read = read(pages)
            check(read.dropped.isEmpty()) {
                "seed $seed wrote '${pages.joinToString(" ")}', and the Art could not place " +
                    read.dropped.joinToString(" ")
            }
            val supplied = read.constraints.filter { it.latent }.map { it.word.name }
            check(supplied.isEmpty()) {
                "seed $seed wrote '${pages.joinToString(" ")}', which would not parse — the Art had to " +
                    "supply ${supplied.joinToString(" ")}"
            }
        }
    }

    /**
     * And it resolves into a world without throwing, at a cost greater than nothing — a book that cost
     * nothing said nothing, whatever its page count.
     */
    test("every book the Art writes resolves") {
        for ((seed, pages, resolution) in resolved) {
            val resolved = resolution
                .getOrElse { failure -> error("seed $seed ('${pages.joinToString(" ")}') would not resolve: $failure") }
            check(resolved.cost > 0) { "seed $seed ('${pages.joinToString(" ")}') cost nothing" }
        }
    }

    /**
     * **A book the Art writes coheres**, which is what makes it content rather than noise. It is a property
     * of the grammar file: an author who puts `frozen` and `molten` in one alternative writes an exemplar
     * that argues with itself, and nothing downstream can rescue it.
     */
    test("every book the Art writes coheres") {
        for ((seed, pages, resolution) in resolved) {
            val instability = resolution.getOrThrow().instability
            check(instability.isCoherent) {
                "seed $seed wrote '${pages.joinToString(" ")}', which came out at " +
                    "$instability: ${instability.flaws.joinToString("; ")}"
            }
        }
    }

    /**
     * §4.3's first failure channel, and half the design's promise: **garbling your words makes an Age
     * vaguer.** Junk pages are dropped and reported, and the words that survived resolve exactly as they
     * did before — so a typo costs precision and never blames the writer for a contradiction they did not
     * write.
     */
    test("garbling a book costs vagueness, never instability") {
        for ((seed, pages) in written) {
            val garbled = pages + "zzzznotaword$seed"
            val read = read(garbled)
            check(read.unreadable == listOf("zzzznotaword$seed")) {
                "garbling seed $seed reported ${read.unreadable} rather than the one page nobody can read"
            }
            // The other channel must stay empty: an unreadable page is vagueness, and charging it as
            // something no sentence has room for would be the two channels collapsed into one (§4.3).
            check(read.impossible.isEmpty()) {
                "garbling seed $seed was read as an impossibility: ${read.impossible}"
            }
            val resolution = Resolver.resolve(vocabulary, read, seed)
            check(resolution.instability.isCoherent) {
                "an unreadable page made seed $seed unstable: ${resolution.instability.flaws.joinToString("; ")}"
            }
        }
    }

    /**
     * The other channel: **contradicting yourself makes an Age unstable** — and it is *not* a parse error.
     * A contradiction is semantics, which the grammar cannot see and must not try to (§4.3), so the book
     * has to be read whole and charged afterwards.
     *
     * Built from the antonym table rather than a hand-picked pair, so it keeps asking the question if the
     * vocabulary is retuned.
     *
     * **Every pair the corpus can write, not the first one** (Jonah, 2026-08-05). A spot check passed for
     * years because whichever pair it happened to find did collide; `drenched` against `arid` was the one
     * that did not, and it slipped in unnoticed under a green suite. `Register.OPPOSED` is what makes
     * sweeping the whole table satisfiable rather than aspirational — and this is the check that will
     * notice when a new dial quietly separates two words that should still disagree.
     */
    test("contradicting yourself costs instability, never a parse error") {
        fun wordAsking(tag: String) = vocabulary.words.firstOrNull { it.tier.narrows && tag in it.wanted }
        val writable = vocabulary.antonyms.mapNotNull { antonym ->
            val first = wordAsking(antonym.first) ?: return@mapNotNull null
            val second = wordAsking(antonym.second) ?: return@mapNotNull null
            first to second
        }
        check(writable.isNotEmpty()) { "the antonym table names no pair the corpus can write" }

        for (opposed in writable) {
            // The two words that disagree, then the nucleus every book must have — modifiers lead the
            // page they modify, so an unaimed book closes with `age` rather than opening on it (§4.3.1).
            val pages = listOf(opposed.first.name, opposed.second.name, nucleusPage(vocabulary))
            val written = pages.joinToString(" ")
            val read = read(pages)
            check(read.dropped.isEmpty()) {
                "'$written' was a parse error, which a contradiction must never be: ${read.dropped}"
            }
            check(read.constraints.size == 2) { "'$written' lost a word: ${read.constraints}" }
            val resolution = Resolver.resolve(vocabulary, read, SAMPLE_BOOK_SEED)
            check(!resolution.instability.isCoherent) {
                "'$written' asks for two opposed things and was charged nothing"
            }
        }
    }

    /**
     * **Any row of pages at all comes back a sentence, and resolves into a world.** Design §2: the pen never
     * refuses, and since repair replaced error recovery this is the only thing asking that at scale.
     */
    test("any row of pages at all comes back a world") {
        var repaired = 0
        var lost = 0
        for ((seed, pages) in fuzzed) {
            val read = runCatching { read(pages) }
                .getOrElse { failure -> error("seed $seed ('${pages.joinToString(" ")}') would not read: $failure") }
            runCatching { Resolver.resolve(vocabulary, read, seed) }
                .getOrElse { failure -> error("seed $seed ('${pages.joinToString(" ")}') would not resolve: $failure") }
            if (read.constraints.any { it.latent }) repaired++
            if (read.impossible.isNotEmpty()) lost++
        }
        // The instrument has to be measuring something. A fuzz that happened to draw only well-formed rows
        // would pass this and the two properties below without ever reaching repair, which is the whole
        // reason these rows are drawn at random rather than written.
        check(repaired > ROWS_FUZZED / 10) { "only $repaired of $ROWS_FUZZED rows reached repair at all" }
        check(lost > 0) { "no row of $ROWS_FUZZED held a page there was nowhere for, so that channel is untested" }
    }

    /**
     * **Nothing vanishes in silence** (§3.3), asked of rows nobody would write. A page is either laid, or
     * reported unreadable, or reported as having nowhere to go; a page that is none of those means a writer
     * gets a world their book did not describe and is told nothing about it.
     *
     * Structural pages are exempt from being *laid* — `and` speaks by joining two words that do — but not
     * from being reported, which is what the impossibility channel is for.
     */
    test("nothing vanishes from a row of pages") {
        for ((seed, pages) in fuzzed) {
            val read = read(pages)
            // **A biome consumed by a siting is used, not lost.** `in <biome>` takes the page that names
            // the place and makes it the clause's address rather than a claim of its own (§4.3.1), so it
            // is accounted for by the clause it sited rather than by any constraint.
            val sited = read.phrases.mapNotNull { it.confinedTo?.path }
            val accountedFor = read.written.map { it.word.name }.toSet() + read.dropped + sited
            val content = pages.filter { vocabulary.grammarWord(it) == null }
            val lost = content.filterNot { it in accountedFor }
            check(lost.isEmpty()) {
                "seed $seed ('${pages.joinToString(" ")}') lost ${lost.joinToString()} — neither laid nor " +
                    "reported, which is the one failure §3.3 forbids"
            }
        }
    }

    /**
     * **The same pages always read the same way.** An Age rebuilds from its recipe on every open, so a
     * reading that varied would be a different world under the same book — and repair, which draws a
     * sentence of its own, is the first thing in the pipeline that could have broken it.
     */
    test("the same pages always read the same way") {
        for ((seed, pages) in fuzzed) {
            val once = read(pages)
            val again = read(pages)
            check(once == again) {
                "seed $seed ('${pages.joinToString(" ")}') read two ways:\n  $once\n  $again"
            }
        }
    }
})

/** The page every book opens with — structure, so it comes from the grammar words rather than the corpus. */
private fun nucleusPage(vocabulary: Vocabulary): String =
    vocabulary.grammarWords.first { it.production == Production.NUCLEUS }.name

/** The one page every book must carry, without which a row is refused rather than read (§4.3.1). */
private const val NUCLEUS_PAGE = "age"

private const val BOOK_GRAMMAR = "book"

/** More seeds than the shipped grammar has distinct expansions, so every alternative is drawn. */
private const val BOOKS_DRAWN = 500L

/** Enough that a rare combination of pages turns up, quick enough to stay in the offline suite. */
private const val ROWS_FUZZED = 2000L

/** Longer than any sentence worth reading; short enough that repair's search stays cheap. */
private const val LONGEST_ROW = 9

/** One page in this many is drawn from the derived corpus, which is materials and nothing else. */
private const val DERIVED_IN = 5

private const val SAMPLE_BOOK_SEED = 20260730L
