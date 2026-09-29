package co.voik.agesandtheart.age.word.generation

/**
 * A generation grammar in the form it is **authored** in, converted to and from the form it ships in.
 *
 * `book.json` written longhand is 142 lines for twelve rules — a JSON object per alternative and a line per
 * symbol — where a weighted context-free grammar has an obvious text form that is a line per rule. So the
 * `.gen` files under `src/main/generation` are the source, the pack JSON is generated from them by
 * `./gradlew :common:grammars`, and `GrammarSourceCheck` fails the build if the two have drifted.
 *
 * Rule names and terminals are both lowercase, and a terminal may be a full id like `minecraft:jungle`, so
 * case and `:` cannot tell them apart. Hence `[square]` marks a reference and `=` opens a rule.
 *
 * The notation entire:
 *
 * ```
 * # a comment, and only where # opens the line
 * start     book
 * terminals word
 *
 * book = age [frozen_age] | age [molten_age]
 *
 * frozen_age = [bleak_mood] landmass [cold_stone] climate frozen@2
 *     | landmass [cold_stone] climate frozen sky [cold_sky]
 * ```
 *
 * `|` separates alternatives and may open a continuation line; any indented line under a rule continues it,
 * so a rule's first alternative may sit on the line after its `=`. `@N` on the end of an alternative gives the
 * **whole alternative** a weight, one being the weight of one that says nothing; it may be attached to the
 * last symbol (`frozen@2`) or stand apart (`frozen @2`).
 */
object GrammarNotation {

    /**
     * The grammar [text] spells, or null where it spells none — every complaint carrying the line it is
     * about, since a grammar is authored by hand and a diagnostic without a line number is a search.
     *
     * Says nothing about whether a terminal is a *word*: that needs the corpus, which needs a server for
     * half of it, and [GenerationGrammar.problemsWith] already asks when one is there.
     */
    fun read(name: String, text: String, problems: MutableList<String>): GenerationGrammar? {
        val found = problems.size
        fun complain(line: Int, said: String) = run { problems += "$name$SUFFIX:$line: $said" }

        var start: String? = null
        var terminals: TerminalKind? = null
        val rules = linkedMapOf<String, MutableList<Alternative>>()
        var openRule: String? = null

        for ((index, raw) in text.lines().withIndex()) {
            val line = index + 1
            val said = raw.trim()
            if (said.isEmpty() || said.startsWith(COMMENT)) continue

            // An indented line under an open rule carries it on, `|` or not, so a rule may open with its
            // first alternative on the line after the `=`.
            val indentedUnderARule = raw.first().isWhitespace() && openRule != null && RULE.matchEntire(said) == null
            if (said.startsWith(CONTINUES) || indentedUnderARule) {
                val carrying = rules[openRule]
                if (carrying == null) complain(line, "an alternative with no rule above it")
                else carrying += alternativesIn(said.removePrefix(CONTINUES), line, ::complain)
                continue
            }

            val header = HEADER.matchEntire(said)
            if (header != null) {
                val (field, value) = header.destructured
                when (field) {
                    START -> start = value
                    else -> {
                        terminals = TerminalKind.entries.firstOrNull { it.key == value }
                        if (terminals == null) complain(line, "no kind of terminal is called '$value'")
                    }
                }
                continue
            }

            val opening = RULE.matchEntire(said)
            if (opening == null) {
                complain(line, "'$said' is neither a header, a rule, nor an alternative")
                continue
            }
            val (rule, tail) = opening.destructured
            if (rule in rules) complain(line, "[$rule] is opened twice; write one rule and join it with |")
            openRule = rule
            rules.getOrPut(rule) { mutableListOf() } += alternativesIn(tail, line, ::complain)
        }

        if (start == null) complain(1, "no `start` line, so nothing says which rule the grammar begins at")
        if (terminals == null) complain(1, "no `terminals` line, so nothing says what its output is")
        if (problems.size > found) return null
        return GenerationGrammar(
            name,
            start ?: return null,
            terminals ?: return null,
            rules.mapValues { (_, alternatives) -> alternatives.toList() },
        )
    }

