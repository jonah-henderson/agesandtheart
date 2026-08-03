package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.word.Word

/**
 * The parse, said back as a sentence (design §4.3.1).
 *
 * The grammar has no punctuation and no bracket — a writer lays out a flat row of pages and the sections
 * exist only in the parser — so the reading is invisible until something shows it. This inserts the
 * particles a writer was spared for being inferable from position (`of`, `over`, `with`) and the
 * punctuation the sections never had, which is the neatest symmetry in the language: the particle dropped
 * from the input because position implied it is exactly the particle that best *shows* the position.
 *
 * Two rules govern every choice here, and both are the discipline the parser already keeps:
 *
 * - **It prettifies; it never launders.** The prose renders what parsed. Pages that reached no clause are
 *   **not** in it, so [Sentence.unreadable] and [Sentence.impossible] must be shown beside it — struck,
 *   marked, left untranslated — or the reading claims a book worked when it did not.
 * - **It shows what you said, never what it will make** (§7.5). This renders the sentence, not the Age.
 */
object Readout {
    /**
     * [sentence] as prose. Empty where nothing parsed, which the caller reports as the book being
     * unreadable rather than as an Age with nothing said about it.
     */
    fun of(sentence: Sentence): String {
        val clauses = sentence.phrases.mapNotNull(::asWritten)
        if (clauses.isEmpty()) return ""
        val read = clauses.mapIndexed { position, phrase ->
            val opensTheSentence = position == 0
            clauseOf(phrase, opensTheSentence)
        }
        return read.joinToString(", ") + "."
    }

    /**
     * One phrase with the Art's own pages taken out, or null where the writer laid none of it — a book
     * shows what its writer wrote (§4.3.1) and a repaired one is complete in ways they never asked for.
     *
     * **A latent subject survives wherever something written hangs off it**, because that subject is the
     * whole of what says where a re-homed page landed: a writer who wrote `landmass starless` is owed
     * "under sky starless", and rendering it as "starless" would launder the one thing they need told.
     */
    private fun asWritten(phrase: Phrase): Phrase? {
        val descriptors = phrase.descriptors.filterNot { it.latent }
        val modifiers = phrase.modifiers.filterNot { it.latent }
        val subjectWasWritten = phrase.subject != null && !phrase.subject.latent
        if (descriptors.isEmpty() && modifiers.isEmpty() && !subjectWasWritten) return null
        val adopted = phrase.subject.takeIf { subjectWasWritten || modifiers.isNotEmpty() }
        return Phrase(descriptors, adopted, modifiers)
    }

    /**
     * One phrase, as its own clause. [opensTheSentence] because the preposition that places a clause
     * against the one before it has nothing to place the first one against.
     */
    private fun clauseOf(phrase: Phrase, opensTheSentence: Boolean): String {
        val preposition = if (opensTheSentence) "" else prepositionFor(phrase)
        val described = (phrase.descriptors.map { it.word.name } + listOfNotNull(phrase.subject?.word?.name))
            .joinToString(" ")
        val steering = steeringOf(phrase)
        return listOf(preposition, described, steering).filter(String::isNotEmpty).joinToString(" ")
    }

    /**
     * Everything steering the subject. Runs the writer joined with `and` stay joined, because "keep both,
     * and keep them apart" is a different claim from two words laid side by side (§3.2) and a reading that
     * flattened them would hide the one page that changed it.
     */
    private fun steeringOf(phrase: Phrase): String {
        if (phrase.modifiers.isEmpty()) return ""
        val hasASubjectToAttachTo = phrase.subject != null
        val said = StringBuilder()
        var aParticleHasBeenSpent = false
        for ((position, run) in phrase.modifiers.chunkedByJoin().withIndex()) {
            // The first run of a subjectless phrase heads its own clause — "blackstone", not "of
            // blackstone", which would be waiting for a subject that was never written.
            val couldTakeAParticle = hasASubjectToAttachTo || position > 0
            val particle = if (couldTakeAParticle && !aParticleHasBeenSpent) particleFor(run) else ""
            // One `of` per clause. A second unjoined material is a rival claim rather than more of the
            // same, and "of basalt of slate" reads as neither.
            val followsAnAttachedRun = aParticleHasBeenSpent && particleFor(run).isNotEmpty()
            aParticleHasBeenSpent = aParticleHasBeenSpent || particle.isNotEmpty()
            if (position > 0) said.append(if (followsAnAttachedRun) ", " else " ")
            said.append(particle).append(runOf(run))
        }
        return said.toString()
    }

    /**
     * One `and`-joined run, with the particle that says how it attaches and whatever `only`/`except` the
     * writer put in front of it.
     */
    private fun runOf(run: List<Constraint>): String {
        val marker = when (run.first().polarity) {
            Polarity.ASSERTED -> ""
            Polarity.ONLY -> "only "
            Polarity.EXCEPT -> "except "
        }
        return marker + run.joinToString(" and ", transform = ::termOf)
    }

    /** One term, carrying the rung the writer quantified it with where they asked for one. */
    private fun termOf(term: Constraint): String =
        if (term.density.isOrdinary) term.word.name else "${term.density.key} ${term.word.name}"

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
     * `with` where the words name things that are *present* — biomes grown, structures built — and `of`
     * where they say what the subject *is made of*. The same distinction [Parameter.Kind] draws, asked of
     * the parameters the words actually set.
     *
     * **No particle at all where the run steers nothing**, because a word that names a preset is a second
     * claim on the same aspect rather than a property of the subject: `riddled flooded` is two things said
     * about the carvers, and "riddled *of* flooded" would read as one made out of the other.
     */
    private fun particleFor(run: List<Constraint>): String {
        val steersNothing = run.none { it.word.sets.isNotEmpty() }
        // `only` and `except` are pages the writer laid down and already say how the run attaches —
        // "except of blackstone" is not a sentence, and the particle earns nothing beside them.
        val alreadyMarked = run.first().polarity != Polarity.ASSERTED
        if (steersNothing || alreadyMarked) return ""
        val namesThingsPresent = run.any { isPopulative(it.word) }
        return if (namesThingsPresent) "with " else "of "
    }

    private fun isPopulative(word: Word): Boolean {
        val aspectsItSpeaksTo = word.aspects.ifEmpty { Aspect.entries.toSet() }
        fun anyPresetCallsItPopulative(parameter: String) =
            aspectsItSpeaksTo.any { aspect ->
                aspect.authored.any { preset ->
                    preset.parameters.any { it.name == parameter && it.kind == Parameter.Kind.POPULATIVE }
                }
            }
        return word.sets.keys.any(::anyPresetCallsItPopulative)
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
