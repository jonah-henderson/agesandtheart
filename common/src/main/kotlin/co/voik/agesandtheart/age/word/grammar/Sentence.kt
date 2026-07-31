package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Density
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.word.Word

/**
 * Where a word has its say — what the grammar decided by *position* (§4.3.1). The asymmetry is the point:
 * a word that cannot narrow candidates must not narrow its own scope either, or aiming an evocative word
 * would quietly demote it to a restrictive one.
 */
sealed interface Scope {
    /** The aspects this word actually reaches, given [everywhere] as what "anywhere" means for it. */
    fun reaches(everywhere: List<Aspect>): List<Aspect>

    /**
     * Everywhere it can find purchase, leaning hardest on [emphasised] — what an **evocative** word gets,
     * aimed or not. Aiming does not confine it: `beautiful sky` still shifts weights everywhere.
     */
    data class Everywhere(val emphasised: Set<Aspect> = emptySet()) : Scope {
        override fun reaches(everywhere: List<Aspect>): List<Aspect> = everywhere
    }

    /**
     * Confined to these aspects — what a **restrictive or exact** word gets, which is what makes
     * `flat land` a claim about the land and nothing else.
     */
    data class Confined(val aspects: Set<Aspect>) : Scope {
        /**
         * **Empty means nowhere**, which is the one case aiming has to be able to express: a word aimed at
         * a part of the world it says nothing about — `flat sky` — is charged and *never re-homed*
         * (§4.3.1). Reading it as "wherever it fits" is exactly the re-homing the design forbids, and it
         * is silent, which is worse than the mistake.
         *
         * Reachable only through aiming: `VocabularyCheck` makes every narrowing word declare its aspects,
         * and a word that declares none is evocative, which gets [Everywhere].
         */
        override fun reaches(everywhere: List<Aspect>): List<Aspect> = aspects.sortedBy { it.ordinal }
    }
}

/**
 * Words a writer joined with `and` — "keep both, and keep them apart" (§3.2).
 *
 * An identity rather than a list, so a [Constraint] stays a flat record. Ungrouped is `null`, which is
 * *not* a group of one: unjoined juxtaposition stays contention, or "and" would mean nothing.
 */
@JvmInline
value class Group(val index: Int)

/**
 * One word, and everything the grammar decided about it. **No parser concepts appear here**: the resolver
 * never sees a parse tree, which is what lets the parser be replaced by rewriting one file, and what lets
 * `ResolverCheck` build sentences by hand.
 */
data class Constraint(
    val word: Word,
    val scope: Scope,
    val polarity: Polarity = Polarity.ASSERTED,
    /** Which `and`-group this joined, or null where it stood alone. */
    val group: Group? = null,
    /**
     * How much of it the writer asked for — [Density.ORDINARY] where they said nothing, which is the rung
     * that asks for nothing and rebuilds nothing.
     */
    val density: Density = Density.ORDINARY,
)

/**
 * One run of pages — a subject and what the writer laid around it. The unit [Readout] renders as a clause,
 * and the only place the *order* a book was written in survives.
 *
 * **Not a parser concept**, despite arriving from the parser: it says what a writer said, in the terms the
 * language itself is described in, and any replacement parser would owe the same. What it deliberately does
 * not carry is a tree — there is no nesting here, only a flat run of clauses.
 *
 * The three lists are in written order and are the whole of it: evocative words precede their subject,
 * everything steering it follows (design §4.3.1).
 */
data class Phrase(
    val descriptors: List<Constraint> = emptyList(),
    /** Null where the writer named no subject — a run that only steers, like `blackstone` standing alone. */
    val subject: Constraint? = null,
    val modifiers: List<Constraint> = emptyList(),
) {
    /** Everything said here, in the order it was laid out. */
    val said: List<Constraint> get() = descriptors + listOfNotNull(subject) + modifiers
}

/**
 * A parsed book: what the Art could read, and what it could not — §4.3's two failure channels kept apart
 * at the type level. [dropped] becomes **vagueness** and is charged nothing; contradiction is a separate
 * matter the resolver finds later among constraints that parsed perfectly.
 *
 * Garbling your words makes an Age vaguer; contradicting yourself makes it unstable.
 *
 * [phrases] is the sentence; [constraints] is that same sentence flattened, which is all the resolver ever
 * wants. Derived rather than stored so the two cannot drift apart.
 */
data class Sentence(
    val phrases: List<Phrase>,
    /** Pages the Art could not read, in the order they were laid out — for telling the writer. */
    val dropped: List<String> = emptyList(),
) {
    /** Every constraint the book made, in written order. The resolver's whole view of a sentence. */
    val constraints: List<Constraint> get() = phrases.flatMap { it.said }

    val words: List<Word> get() = constraints.map { it.word }

    companion object {
        /**
         * A book already broken into constraints, with no clause structure to render — what a check means
         * when it builds a sentence by hand to exercise the resolver.
         */
        fun of(constraints: List<Constraint>, dropped: List<String> = emptyList()): Sentence =
            Sentence(constraints.map { Phrase(modifiers = listOf(it)) }, dropped)

        /**
         * A book with no structure — every word standing alone, unaimed. What a check means when it wants
         * to exercise the resolver without a parser.
         */
        fun flat(words: List<Word>): Sentence = of(
            words.map { word ->
                val scope = if (word.tier.narrows) Scope.Confined(word.aspects) else Scope.Everywhere()
                Constraint(word, scope)
            },
        )
    }

    /** Whether anything at all was understood. An unreadable book still opens an Age — the pen never refuses. */
    val isEmpty: Boolean get() = phrases.all { it.said.isEmpty() }
}
