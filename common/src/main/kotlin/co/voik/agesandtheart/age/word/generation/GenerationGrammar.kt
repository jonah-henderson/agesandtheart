package co.voik.agesandtheart.age.word.generation

import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.StringRepresentable
import kotlin.random.Random

/**
 * One element of a right-hand side. `<name>` expands the rule called `name`; anything else is produced as
 * it stands, which means a terminal may never be written in angle brackets.
 */
sealed interface Symbol {
    /** How it is written in a grammar file. */
    val written: String

    data class Reference(val rule: String) : Symbol {
        override val written: String get() = "<$rule>"
    }

    data class Terminal(val text: String) : Symbol {
        override val written: String get() = text
    }

    companion object {
        private const val OPENS = "<"
        private const val CLOSES = ">"

        fun of(written: String): Symbol {
            val namesARule = written.startsWith(OPENS) && written.endsWith(CLOSES) && written.length > 2
            if (!namesARule) return Terminal(written)
            return Reference(written.removeSurrounding(OPENS, CLOSES))
        }

        val CODEC: Codec<Symbol> = Codec.STRING.xmap(Symbol::of, Symbol::written)
    }
}

/** One right-hand side, and its share of the draw against its siblings'. */
data class Alternative(val weight: Double, val produces: List<Symbol>) {
    /** Whether taking this expands nothing further, which is what the depth guard falls back on. */
    val stopsHere: Boolean get() = produces.none { it is Symbol.Reference }

    companion object {
        private const val ORDINARY_WEIGHT = 1.0

        val CODEC: Codec<Alternative> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.optionalFieldOf("weight", ORDINARY_WEIGHT).forGetter(Alternative::weight),
                Symbol.CODEC.listOf().fieldOf("produce").forGetter(Alternative::produces),
            ).apply(instance, ::Alternative)
        }
    }
}

/**
 * What a grammar's terminals are — the whole of what a caller has to know to use its output.
 */
enum class TerminalKind(val key: String) : StringRepresentable {
    /** Words of the Art, so the output is a book's pages. Checked against the corpus at load. */
    WORD("word"),

    /** Text the Art has no opinion about — syllables, mostly. Joined by whoever asked for it. */
    TEXT("text"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<TerminalKind> = StringRepresentable.fromEnum(TerminalKind::values)
    }
}

/**
 * A weighted grammar read **forwards**: a start symbol and a seed in, a row of terminals out.
 *
 * The other grammar in the mod parses; this one produces, and the two share no machinery on purpose. The
 * parser's terminals are word *classes* so that it stays small while the vocabulary grows without bound
 * (see `age/word/grammar/`), where these terminals are the words themselves — which is the point, since
 * choosing *which* word is the whole of what a generator does.
 *
 * A rule whose alternatives are all single terminals is a **middle symbol**: `cold_stone` naming three
 * blocks is one, and it needs no concept of its own because a nonterminal already is one.
 */
