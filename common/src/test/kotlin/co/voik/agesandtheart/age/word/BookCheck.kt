package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.word.grammar.Grammar
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The whole pipeline, fuzzed through the one thing that can feed it at scale (design §4.5). A generated
 * book is written by the same vocabulary a player writes with, so what these properties actually ask is
 * whether *any* well-formed book survives the parser and the resolver — thousands of them, rather than the
 * dozen anyone would think to write by hand.
 *
 * §4.3's **two failure channels are the sharp end**, and the last two properties are the ones worth having:
 * garbling a book must cost vagueness and never instability, and contradicting yourself must cost
 * instability and never a parse error. Those are the design's promise, and they are only checkable by
 * damaging a book that was known good first.
 *
 * Offline the corpus is blocks and the authored words. Biomes and structure sets arrive with a server's
 * dynamic registries, so the populative half of the language — and with it `only`, `except` and the rungs —
 * is exercised by `scripts/checks/quantifiers.txt` rather than here.
 */
@Tags(NEEDS_REGISTRIES)
class BookCheck : FunSpec({

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData()).also {
            check(it.problems.isEmpty()) { "the corpus would not load: ${it.problems}" }
        }
    }

    /** Enough that a rare word combination turns up, quick enough to stay in the offline suite. */
    val booksFuzzed = 2000L

    /**
     * Written once and read by every property below. Generating and resolving are the whole cost of this
     * spec, and doing either per property multiplies a four-minute-loop saving away for nothing.
     */
    val written by lazy { (1L..booksFuzzed).map { seed -> seed to BookGenerator.write(vocabulary, seed) } }
    val resolved by lazy {
        written.map { (seed, pages) ->
            val read = Grammar.read(vocabulary, pages)
            Triple(seed, pages, runCatching { Resolver.resolve(vocabulary, read, seed) })
        }
    }

    /**
     * A generated book says something. The failure this guards is the quiet one — a generator that emits
     * empty or one-page books would pass every property below by never testing anything.
     */
    test("a generated book is a book") {
        // Two pages is the floor and it is a real one: an aiming page with nothing after it changes no
        // world, so a book at that length has spent ink to say nothing at all.
        val silent = written.filter { (_, pages) -> pages.size < 2 }
        check(silent.isEmpty()) { "${silent.size} of $booksFuzzed generated books said nothing: $silent" }
        // Deterministic, or a failing seed could not be reproduced — the first thing anyone will want.
        check(BookGenerator.write(vocabulary, 1L) == BookGenerator.write(vocabulary, 1L)) {
            "the same seed wrote two different books"
        }
    }

    /**
     * **Well-formed means read whole.** A generated book that lost a page would mean the generator can
     * write something the grammar cannot read — and since these become the books that *teach* the grammar
     * (§4.5), a dropped page is a lesson in nonsense.
     */
    test("every generated book is read whole") {
        for ((seed, pages) in written) {
            val read = Grammar.read(vocabulary, pages)
            check(read.dropped.isEmpty()) {
                "seed $seed wrote '${pages.joinToString(" ")}', and the Art could not read " +
                    read.dropped.joinToString(" ")
            }
        }
    }

    /**
     * And it resolves into a world without throwing, at a cost greater than nothing — a book that cost
     * nothing said nothing, whatever its page count.
     */
    test("every generated book resolves") {
        for ((seed, pages, resolution) in resolved) {
            val resolved = resolution
                .getOrElse { failure -> error("seed $seed ('${pages.joinToString(" ")}') would not resolve: $failure") }
            check(resolved.cost > 0) { "seed $seed ('${pages.joinToString(" ")}') cost nothing" }
        }
    }

    /**
     * **A well-formed book coheres.** This is the property that makes the generator content rather than
     * noise: a found book is a worked example a player calibrates against (§4.2), so one that arrived
     * self-contradictory would teach exactly the wrong lesson — and the reader has no way to tell.
     *
     * It has already earned its place: the generator picked a descriptor before committing to the subject
     * it describes, so `savage` was free to walk in beside `ordered` and pull the other way.
     */
    test("every generated book coheres") {
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
        for ((seed, pages) in written.take(GARBLED_SAMPLE)) {
            val garbled = pages + "zzzznotaword$seed"
            val read = Grammar.read(vocabulary, garbled)
            check(read.dropped == listOf("zzzznotaword$seed")) {
                "garbling seed $seed reported ${read.dropped} rather than the one page nobody can read"
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
     */
    test("contradicting yourself costs instability, never a parse error") {
        val opposed = vocabulary.antonyms.firstNotNullOfOrNull { antonym ->
            fun wordAsking(tag: String) = vocabulary.words.firstOrNull { it.tier.narrows && tag in it.wanted }
            val first = wordAsking(antonym.first) ?: return@firstNotNullOfOrNull null
            val second = wordAsking(antonym.second) ?: return@firstNotNullOfOrNull null
            first to second
        } ?: error("the antonym table names no pair the corpus can write, so this can assert nothing")

        val pages = listOf(opposed.first.name, opposed.second.name)
        val read = Grammar.read(vocabulary, pages)
        check(read.dropped.isEmpty()) {
            "'${pages.joinToString(" ")}' was a parse error, which a contradiction must never be: ${read.dropped}"
        }
        check(read.constraints.size == 2) { "a contradictory book lost a word: ${read.constraints}" }
        val resolution = Resolver.resolve(vocabulary, read, SAMPLE_BOOK_SEED)
        check(!resolution.instability.isCoherent) {
            "'${pages.joinToString(" ")}' asks for two opposed things and was charged nothing"
        }
    }
})

/** Garbling re-resolves every book, so it is asked of a sample rather than the whole run. */
private const val GARBLED_SAMPLE = 300

private const val SAMPLE_BOOK_SEED = 20260730L
