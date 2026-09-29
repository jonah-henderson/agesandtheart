package co.voik.agesandtheart.age.word.generation

import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

/** A grammar built in code, so the engine can be asked about shapes no shipped file has. */
internal fun grammarOf(
    start: String,
    vararg rules: Pair<String, List<Alternative>>,
    terminals: TerminalKind = TerminalKind.TEXT,
) = GenerationGrammar("check", start, terminals, rules.toMap())

internal fun produces(vararg symbols: String, weight: Double = 1.0) =
    Alternative(weight, symbols.map(Symbol::of))

/**
 * The engine that reads a grammar **forwards**: does an expansion terminate, obey its weights, and
 * reproduce from a seed? Offline, because nothing here needs a corpus — a grammar is rules and weights,
 * and what its terminals mean is the caller's business.
 */
class GenerationCheck : FunSpec({

    test("an expansion reproduces from its seed") {
        val grammar = grammarOf(
            "start",
            "start" to listOf(produces("[a]", "[a]", "[a]")),
            "a" to listOf(produces("x", weight = 3.0), produces("y"), produces("z")),
        )
        for (seed in 1L..50L) {
            val once = grammar.expand(Random(seed))
            val again = grammar.expand(Random(seed))
            check(once == again) { "seed $seed produced $once and then $again" }
        }
    }

    /** Weights are the whole of the authoring control, so a heavy alternative has to actually dominate. */
    test("weight decides how often an alternative is taken") {
        val grammar = grammarOf(
            "start",
            "start" to listOf(produces("common", weight = 9.0), produces("rare", weight = 1.0)),
        )
        val draws = (1L..2000L).map { grammar.expand(Random(it)).single() }
        val common = draws.count { it == "common" }
        check(common in 1600..1990) { "'common' at nine parts in ten came up $common times in ${draws.size}" }
    }

    /**
     * **A grammar that can recurse forever still has to stop.** The depth guard falls back to alternatives
     * that expand nothing further, which is why [GenerationGrammar.problemsWith] refuses one that has none.
     */
    test("a recursive grammar terminates") {
        val grammar = grammarOf(
            "start",
            "start" to listOf(produces("[start]", "[start]", weight = 99.0), produces("end")),
        )
        for (seed in 1L..200L) {
            val produced = grammar.expand(Random(seed))
            check(produced.isNotEmpty()) { "seed $seed produced nothing at all" }
            check(produced.all { it == "end" }) { "seed $seed produced something unexpected: $produced" }
        }
    }

    /** A rule nobody defined produces nothing — the fault is reported at load, never improvised here. */
    test("a missing rule produces nothing rather than failing") {
        val grammar = grammarOf("start", "start" to listOf(produces("a", "[nowhere]", "b")))
        val produced = grammar.expand(Random(1))
        check(produced == listOf("a", "b")) { "read back as $produced" }
    }

    context("the faults an author needs told about") {
        fun problemsIn(grammar: GenerationGrammar, isAWord: (String) -> Boolean = { true }) =
            GenerationGrammar.problemsWith(grammar, isAWord)

        test("a start symbol no rule defines") {
            val grammar = grammarOf("book", "opening" to listOf(produces("age")))
            val problems = problemsIn(grammar)
            check(problems.any { "starts at [book]" in it }) { "went unreported: $problems" }
        }

        test("a reference to a rule that does not exist") {
            val grammar = grammarOf("start", "start" to listOf(produces("[mising_typo]")))
            val problems = problemsIn(grammar)
            check(problems.any { "[mising_typo]" in it }) { "went unreported: $problems" }
        }

        /** The one fault the runtime guard can only paper over, so it has to be caught at load. */
        test("a rule with no way to reach terminals") {
            val grammar = grammarOf("start", "start" to listOf(produces("[start]", "[start]")))
            val problems = problemsIn(grammar)
            check(problems.any { "can never finish [start]" in it }) { "went unreported: $problems" }
        }

        test("a rule nothing asks for") {
            val grammar = grammarOf(
                "start",
                "start" to listOf(produces("x")),
                "orphan" to listOf(produces("y")),
            )
            val problems = problemsIn(grammar)
            check(problems.any { "[orphan]" in it }) { "went unreported: $problems" }
        }

        /** A word grammar producing a word the corpus never heard of is a page that would drop, silently. */
        test("a terminal that is no word of the Art") {
            val grammar = grammarOf(
                "start",
                "start" to listOf(produces("landmass", "zzzznotaword")),
                terminals = TerminalKind.WORD,
            )
            val problems = problemsIn(grammar) { it == "landmass" }
            check(problems.any { "zzzznotaword" in it }) { "went unreported: $problems" }
            check(problems.none { "landmass" in it }) { "a real word was reported: $problems" }
        }

        /** A text grammar's terminals mean nothing to the Art, so they must not be held to the corpus. */
        test("a text grammar's terminals are not held to the corpus") {
            val grammar = grammarOf("start", "start" to listOf(produces("reh", "vah")))
            val problems = problemsIn(grammar) { false }
            check(problems.isEmpty()) { "reported: $problems" }
        }
    }
})
