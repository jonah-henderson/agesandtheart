package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.ShippedCorpus.written
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.reading.Fate
import co.voik.agesandtheart.age.reading.Trace
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.book.FoundBookDraft
import co.voik.agesandtheart.book.FoundBookKind
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/**
 * The whole pipeline, at scale, from both ends.
 *
 * **Books the Art writes** come out of the shipped grammar for each [FoundBookKind] (§4.5), and they are content: a found book
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
 * Offline the corpus carries vanilla's worldgen, so biomes, structure sets and vanilla's placed features are
 * all words here, and `in`, `only`, `except` and the rungs over them are checked like anything else. What it
 * lacks is the pack's own worldgen — `torchflowers`, `algae` — which only a server loads.
 */
@Tags(NEEDS_REGISTRIES)
class BookCheck : FunSpec({

    /**
     * Written once and read by every property below. Generating and resolving are the whole cost of this
     * spec, and doing either per property multiplies a four-minute-loop saving away for nothing.
     */
    val drafts by lazy {
        FoundBookKind.entries.flatMap { kind ->
            (1L..BOOKS_DRAWN).map { seed ->
                FoundBookDraft.drawn(vocabulary, kind, seed) ?: error("the pack ships no '${kind.grammar}' grammar")
            }
        }
    }

    /**
     * The books this corpus can judge. One naming the pack's own worldgen is judged by `BookOnServerCheck`
     * instead, since offline those pages are not words at all.
     */
    val judgeable by lazy { drafts.filterNot { draft -> draft.pages.any(vocabulary::awaitsAServer) } }

    /** Rows of pages drawn from the whole corpus — nonsense on purpose, and never meant to be otherwise. */
    val fuzzed by lazy {
        val sayable = vocabulary.authoredWords.map { it.name }
        val derived = vocabulary.derivedWords.map { it.name }
        (1L..ROWS_FUZZED).map { seed ->
            val random = Random(seed)
            // Mostly ours, because those are the words that carry structure — things, joiners, rungs. A row drawn
            // evenly from the corpus would be eleven materials in a row and nothing else.
            fun page() = if (random.nextInt(DERIVED_IN) == 0) derived.random(random) else sayable.random(random)
            // The `age` page first, because a row without one is not a book (design §2) — fuzzing those would only
            // ever re-test the one rule that refuses. Everything after it is as random, `age` free to recur.
            seed to (listOf(NUCLEUS_PAGE) + List(random.nextInt(1, LONGEST_ROW)) { page() })
        }
    }

    /**
     * §4.3's first failure channel, and half the design's promise: **garbling your words makes an Age
     * vaguer.** Junk pages are dropped and reported, and the words that survived resolve exactly as they
     * did before — so a typo costs precision and never blames the writer for a contradiction they did not
     * write.
     */
    test("garbling a book costs vagueness, never instability") {
        for (book in judgeable) {
            val junk = "zzzznotaword${book.seed}"
            val garbled = written(book.pages + junk, book.seed)
            val unread = garbled.reading.pages.filter { it.fate == Fate.Unknown }.map { it.written }
            check(unread == listOf(junk)) { "garbling $book reported $unread rather than the one page nobody can read" }
            // Against the book as written rather than against nothing, since an unstable one starts flawed.
            val flaws = garbled.resolution.instability.flaws
            check(flaws == book.instability?.flaws) {
                "an unreadable page changed $book from ${book.instability} to ${garbled.resolution.instability}: ${flaws.joinToString("; ")}"
            }
        }
    }

    /**
     * The other channel: **contradicting yourself makes an Age unstable** — and it is *not* a parse error.
     * A contradiction is semantics, which the grammar cannot see and must not try to (§4.3), so the book
     * has to be read whole and charged afterwards.
     *
     * Built from the opposed-tags table rather than a hand-picked pair, so it keeps asking the question if the
     * vocabulary is retuned. **Every pair the corpus can write, not the first one** (Jonah, 2026-08-05): words that
     * never share a place never clash (`the-world-model.md` §8), so each pair is written by two words wanting its two
     * tags in one part of the world, said of the whole Age.
     */
    test("contradicting yourself costs instability, never a parse error") {
        val wanting = vocabulary.authoredWords.filterNot(vocabulary::isAStructureWord).associateWith { word ->
            written(listOf(word.name, NUCLEUS_PAGE), SAMPLE_BOOK_SEED).drawn.possible.flatMap { landing ->
                landing.effects.filterIsInstance<Effect.Restricts>().flatMap { restricts ->
                    restricts.bars.filterValues(Bars::isAtLeast).keys.map { tag -> tag to restricts.aspect }
                }
            }.toSet()
        }
        fun wordsWanting(first: String, second: String): Pair<Word, Word>? {
            for ((one, wants) in wanting) {
                val places = wants.filter { it.first == first }.map { it.second }.toSet().ifEmpty { continue }
                val other = wanting.entries.firstOrNull { (word, theirs) -> word != one && theirs.any { it.first == second && it.second in places } }
                if (other != null) return one to other.key
            }
            return null
        }
        val writable = vocabulary.opposedTags.mapNotNull { pair -> wordsWanting(pair.first, pair.second) }
        check(writable.isNotEmpty()) { "the opposed-tags table names no pair the corpus can write" }

        for (opposed in writable) {
            val pages = listOf(opposed.first.name, opposed.second.name, NUCLEUS_PAGE)
            val said = pages.joinToString(" ")
            val book = written(pages, SAMPLE_BOOK_SEED)
            check(book.reading.readsAsLaid) { "'$said' was read as something other than laid:\n${Trace.of(book.reading, book.drawn).joinToString("\n")}" }
            check(!book.resolution.instability.isCoherent) { "'$said' asks for two opposed things and was charged nothing" }
        }
    }

    /**
     * **Any row of pages at all comes back a sentence, and resolves into a world.** Design §2: the pen never
     * refuses, and nothing asks it at scale but this.
     */
    test("any row of pages at all comes back a world") {
        var drawnOnto = 0
        var nowhere = 0
        for ((seed, pages) in fuzzed) {
            val book = runCatching { written(pages, seed) }
                .getOrElse { failure -> error("seed $seed ('${pages.joinToString(" ")}') would not be written: $failure") }
            if (book.drawn.dangling.any { it.drawnOnto != null }) drawnOnto++
            if (book.drawn.dangling.any { it.drawnOnto == null }) nowhere++
        }
        // The instrument has to be measuring something: a fuzz that drew only well-formed rows would pass without
        // ever reaching a dangling page, which is the whole reason these rows are drawn at random.
        check(drawnOnto > ROWS_FUZZED / 10) { "only $drawnOnto of $ROWS_FUZZED rows drew a dangling page onto a word" }
        check(nowhere > 0) { "no row of $ROWS_FUZZED held a page there was nowhere for, so that channel is untested" }
    }

    /**
     * **Nothing vanishes in silence** (§3.3), asked of rows nobody would write: every page the reader says something
     * took was placed somewhere — as an offer, carried inside one, or reshaping one — so none is counted taken and lost.
     */
    test("nothing vanishes from a row of pages") {
        for ((seed, pages) in fuzzed) {
            val reading = written(pages, seed).reading
            val placedSomewhere = reading.placements.flatMap { listOf(it.from) + it.carrying }.toSet()
            val lost = reading.pages.filter { it.fate == Fate.Placed && it.index !in placedSomewhere }
            check(lost.isEmpty()) {
                "seed $seed ('${pages.joinToString(" ")}') counted ${lost.joinToString { it.written }} taken and placed it nowhere"
            }
        }
    }

    /**
     * **The same pages always read the same way.** An Age rebuilds from its recipe on every open, so a
     * reading that varied would be a different world under the same book.
     */
    test("the same pages always read the same way") {
        for ((seed, pages) in fuzzed) {
            check(written(pages, seed) == written(pages, seed)) { "seed $seed ('${pages.joinToString(" ")}') read two ways" }
        }
    }
})

/** The one page every book must carry, without which a row is refused rather than read (design §2). */
private const val NUCLEUS_PAGE = "age"

/** Enough to draw every common sentence of each grammar, in the loop; the rarer ones wait for `occasionalTest`. */
private const val BOOKS_DRAWN = 500L


/** Enough that a rare combination of pages turns up, quick enough to stay in the offline suite. */
private const val ROWS_FUZZED = 2000L

/** Longer than any sentence worth reading; short enough that repair's search stays cheap. */
private const val LONGEST_ROW = 9

/** One page in this many is drawn from the derived corpus, which is materials and nothing else. */
private const val DERIVED_IN = 5

private const val SAMPLE_BOOK_SEED = 20260730L
