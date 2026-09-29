package co.voik.agesandtheart.age.word.generation

import io.kotest.core.spec.style.FunSpec

/**
 * Whether the authored form of a generation grammar reads, writes, and refuses.
 *
 * A converter is trusted rather than watched — you run it and read the `.gen`, not the JSON it wrote — so
 * the two things it owes are that nothing is lost going either way, and that a grammar it *cannot* read is
 * refused with the line to look at rather than half-converted. `GrammarSourceCheck` asks the other
 * question, which is whether the shipped JSON still matches the source beside it.
 */
class GrammarNotationCheck : FunSpec({

    /** Weights, wrapping and reading order all survive the trip out and back. */
    test("a grammar written out reads back the same") {
        val written = GrammarNotation.write(SAMPLE)
        val problems = mutableListOf<String>()
        val read = GrammarNotation.read("sample", written, problems)
        check(problems.isEmpty()) { "'$written' did not read back:\n  ${problems.joinToString("\n  ")}" }
        check(read == SAMPLE) { "came back as $read, not $SAMPLE" }
    }

    /** An alternative that says nothing about its weight is worth one, which is what [Alternative] says. */
    test("an unweighted alternative weighs one") {
        val read = readOrFail("a = b | c@3 | d@0.5")
        val weights = read.rules.getValue("a").map { it.weight }
        check(weights == listOf(1.0, 3.0, 0.5)) { "read the weights as $weights" }
    }

    /**
     * A weight may ride on the last symbol or stand apart, and weighs the **whole** alternative either
     * way — never only the symbol it is attached to.
     */
    test("a weight attached and a weight apart read the same") {
        val attached = readOrFail("a = b [c]@2 | d").rules.getValue("a")
        val apart = readOrFail("a = b [c] @2 | d").rules.getValue("a")
        check(attached == apart) { "attached read as $attached, apart as $apart" }
        check(attached.first().weight == 2.0) { "the weight read as ${attached.first().weight}" }
        val produced = attached.first().produces.map { it.written }
        check(produced == listOf("b", "[c]")) { "the weight was left on the symbol: $produced" }
    }

    /**
     * **A terminal may be a full id, and this is why the notation opens a rule with `=`.** `minecraft:jungle`
     * is a word the moment a server has loaded its registries, and `:` is what ANTLR's own notation would
     * have spent on the rule operator — so borrowing `.g4`'s shape wholesale would have made this unsayable.
     */
    test("a terminal may carry a namespace") {
        val read = readOrFail("a = minecraft:jungle [b]\nb = minecraft:villages")
        val produced = read.rules.getValue("a").single().produces.map { it.written }
        check(produced == listOf("minecraft:jungle", "[b]")) { "read the alternative as $produced" }
    }

    /** A rule may be joined across lines, which is the whole reason a long one is readable. */
    test("a rule continues across lines") {
        val read = readOrFail("a = b\n    | c\n    | d@2")
        check(read.rules.getValue("a").size == 3) { "read ${read.rules.getValue("a").size} alternatives" }
    }

    /** And its first alternative may sit on the next line, indented, which is how a long rule is written. */
    test("a rule may open on the line after its equals") {
        val read = readOrFail("a =\n    b [c]\n    | d@2\nc = e")
        check(read.rules.getValue("a").size == 2) { "read ${read.rules.getValue("a")}" }
        check(read.rules.keys == setOf("a", "c")) { "an unindented rule was swallowed: ${read.rules.keys}" }
    }

    /** `#` opens a comment, and only where it opens the line — so a terminal carrying one is still a word. */
    test("a comment is a line of its own") {
        val read = readOrFail("# the whole line\na = b")
        check(read.rules.keys == setOf("a")) { "read the rules as ${read.rules.keys}" }
    }

    /**
     * Every way a grammar can fail to read says **what** and **where**. A converter that reported "could not
     * read" against a fifty-line file would send its author looking, which is the whole cost the notation
     * was meant to save.
     */
    test("a grammar that does not read says why, and where") {
        val refusals = listOf(
            "no `start` line" to "terminals word\na = b",
            "no `terminals` line" to "start a\na = b",
            "no kind of terminal is called 'bogus'" to "start a\nterminals bogus\na = b",
            "an alternative with no rule above it" to "start a\nterminals word\n| b",
            "opened twice" to "start a\nterminals word\na = b\na = c",
            "is neither a header, a rule, nor an alternative" to "start a\nterminals word\nnonsense here",
        )
        for ((expected, text) in refusals) {
            val problems = mutableListOf<String>()
            val read = GrammarNotation.read("sample", text, problems)
            check(read == null) { "'$text' was read as $read when it should have been refused" }
            check(problems.any { expected in it }) { "'$text' complained $problems, not about '$expected'" }
            check(problems.all { LINE_NUMBER.containsMatchIn(it) }) {
                "a complaint about '$text' named no line: $problems"
            }
        }
    }
})

/** Reads [text] or fails the check saying why, for the properties that are about a grammar that does read. */
private fun readOrFail(text: String): GenerationGrammar {
    val problems = mutableListOf<String>()
    val read = GrammarNotation.read("sample", withHeaders(text), problems)
    check(problems.isEmpty()) { "'$text' did not read:\n  ${problems.joinToString("\n  ")}" }
    return read ?: error("'$text' read as nothing at all")
}

/** The two lines every grammar needs, so a property about one rule can be written as one rule. */
private fun withHeaders(text: String): String = "start a\nterminals word\n$text"

/** `sample.gen:3:` — every complaint carries one, which is half of what makes it actionable. */
private val LINE_NUMBER = Regex("sample\\.gen:\\d+:")

/**
 * One grammar wearing everything the notation has to carry: a weight, a fractional weight, a reference, a
 * namespaced terminal, and a rule long enough to be written an alternative to the line.
 */
private val SAMPLE = GenerationGrammar(
    name = "sample",
    start = "book",
    terminals = TerminalKind.WORD,
    rules = mapOf(
        "book" to listOf(
            Alternative(2.0, listOf(Symbol.of("age"), Symbol.of("[mood]"))),
            Alternative(1.0, listOf(Symbol.of("age"), Symbol.of("minecraft:jungle"))),
        ),
        "mood" to listOf(
            Alternative(0.5, listOf(Symbol.of("desolate"))),
            Alternative(1.0, (1..20).map { Symbol.of("word$it") }),
        ),
    ),
)
