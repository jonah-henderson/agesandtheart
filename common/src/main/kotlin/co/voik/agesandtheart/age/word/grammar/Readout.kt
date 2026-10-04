package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Rung

/**
 * The parse, said back word for word (design §4.3.1) — the instrument for seeing what attached to what,
 * where a book is read as [Prose].
 *
 * The grammar has no punctuation and no bracket — a writer lays out a flat row of pages and the sections
 * exist only in the parser — so the reading is invisible until something shows it. This inserts the
 * particles a writer was spared for being inferable from position (`over`, `under`) and the punctuation the
 * sections never had.
 *
 * Two rules govern every choice here, and both are the discipline the parser already keeps:
 *
 * - **It prettifies; it never launders.** Pages that reached no clause are **not** in it, so
 *   [Sentence.unreadable] and [Sentence.impossible] must be shown beside it — struck, marked, left
 *   untranslated — or the reading claims a book worked when it did not.
 * - **It shows what you said, never what it will make** (§7.5). This renders the sentence, not the Age.
 */
object Readout {
    /**
     * [sentence] as the words a writer says — what `/age write` prints and the desk shows. Empty where
     * nothing parsed, which the caller reports as the book being unreadable rather than as an Age with
     * nothing said about it.
     */
    fun of(sentence: Sentence): String {
        val clauses = sentence.phrases.mapNotNull(::asWritten)
        val said = headOf(sentence)
        if (clauses.isEmpty() && said.isEmpty()) return ""
        // A colon rather than the comma that separates clauses: a book is an Age and *then* what is true of
        // it, so the head is not one more thing said about the world alongside the rest.
        if (said.isNotEmpty() && clauses.isNotEmpty()) said.punctuate(":")
        for ((position, phrase) in clauses.withIndex()) {
            if (position > 0) said.punctuate(",")
            said += clauseOf(phrase, opensTheSentence = position == 0)
        }
        said.punctuate(".")
        return said.joinToString(" ")
    }

    /**
     * The `Age` page a book opens with — **the head of the reading**. It carries no constraint and so
     * reaches no [Phrase], which is why it has to be put back here rather than falling out of one.
     *
     * Read off [Sentence.structural], which the parser fills from the writer's pages alone: a repaired
     * book's nucleus is the Art's, and claiming one that was never written is the laundering §4.3.1 forbids.
     */
    private fun headOf(sentence: Sentence): MutableList<String> =
        if (Production.NUCLEUS !in sentence.structural) mutableListOf() else mutableListOf(NUCLEUS_PAGE)

    /** [mark] put against the word just laid. */
    private fun MutableList<String>.punctuate(mark: String) {
        val last = removeLastOrNull() ?: return
        this += last + mark
    }

    private const val CONFINED = "in"
    private const val WIDENED = "everywhere"

    /**
     * How the nucleus page is spelled. Written down here as `only`, `except` and the rungs already are,
     * rather than read off the page: a pack may rename any structural word and every one of them would still
     * be spelled our way.
     */
    private const val NUCLEUS_PAGE = "age"

    /**
     * One phrase with the Art's own pages taken out, or null where the writer laid none of it — a book
     * shows what its writer wrote (§4.3.1) and a repaired one is complete in ways they never asked for.
     *
     * **A latent subject survives wherever something written hangs off it**, because that subject is the
     * whole of what says where a re-homed page landed: a writer who wrote `landmass starless` is owed
     * "under sky starless", and rendering it as "starless" would launder the one thing they need told.
     */
    private fun asWritten(phrase: Phrase): Phrase? {
        val modifiers = phrase.modifiers.filterNot { it.latent }
        val subjectWasWritten = phrase.subject != null && !phrase.subject.latent
        if (modifiers.isEmpty() && !subjectWasWritten) return null
        val adopted = phrase.subject.takeIf { subjectWasWritten || modifiers.isNotEmpty() }
        return Phrase(modifiers, adopted, phrase.confinedTo, phrase.everywhere)
    }

    /** One phrase, as its own clause: what is said, and then the page it is said about. */
    private fun clauseOf(phrase: Phrase, opensTheSentence: Boolean): List<String> {
        val said = mutableListOf<String>()
        // The clause's own ground, said at the head, before the claims it governs.
        phrase.confinedTo?.let { biome ->
            said += CONFINED
            said += biome.path
            said.punctuate(",")
        }
        val preposition = if (opensTheSentence) "" else prepositionFor(phrase)
        if (preposition.isNotEmpty()) said += preposition
        // Runs the writer joined with `and` stay joined, because "keep both, and keep them apart" is a
        // different claim from two words laid side by side (§3.2).
        for ((position, run) in phrase.modifiers.chunkedByJoin().withIndex()) {
            if (position > 0) said.punctuate(",")
            said += runOf(run)
        }
        phrase.subject?.let { said += it.word.name }
        if (phrase.everywhere) said += WIDENED
        return said
    }

    /** One `and`-joined run, with whatever `only`/`except` the writer put in front of it. */
    private fun runOf(run: List<Constraint>): List<String> {
        val said = mutableListOf<String>()
        when (run.first().polarity) {
            Polarity.ASSERTED -> Unit
            Polarity.ONLY -> said += "only"
            Polarity.EXCEPT -> said += "except"
        }
        for ((position, term) in run.withIndex()) {
            if (position > 0) said += "and"
            said += termOf(term)
        }
        return said
    }

    /** One term, carrying the rung the writer quantified it with. */
    private fun termOf(term: Constraint): List<String> {
        val quantified = term.quantifier?.takeUnless { Rung.isOrdinary(term.density) }
        return listOfNotNull(quantified, term.word.name)
    }

    /**
     * Consecutive modifiers gathered into the runs a writer joined. A null group is a word standing alone,
     * which is never a run of one with the next word — unjoined juxtaposition has to keep meaning
     * contention.
     */
    private fun List<Constraint>.chunkedByJoin(): List<List<Constraint>> {
        val runs = mutableListOf<MutableList<Constraint>>()
        for (constraint in this) {
            val joinsTheRunBefore = constraint.group != null && constraint.group == runs.lastOrNull()?.last()?.group
            if (joinsTheRunBefore) runs.last() += constraint else runs += mutableListOf(constraint)
        }
        return runs
    }

    /**
     * How a clause is placed against the one before it. Vertical where the world is — a sea is under the
     * land and a sky is over it — and a plain comma everywhere else, since the aspects that are neither
     * above nor below have no honest preposition and an invented one would read as meaning something.
     */
    private fun prepositionFor(phrase: Phrase): String {
        val about = phrase.subject?.word?.aspects.orEmpty()
        return when {
            Aspect.SEA in about -> "over"
            Aspect.SKY in about -> "under"
            else -> ""
        }
    }
}
