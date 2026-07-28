package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.word.Word

/**
 * Where a word has its say — what the grammar decided by *position*, so nothing downstream has to guess.
 *
 * This is the tier rule of design §4.3.1 made into a type, and the asymmetry is the whole point: a word
 * that cannot narrow candidates must not be able to narrow its own scope either, or aiming an evocative
 * word would quietly demote it to a restrictive one.
 */
sealed interface Scope {
    /** The slots this word actually reaches, given [everywhere] as what "anywhere" means for it. */
    fun reaches(everywhere: List<Slot>): List<Slot>

    /**
     * Everywhere it can find purchase, leaning hardest on [emphasised] — what an **evocative** word gets,
     * aimed or not.
     *
     * Aiming one does not confine it: `beautiful sky` still shifts weights over every candidate in the
     * world, it simply shifts them hardest overhead. So the beginner's `beautiful floating` keeps meaning
     * what it always meant, and precision bought by placement is a tilt rather than a fence.
     */
    data class Everywhere(val emphasised: Set<Slot> = emptySet()) : Scope {
        override fun reaches(everywhere: List<Slot>): List<Slot> = everywhere
    }

    /**
     * Confined to these slots — what a **restrictive or exact** word gets.
     *
     * A word that narrows candidates narrows where it speaks, which is what makes `flat land` a claim about
     * the land and nothing else.
     */
    data class Confined(val slots: Set<Slot>) : Scope {
        // An empty confinement means "nothing was declared and nothing aimed it", which has to read as
        // *wherever it finds purchase* rather than as nowhere — a word confined to no slot at all could
        // never be satisfied, and would be charged as unbacked for a fault of the grammar's.
        override fun reaches(everywhere: List<Slot>): List<Slot> =
            slots.ifEmpty { return everywhere }.sortedBy { it.ordinal }
    }
}

/**
 * What a writer asked to happen to a value — the `only`/`except` axis (design §4.3.1).
 *
 * Separate from the word rather than a property of it, because the same word means different things under
 * each: `andesite` names a substance, `only andesite` says the ground wears nothing else.
 */
enum class Polarity {
    /** Said plainly. Adds or strengthens; removes nothing. */
    ASSERTED,

    /** This and nothing else — the pin that naming alone deliberately never does (Jonah). */
    ONLY,

    /** Anything but this. Expressible today because `BiomePreference` weights are already signed. */
    EXCEPT,
}

/**
 * Words a writer joined with `and` — "keep both, and keep them apart" (§3.2).
 *
 * An identity rather than a list, so a [Constraint] stays a flat record and two constraints are in the same
 * group exactly when they carry the same one. Ungrouped is `null`, which is *not* the same as a group of
 * one: unjoined juxtaposition stays contention, because if juxtaposition already meant "and" then "and"
 * would mean nothing.
 */
@JvmInline
value class Group(val index: Int)

/**
 * One word, and everything the grammar decided about it.
 *
 * **No parser concepts appear here, deliberately.** This is the boundary described in §4.3.1: the resolver
 * consumes these and never sees a parse tree, so the parser can be replaced by rewriting one file. It is
 * also what lets `:common:resolvercheck` build sentences by hand without a parser at all.
 */
data class Constraint(
    val word: Word,
    val scope: Scope,
    val polarity: Polarity = Polarity.ASSERTED,
    /** Which `and`-group this joined, or null where it stood alone. */
    val group: Group? = null,
)

/**
 * A parsed book: what the Art could read, and what it could not.
 *
 * The two halves are §4.3's two failure channels kept apart at the type level. [constraints] is what was
 * understood and goes on to be resolved; [dropped] is what could not be read at all, and becomes
 * **vagueness** — the Age comes out less determined, and *no instability is charged*. Contradiction is a
 * separate matter entirely, discovered later by the resolver among constraints that parsed perfectly.
 *
 * Garbling your words makes an Age vaguer; contradicting yourself makes it unstable.
 */
data class Sentence(
    val constraints: List<Constraint>,
    /** Pages the Art could not read, in the order they were laid out — for telling the writer. */
    val dropped: List<String> = emptyList(),
) {
    val words: List<Word> get() = constraints.map { it.word }

    companion object {
        /**
         * A book with no structure — every word standing alone, unaimed.
         *
         * What a flat list of pages meant before the grammar existed, and what a check means when it wants
         * to exercise the resolver without a parser. That it can be built here at all is the boundary
         * earning its keep: `:common:resolvercheck` needs no grammar to run.
         */
        fun flat(words: List<Word>): Sentence = Sentence(
            words.map { word ->
                val scope = if (word.tier.narrows) Scope.Confined(word.slots) else Scope.Everywhere()
                Constraint(word, scope)
            },
        )
    }

    /** Whether anything at all was understood. An unreadable book still opens an Age — the pen never refuses. */
    val isEmpty: Boolean get() = constraints.isEmpty()
}
