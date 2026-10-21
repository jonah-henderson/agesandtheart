package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.read
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.word.generation.GenerationGrammar
import co.voik.agesandtheart.age.language.Fate
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.language.Word
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

    fun grammarFor(kind: FoundBookKind): GenerationGrammar =
        vocabulary.generation.grammar(kind.grammar) ?: error("the pack ships no '${kind.grammar}' grammar")

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
        val sayable = vocabulary.authoredWords.map { it.name } + vocabulary.structurePages.map { it.name }
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

    /** Deterministic, or a failing seed could not be reproduced — the first thing anyone will want. */
    /** Nothing else teaches the grammar, so between them the advanced books must use every structure page there is. */
    test("the advanced books teach every structure page") {
        val written = drafts.filter { it.kind == FoundBookKind.ADVANCED }.flatMap { it.pages }.toSet()
        val untaught = vocabulary.structurePages.map { it.name }.filterNot { it in written }
        check(untaught.isEmpty()) { "no advanced book teaches $untaught" }
    }

    test("the same seed writes the same book") {
        for (kind in FoundBookKind.entries) {
            val grammar = grammarFor(kind)
            check(grammar.expand(Random(1L)) == grammar.expand(Random(1L))) {
                "the same seed wrote two different ${kind.key} books"
            }
        }
    }

    /**
     * **Every book the Art writes is a good found book** — read whole, resolved, coherent unless it is
     * meant not to be, and teaching a modifier if it is advanced. `FoundBookRules` is what that means, and
     * the server's half of this check asks the same of the books only a server can read.
     */
    test("every book the Art writes is one worth finding") {
        // The instrument has to be measuring something: a grammar that had gone wholly server-only would
        // pass here by never being asked.
        check(judgeable.isNotEmpty()) { "every found book names worldgen only a server has" }
        val problems = judgeable.flatMap { draft -> problemsWith(JudgedBook.of(draft)) }
        check(problems.isEmpty()) { "${problems.size} problems, the first of them:\n  ${problems.take(10).joinToString("\n  ")}" }
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
            val read = read(book.pages + junk, book.seed)
            check(read.unread.map { it.written } == listOf(junk)) {
                "garbling $book reported ${read.unread} rather than the one page nobody can read"
            }
            // The other channel must stay empty: an unreadable page is vagueness, and charging it as
            // something no sentence has room for would be the two channels collapsed into one (§4.3).
            check(read.impossible.isEmpty()) {
                "garbling $book was read as an impossibility: ${read.impossible}"
            }
            // Against the book as written rather than against nothing, since an unstable one starts flawed.
            val garbled = vocabulary.resolve(read, book.seed).instability
            // The flaws, not the whole instability: the garbling page is one more page laid.
            check(garbled.flaws == book.instability?.flaws) {
                "an unreadable page changed $book from ${book.instability} to $garbled: ${garbled.flaws.joinToString("; ")}"
            }
        }
    }

    /**
     * The other channel: **contradicting yourself makes an Age unstable** — and it is *not* a parse error.
     * A contradiction is semantics, which the grammar cannot see and must not try to (§4.3), so the book
     * has to be read whole and charged afterwards.
     *
     * Built from the opposed-tags table rather than a hand-picked pair, so it keeps asking the question if the
     * vocabulary is retuned.
     *
     * **Every pair the corpus can write, not the first one** (Jonah, 2026-08-05). A spot check passed for
     * years because whichever pair it happened to find did collide. Words that never share a place never clash
     * (`the-world-model.md` §8), so each pair is written by two words wanting its two tags in one part of the
     * world.
     */
    test("contradicting yourself costs instability, never a parse error") {
        fun wantedWhere(word: Word, tag: String) = word.effects.filterIsInstance<Effect.Restricts>()
            .filter { restricts -> restricts.bars[tag]?.let(Bars::isAtLeast) == true }
            .map { it.aspect }.toSet()
        fun wordsWanting(first: String, second: String): Pair<Word, Word>? {
            for (one in vocabulary.words) {
                val places = wantedWhere(one, first).ifEmpty { continue }
                val other = vocabulary.words.firstOrNull { it != one && wantedWhere(it, second).any(places::contains) }
                if (other != null) return one to other
            }
            return null
        }
        val writable = vocabulary.opposedTags.mapNotNull { pair -> wordsWanting(pair.first, pair.second) }
        check(writable.isNotEmpty()) { "the opposed-tags table names no pair the corpus can write" }

        for (opposed in writable) {
            // The two words that disagree laid bare, then the `age` page that takes them.
            val pages = listOf(opposed.first.name, opposed.second.name, NUCLEUS_PAGE)
            val written = pages.joinToString(" ")
            val read = read(pages)
            check(read.readsAsLaid) { "'$written' was read as something other than laid:\n${read.readout}" }
            val landed = read.possible.map { it.page }.toSet()
            check(landed == setOf(0, 1)) { "'$written' lost a word:\n${read.readout}" }
            val resolution = vocabulary.resolve(read, SAMPLE_BOOK_SEED)
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
            runCatching { vocabulary.resolve(read, seed) }
                .getOrElse { failure -> error("seed $seed ('${pages.joinToString(" ")}') would not resolve: $failure") }
            if (read.moved.isNotEmpty()) repaired++
            if (read.impossible.isNotEmpty()) lost++
        }
        // The instrument has to be measuring something. A fuzz that happened to draw only well-formed rows
        // would pass this and the two properties below without ever reaching repair, which is the whole
        // reason these rows are drawn at random rather than written.
        check(repaired > ROWS_FUZZED / 10) { "only $repaired of $ROWS_FUZZED rows reached repair at all" }
        check(lost > 0) { "no row of $ROWS_FUZZED held a page there was nowhere for, so that channel is untested" }
    }

    /**
     * **Nothing vanishes in silence** (§3.3), asked of rows nobody would write. Every page a word was laid, so it
     * could land somewhere, or it is reported unread, impossible or moved. A word laid that could land nowhere means a
     * writer gets a world their book did not describe and is told nothing about it.
     */
    test("nothing vanishes from a row of pages") {
        // A moved page is reported, charged as rehomed, wherever it then lands.
        fun mustLand(fate: Fate) = when (fate) {
            is Fate.TakenBy, Fate.LaidBare, Fate.AssertedAlone, Fate.Banned, Fate.Anchors -> true
            is Fate.Took, is Fate.Structural, Fate.Unread, Fate.Impossible, Fate.Sited, Fate.Moved -> false
        }
        for ((seed, pages) in fuzzed) {
            val read = read(pages)
            val couldLand = read.possible.map { it.page }.toSet()
            // A template's word lands as the world the book starts from, which is the reading's and no landing's.
            fun namesTheTemplate(written: String) =
                vocabulary.word(written)?.effects?.any { it is Effect.Template && it.name == read.template } == true
            val lost = read.pages.filter { mustLand(it.fate) && it.index !in couldLand && !namesTheTemplate(it.written) }
            check(lost.isEmpty()) {
                "seed $seed ('${pages.joinToString(" ")}') lost ${lost.joinToString { it.written }} — laid and landing " +
                    "nowhere, which is the one failure §3.3 forbids:\n${read.readout}"
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

/** The one page every book must carry, without which a row is refused rather than read (§4.3.1). */
private const val NUCLEUS_PAGE = "age"

/** More seeds than any shipped book grammar has distinct expansions, so every alternative is drawn. */
private const val BOOKS_DRAWN = 500L

/** Enough that a rare combination of pages turns up, quick enough to stay in the offline suite. */
private const val ROWS_FUZZED = 2000L

/** Longer than any sentence worth reading; short enough that repair's search stays cheap. */
private const val LONGEST_ROW = 9

/** One page in this many is drawn from the derived corpus, which is materials and nothing else. */
private const val DERIVED_IN = 5

private const val SAMPLE_BOOK_SEED = 20260730L