    /**
     * [grammar] as it would be authored — **in reading order**, which is breadth-first from the start rule
     * rather than the order a map happened to hold. A grammar is read top to bottom by whoever is tuning it,
     * so the rule that uses a thing should come before the thing.
     */
    fun write(grammar: GenerationGrammar): String {
        val lines = mutableListOf(
            "$START$HEADER_GAP${grammar.start}",
            "$TERMINALS ${grammar.terminals.key}",
        )
        for (rule in readingOrderOf(grammar)) {
            val alternatives = grammar.rules[rule].orEmpty().map(::writtenAlternative)
            lines += ""
            val together = "$rule $OPENS ${alternatives.joinToString(" $CONTINUES ")}"
            if (together.length <= LONGEST_LINE || alternatives.size < 2) {
                lines += together
                continue
            }
            lines += "$rule $OPENS ${alternatives.first()}"
            for (alternative in alternatives.drop(1)) lines += "$CONTINUATION_INDENT$CONTINUES $alternative"
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * Every rule, the ones reachable from the start first and in the order they are reached. Anything
     * unreachable is kept rather than dropped — it is [GenerationGrammar.problemsWith]'s to report, and
     * silently losing a rule on the way through a converter would be the worse answer by far.
     */
    private fun readingOrderOf(grammar: GenerationGrammar): List<String> {
        val ordered = mutableListOf<String>()
        val pending = ArrayDeque(listOf(grammar.start))
        val seen = mutableSetOf(grammar.start)
        while (pending.isNotEmpty()) {
            val rule = pending.removeFirst()
            if (rule in grammar.rules) ordered += rule
            val alternatives = grammar.rules[rule].orEmpty()
            for (symbol in alternatives.flatMap { it.produces }.filterIsInstance<Symbol.Reference>()) {
                if (seen.add(symbol.rule)) pending += symbol.rule
            }
        }
        return ordered + grammar.rules.keys.filterNot { it in seen }.sorted()
    }

    /** The alternatives one stretch of a rule's right-hand side spells, `|` between them. */
    private fun alternativesIn(text: String, line: Int, complain: (Int, String) -> Unit): List<Alternative> =
        text.split(CONTINUES).map(String::trim).filter { it.isNotEmpty() }.mapNotNull { written ->
            alternativeOf(written) ?: null.also { complain(line, "'$written' produces nothing") }
        }

    /** One right-hand side: the symbols it produces, and the weight `@N` on the end gives it. */
    private fun alternativeOf(written: String): Alternative? {
        val tokens = written.split(WHITESPACE).filter { it.isNotEmpty() }
        val weighing = tokens.lastOrNull()?.let(WEIGHED::matchEntire)
        val produced = when {
            weighing == null -> tokens
            // `frozen @2`: the weight is a token of its own.
            weighing.groupValues[1].isEmpty() -> tokens.dropLast(1)
            // `frozen@2`: the weight rides on the last symbol.
            else -> tokens.dropLast(1) + weighing.groupValues[1]
        }
        if (produced.isEmpty()) return null
        val weight = weighing?.groupValues?.get(2)?.toDoubleOrNull() ?: ORDINARY_WEIGHT
        return Alternative(weight, produced.map(Symbol::of))
    }

    private fun writtenAlternative(alternative: Alternative): String {
        val produced = alternative.produces.joinToString(" ") { it.written }
        if (alternative.weight == ORDINARY_WEIGHT) return produced
        val weight = alternative.weight
        // Whole weights are the ordinary case and `@2` is what an author writes; `@0.5` survives unrounded.
        val said = if (weight == weight.toLong().toDouble()) weight.toLong().toString() else weight.toString()
        return "$produced$WEIGHS$said"
    }

    /** What an alternative weighs when it does not say — [Alternative]'s own default, spelled once here. */
    private const val ORDINARY_WEIGHT = 1.0

    /** Past this a rule is written an alternative to the line instead of all on one. */
    private const val LONGEST_LINE = 100

    private const val SUFFIX = ".gen"
    private const val COMMENT = "#"
    private const val CONTINUES = "|"
    private const val OPENS = "="
    private const val WEIGHS = "@"
    private const val START = "start"
    private const val TERMINALS = "terminals"
    private const val CONTINUATION_INDENT = "    "

    /** So `start` and `terminals` line their values up, the longer word setting the column. */
    private val HEADER_GAP = " ".repeat(TERMINALS.length - START.length + 1)

    private val HEADER = Regex("($START|$TERMINALS)\\s+(\\S+)")
    private val RULE = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*$OPENS\\s*(.*)")
    /** A last token carrying a weight: what it produces, if anything, then the weight. */
    private val WEIGHED = Regex("(.*)$WEIGHS(\\d+(?:\\.\\d+)?)")
    private val WHITESPACE = Regex("\\s+")
}
