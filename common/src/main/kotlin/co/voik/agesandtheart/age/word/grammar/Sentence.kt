package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
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
        // An empty confinement is "nothing declared and nothing aimed it", which reads as *wherever it
        // finds purchase* rather than nowhere — a word confined to no aspect could never be satisfied, and
        // would be charged as unbacked for a fault of the grammar's.
        override fun reaches(everywhere: List<Aspect>): List<Aspect> =
            aspects.ifEmpty { return everywhere }.sortedBy { it.ordinal }
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
)

/**
 * A parsed book: what the Art could read, and what it could not — §4.3's two failure channels kept apart
 * at the type level. [dropped] becomes **vagueness** and is charged nothing; contradiction is a separate
 * matter the resolver finds later among constraints that parsed perfectly.
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
         * A book with no structure — every word standing alone, unaimed. What a check means when it wants
         * to exercise the resolver without a parser.
         */
        fun flat(words: List<Word>): Sentence = Sentence(
            words.map { word ->
                val scope = if (word.tier.narrows) Scope.Confined(word.aspects) else Scope.Everywhere()
                Constraint(word, scope)
            },
        )
    }

    /** Whether anything at all was understood. An unreadable book still opens an Age — the pen never refuses. */
    val isEmpty: Boolean get() = constraints.isEmpty()
}
