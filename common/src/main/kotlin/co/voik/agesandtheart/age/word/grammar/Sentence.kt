package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.word.Word
import net.minecraft.resources.Identifier

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
    /**
     * The parts of the world this claim reaches — where the clause aimed, or the word's own where nobody
     * aimed it (`the-world-model.md` §3, §5).
     *
     * Empty only where an `unaimed` roll took every part the word reached (`Resolver.rolledBare`).
     */
    val aimedAt: Set<Aspect> = emptySet(),
    val polarity: Polarity = Polarity.ASSERTED,
    /** Which `and`-group this joined, or null where it stood alone. */
    val group: Group? = null,
    /**
     * How much of it the writer asked for — [Rung.ORDINARY] where they said nothing, which is the rung
     * that asks for nothing and rebuilds nothing.
     */
    val density: Double = Rung.ORDINARY,
    /**
     * The quantifier page that asked for [density], for the reading to say back — a recipe records the
     * *amount*, since a word's meaning is pack data and an Age must not shift when a pack is retuned
     * (§4.6), but a book shows the page its writer actually laid.
     */
    val quantifier: String? = null,
    /**
     * The biome this term is confined to, or null where it speaks for the whole Age — `in` (§4.3.1).
     *
     * Only an aspect vanilla resolves through the biome can carry one, which is what the grammar admits
     * rather than what this checks: a term that could not be confined has no rule to sit in.
     */
    val confinedTo: Identifier? = null,
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
    /**
     * Whether this was laid on the Age itself, aimed at nothing — the case in which the word's
     * [co.voik.agesandtheart.age.word.Word.unaimed] chances are rolled.
     */
    val laidBare: Boolean = false,
    /**
     * Which member of a **population** this is about, counting the clauses that described one, or null
     * where the claim is not about one (`the-world-model.md` §2).
     *
     * *A large red sun. A small blue sun.* is two bodies, and this is what keeps the second's colour off
     * the first. Every other aspect leaves it null and is steered as a whole.
     *
     * Not *minting*, which the design keeps for a weighted-set member made from a pattern and a substance
     * — `ink springs`. A population's members are described into being, and that is this.
     */
    val describes: Int? = null,
    /**
     * Whether the clause said `everywhere`: a plant or creature named here is asked for in every place,
     * rather than more of it where it already grows.
     */
    val everywhere: Boolean = false,
)

/**
 * One run of pages — a subject and what the writer laid around it. The unit [Readout] renders as a clause,
 * and the only place the *order* a book was written in survives.
 *
 * **Not a parser concept**, despite arriving from the parser: it says what a writer said, in the terms the
 * language itself is described in, and any replacement parser would owe the same. What it deliberately does
 * not carry is a tree — there is no nesting here, only a flat run of clauses.
 *
 * [modifiers] is in written order and leads the subject, which closes the clause (design §4.3.1).
 */
data class Phrase(
    /**
     * Everything said about the subject, **in the order it was written and ahead of it** (§4.3.1).
     *
     * One list rather than the old evocative-then-subject-then-rest. An evocative word had a position of its
     * own because leaning on something unnamed was only possible in front of it; everything leads now, and keeping one
     * list is what stops a reading reordering the pages a writer laid.
     */
    val modifiers: List<Constraint> = emptyList(),
    /** Null where the writer named no subject — a run that only steers, like `blackstone` standing alone. */
    val subject: Constraint? = null,
    /**
     * The biome this whole clause was confined to — `in mushroom_fields, spawns only slime` (§4.3.1).
     *
     * Held here *as well as* on every constraint it governs, and the two are not a duplication: the clause
     * is where a writer laid the page and so where a reading has to say it back, and the constraint is
     * what carries it into the recipe long after the sentence is gone.
     */
    val confinedTo: Identifier? = null,
    /** Whether this clause closed on `everywhere`, held here so a reading can say it back as [confinedTo] is. */
    val everywhere: Boolean = false,
) {
    /** Everything said here, in the order it was laid out — the subject closing the clause it is about. */
    val said: List<Constraint> get() = modifiers + listOfNotNull(subject)
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

    /**
     * Whether the *writer* said anything that stuck. Asked of what they wrote rather than of the whole
     * reading, since a repaired book is complete however little of it came off their pages — and a book
     * nothing survived is one the pen still never refused, only one nobody managed to say a word in.
     */
    val isEmpty: Boolean get() = written.isEmpty()
}