data class GenerationGrammar(
    /** The file's name, which is what a caller asks for it by. */
    val name: String,
    val start: String,
    val terminals: TerminalKind,
    val rules: Map<String, List<Alternative>>,
) {
    /** What this grammar produces at [random] — deterministic, so a seed names a book or a name. */
    fun expand(random: Random): List<String> {
        val produced = mutableListOf<String>()
        expand(start, random, depth = 0, produced)
        return produced.toList()
    }

    private fun expand(rule: String, random: Random, depth: Int, produced: MutableList<String>) {
        val chosen = choose(rules[rule] ?: return, random, depth, produced.size) ?: return
        for (symbol in chosen.produces) {
            if (produced.size >= MOST_TERMINALS) return
            when (symbol) {
                is Symbol.Terminal -> produced += symbol.text
                is Symbol.Reference -> expand(symbol.rule, random, depth + 1, produced)
            }
        }
    }

    /**
     * Which right-hand side to take. Past the guards only alternatives that expand nothing further are
     * considered, so a grammar weighted towards recursion still stops — [problemsWith] refuses at load the
     * one that has no way to stop at all.
     */
    private fun choose(
        alternatives: List<Alternative>,
        random: Random,
        depth: Int,
        produced: Int,
    ): Alternative? {
        val isRunningLong = depth >= DEEPEST || produced >= MOST_TERMINALS
        val considered = if (!isRunningLong) alternatives else alternatives.filter { it.stopsHere }
        val total = considered.sumOf { it.weight }
        if (total <= 0.0) return null
        var roll = random.nextDouble() * total
        for (alternative in considered) {
            roll -= alternative.weight
            if (roll <= 0.0) return alternative
        }
        return considered.lastOrNull()
    }

    companion object {
        /** Deeper than any grammar worth authoring, and shallow enough that recursion cannot blow the stack. */
        private const val DEEPEST = 24

        /** More terminals than a book has pages or a name has syllables. */
        private const val MOST_TERMINALS = 64

        fun codec(name: String): Codec<GenerationGrammar> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("start").forGetter(GenerationGrammar::start),
                TerminalKind.CODEC.fieldOf("terminals").forGetter(GenerationGrammar::terminals),
                Codec.unboundedMap(Codec.STRING, Alternative.CODEC.listOf()).fieldOf("rules")
                    .forGetter(GenerationGrammar::rules),
            ).apply(instance) { start, terminals, rules -> GenerationGrammar(name, start, terminals, rules) }
        }

        /**
         * Everything wrong with [grammar], in the words its author needs to hear. A typo in a rule name or
         * in a word is otherwise invisible until a generated book quietly comes out short.
         */
        fun problemsWith(grammar: GenerationGrammar, isAWord: (String) -> Boolean): List<String> {
            val problems = mutableListOf<String>()
            val grammarSaid = "generation grammar '${grammar.name}'"
            if (grammar.start !in grammar.rules) {
                problems += "$grammarSaid starts at <${grammar.start}>, which no rule defines"
            }
            for ((rule, alternatives) in grammar.rules.entries.sortedBy { it.key }) {
                if (alternatives.sumOf { it.weight } <= 0.0) {
                    problems += "$grammarSaid gives <$rule> nothing it can produce"
                }
                for (symbol in alternatives.flatMap { it.produces }) {
                    problems += problemWith(symbol, rule, grammar, isAWord) ?: continue
                }
            }
            problems += unfinishable(grammar).map { "$grammarSaid can never finish <$it>" }
            problems += unreachable(grammar).map { "$grammarSaid defines <$it> and never asks for it" }
            return problems
        }

        private fun problemWith(
            symbol: Symbol,
            rule: String,
            grammar: GenerationGrammar,
            isAWord: (String) -> Boolean,
        ): String? {
            val grammarSaid = "generation grammar '${grammar.name}'"
            return when (symbol) {
                is Symbol.Reference ->
                    if (symbol.rule in grammar.rules) null
                    else "$grammarSaid has <$rule> ask for <${symbol.rule}>, which no rule defines"

                is Symbol.Terminal ->
                    if (grammar.terminals != TerminalKind.WORD || isAWord(symbol.text)) null
                    else "$grammarSaid has <$rule> produce '${symbol.text}', which is no word of the Art"
            }
        }

        /**
         * Rules with no way to reach terminals, which is the one fault the depth guard can only paper over.
         * A reference to a rule that does not exist counts as finishing, since at expansion time it
         * produces nothing — it is already reported as the missing rule it is.
         */
        private fun unfinishable(grammar: GenerationGrammar): List<String> {
            val finishes = mutableSetOf<String>()
            fun everyReferenceFinishes(alternative: Alternative) =
                alternative.produces.filterIsInstance<Symbol.Reference>()
                    .all { it.rule !in grammar.rules || it.rule in finishes }
            do {
                val before = finishes.size
                for ((rule, alternatives) in grammar.rules) {
                    if (alternatives.any { it.weight > 0.0 && everyReferenceFinishes(it) }) finishes += rule
                }
            } while (finishes.size > before)
            return grammar.rules.keys.filterNot { it in finishes }.sorted()
        }

        /** Rules nothing asks for — a renamed nonterminal whose old callers were all updated but one. */
        private fun unreachable(grammar: GenerationGrammar): List<String> {
            val reached = mutableSetOf(grammar.start)
            val pending = ArrayDeque(listOf(grammar.start))
            while (pending.isNotEmpty()) {
                val alternatives = grammar.rules[pending.removeFirst()].orEmpty()
                for (symbol in alternatives.flatMap { it.produces }.filterIsInstance<Symbol.Reference>()) {
                    if (reached.add(symbol.rule)) pending += symbol.rule
                }
            }
            return grammar.rules.keys.filterNot { it in reached }.sorted()
        }
    }
}

/**
 * The generation grammars a pack ships, one file per grammar under `art/generation/`.
 *
 * **Not stacked.** A grammar is a rule set that hangs together, so two packs' halves interleaved would be
 * one neither author wrote — the same argument the script's rewrite table makes. A higher-priority pack
 * replaces a grammar whole.
 */
data class GenerationGrammars(private val byName: Map<String, GenerationGrammar>) {
    /** Every grammar this server knows, for `/age draft` to offer and a check to walk. */
    val names: List<String> get() = byName.keys.sorted()

    fun grammar(name: String): GenerationGrammar? = byName[name]

    companion object {
        /** Where a pack puts generation grammars, one file per grammar. */
        const val GENERATION_DIRECTORY = "art/generation"

        fun load(
            resources: ResourceManager,
            isAWord: (String) -> Boolean,
            problems: MutableList<String>,
        ): GenerationGrammars {
            val grammars = mutableMapOf<String, GenerationGrammar>()
            val sources = mutableMapOf<String, Identifier>()
            for ((file, resource) in resources.listResources(GENERATION_DIRECTORY, ResourceParsing::isJson)) {
                val name = ResourceParsing.nameUnder(file, GENERATION_DIRECTORY)
                val grammar = ResourceParsing.parse(resource, file, GenerationGrammar.codec(name), problems)
                    ?: continue
                // Two namespaces both shipping `book.json` would otherwise leave the winner to iteration
                // order; one pack overriding another's file at the same path never reaches here.
                val standing = sources[name]
                if (standing != null) problems += "two generation grammars are both called '$name': $standing and $file"
                sources[name] = file
                problems += GenerationGrammar.problemsWith(grammar, isAWord)
                grammars[name] = grammar
            }
            return GenerationGrammars(grammars.toMap())
        }
    }
}
