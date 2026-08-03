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
         * **Empty means nowhere.** No arrangement of pages produces it any more — a section admits only
         * terms belonging to what it aims at, and a book laying one anywhere else does not parse at all, so
         * a word aimed where it says nothing is [Repair]'s to re-home rather than a scope to represent.
         * What keeps the case is [Sentence.of], where a check builds a constraint by hand.
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
    /**
     * Whether the Art supplied this page rather than the writer — the natural course of a world nobody
     * described that far.
     *
     * It constrains the Age exactly as a written page does, and differs in the two places a *page* differs
     * from a *claim*: it costs no ink, because nobody spent any, and the book never shows it.
     */
    val latent: Boolean = false,
    /**
     * Whether the writer laid this page in a clause that could not read it, so [Repair] moved it to one
     * that could (§4.3.1).
     *
     * Charged, and that is the whole of the design's objection: *silent* re-homing is the mistake, not
     * re-homing. Where it went is the clause it is in now, which the readout shows.
     */
    val rehomed: Boolean = false,
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
 * A parsed book: what the Art could read, and the two quite different ways a page can fail to be in it —
 * §4.3's failure channels, now genuinely kept apart at the type level.
 *
 * Garbling your words makes an Age vaguer; writing something no sentence has room for makes it unstable;
 * contradicting yourself makes it unstable too, and the resolver finds that later among constraints that
 * parsed perfectly.
 *
 * [phrases] is the sentence; [constraints] is that same sentence flattened, which is all the resolver ever
 * wants. Derived rather than stored so the two cannot drift apart.
 */
data class Sentence(
    val phrases: List<Phrase>,
    /**
     * Pages nobody recognises, in the order they were laid out — **vagueness, charged nothing** (§4.3).
     *
     * Nearly unreachable in play: a page is a physical item carrying a real word and `/age write` refuses
     * a word the Art has never heard of, so what is left is a book outliving the pack that taught it.
     */
    val unreadable: List<String> = emptyList(),
    /**
     * Pages [Repair] could find no position for in any sentence at all — **charged**, and heavily
     * (§4.3.1). A second `Age`, or an `and` with nothing on one side of it.
     */
    val impossible: List<String> = emptyList(),
    /**
     * The structures the writer spelled — `Age`, `and`, `only`. They make no claim and so appear in no
     * [Constraint], but **every page laid costs ink**, so what they cost has to survive the parse.
     */
    val structural: List<Production> = emptyList(),
) {
    /** Every constraint the book made, in written order. The resolver's whole view of a sentence. */
    val constraints: List<Constraint> get() = phrases.flatMap { it.said }

    /** The constraints a writer actually laid — what the book shows, and what the ink was spent on. */
    val written: List<Constraint> get() = constraints.filterNot { it.latent }

    val words: List<Word> get() = constraints.map { it.word }

    /** Every page that reached no clause, whichever way it failed — for saying so beside the readout. */
    val dropped: List<String> get() = unreadable + impossible

    companion object {
        /**
         * A book already broken into constraints, with no clause structure to render — what a check means
         * when it builds a sentence by hand to exercise the resolver.
         */
        fun of(constraints: List<Constraint>, unreadable: List<String> = emptyList()): Sentence =
            Sentence(constraints.map { Phrase(modifiers = listOf(it)) }, unreadable)

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

    /**
     * Whether the *writer* said anything that stuck. Asked of what they wrote rather than of the whole
     * reading, since a repaired book is complete however little of it came off their pages — and a book
     * nothing survived is one the pen still never refused, only one nobody managed to say a word in.
     */
    val isEmpty: Boolean get() = written.isEmpty()
}
