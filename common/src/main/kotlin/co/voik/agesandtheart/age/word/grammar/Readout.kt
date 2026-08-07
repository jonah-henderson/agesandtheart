package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.WordNames
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

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
/**
 * One column of a reading: **a word as the Art writes it, and what it says**.
 *
 * [written] is plain letters — a page's own name, or a particle the Art supplied — which the script spells
 * wherever the reading is drawn, so a pack that retunes its transliteration retunes every book already
 * written. It is deliberately not localised: a book says the same thing to everyone holding it.
 *
 * [read] is the same column in the reader's language: a name a pack translates for a page, and the particle
 * itself for a particle, since the particles are §4.1's English-as-interface doing its job.
 *
 * Punctuation is carried on both, against the word it followed — a comma belongs *to* a column rather than
 * standing in one of its own.
 */
data class Said(val written: String, val read: Component) {
    companion object {
        val CODEC: Codec<Said> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("written").forGetter(Said::written),
                ComponentSerialization.CODEC.fieldOf("read").forGetter(Said::read),
            ).apply(instance, ::Said)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, Said> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            Said::written,
            ComponentSerialization.STREAM_CODEC,
            Said::read,
            ::Said,
        )
    }
}

object Readout {
    /**
     * [sentence] as prose, in the words a writer says — what `/age write` prints. Empty where nothing
     * parsed, which the caller reports as the book being unreadable rather than as an Age with nothing
     * said about it.
     */
    fun of(sentence: Sentence): String = columnsOf(sentence).joinToString(" ") { it.written }

    /** [columns] run together in the language their reader speaks, which is what a tooltip has room for. */
    fun asProse(columns: List<Said>): Component {
        val said = Component.empty()
        for ((position, column) in columns.withIndex()) {
            if (position > 0) said.append(" ")
            said.append(column.read)
        }
        return said
    }

    /**
     * **The reading, column by column** — the shape a book is set from, and the one everything else here
     * is derived from so that no two renderings of a sentence can drift apart.
     *
     * A column is one thing standing in one place: a page the writer laid, or a word the Art supplied to
     * show where that page sits. Keeping them apart is what lets a book set the script over its reading
     * word for word, which is the whole of how a player comes to know the language.
     */
    fun columnsOf(sentence: Sentence): List<Said> {
        val clauses = sentence.phrases.mapNotNull(::asWritten)
        val said = headOf(sentence)
        if (clauses.isEmpty() && said.isEmpty()) return emptyList()
        // A colon rather than the comma that separates clauses: a book is an Age and *then* what is true of
        // it, so the head is not one more thing said about the world alongside the rest.
        if (said.isNotEmpty() && clauses.isNotEmpty()) said.punctuate(":")
        for ((position, phrase) in clauses.withIndex()) {
            if (position > 0) said.punctuate(",")
            said += clauseOf(phrase, opensTheSentence = position == 0)
        }
        said.punctuate(".")
        return said.toList()
    }

    /**
     * The `Age` page a book opens with, as its own column — **the head of the reading, and a page like any
     * other**.
     *
     * It carries no constraint and so reaches no [Phrase], which is why it has to be put back here rather
     * than falling out of one. Without it a book's columns are its pages *less one*, and since the script is
     * set from these columns and not from the row of pages, the glyph for the page every book must open with
     * was drawn nowhere in the game — so a player learning the language by reading found books never met it.
     *
     * Read off [Sentence.structural], which `ArtGrammar` fills from the writer's pages alone: a repaired
     * book's nucleus is the Art's, and claiming one that was never written is the laundering §4.3.1 forbids.
     */
    private fun headOf(sentence: Sentence): MutableList<Said> =
        if (Production.NUCLEUS !in sentence.structural) mutableListOf()
        else mutableListOf(Said(NUCLEUS_PAGE, Component.literal(NUCLEUS_READ)))

    /**
     * [mark] put against the column just laid.
     *
     * Punctuation belongs *to* a word rather than beside it: a comma standing in a column of its own would
     * be a glyph with nothing above it and a gap either side.
     */
    private fun MutableList<Said>.punctuate(mark: String) {
        val last = removeLastOrNull() ?: return
        this += Said(last.written + mark, Component.empty().append(last.read).append(mark))
    }

    /** A page the writer laid: its own name to be spelled, and what a pack calls it. */
    private fun pageFor(word: Word): Said = Said(word.name, WordNames.readable(word.id))

    /** How `in` reads, written down beside `only` and the rungs for the reason given there. */
    private const val CONFINED = "in"

    /** A word the Art supplied — English on both sides, since that is §4.1's interface doing its job. */
    private fun particleFor(text: String): Said = Said(text, Component.literal(text))

    /**
     * How the nucleus page is spelled, and how it reads at the head of a sentence.
     *
     * Written down here as `only`, `except` and the rungs already are, rather than read off the page: a pack
     * may rename any structural word and every one of them would still be spelled our way. That is one
     * limitation shared by all five and worth lifting for all five at once, not a new one taken on here.
     */
    private const val NUCLEUS_PAGE = "age"
    private const val NUCLEUS_READ = "Age"

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
        return Phrase(modifiers, adopted, phrase.confinedTo)
    }

    /**
     * One phrase, as its own clause: what is said, and then the page it is said about.
     *
     * **No particle attaches a modifier to its subject any more**, and that is the reversal paying for
     * itself (§4.3.1). A trailing modifier needed one — `landmass of pillars`, a word the writer never
     * laid and the readout had to decide when to spend — where a leading one simply stands in front of
     * what it modifies, the way an English noun phrase does. `pillars and hills landmass` is the pages
     * back in their own order, and everything that chose between `of` and `with` and nothing is gone.
     */
    private fun clauseOf(phrase: Phrase, opensTheSentence: Boolean): List<Said> {
        val said = mutableListOf<Said>()
        // The clause's own ground, said before the claims it governs — which is the order the writer laid
        // the pages in, and the whole reason `in` sits at the head rather than after a term.
        phrase.confinedTo?.let { biome ->
            said += particleFor(CONFINED)
            said += Said(biome.path, WordNames.readable(biome))
            said.punctuate(",")
        }
        val preposition = if (opensTheSentence) "" else prepositionFor(phrase)
        if (preposition.isNotEmpty()) said += particleFor(preposition)
        // Runs the writer joined with `and` stay joined, because "keep both, and keep them apart" is a
        // different claim from two words laid side by side (§3.2).
        for ((position, run) in phrase.modifiers.chunkedByJoin().withIndex()) {
            if (position > 0) said.punctuate(",")
            said += runOf(run)
        }
        phrase.subject?.let { said += pageFor(it.word) }
        return said
    }


    /**
     * One `and`-joined run, with whatever `only`/`except` the writer put in front of it. The `and` is a
     * column of its own, because the writer laid a page for it and the reader should see one.
     */
    private fun runOf(run: List<Constraint>): List<Said> {
        val said = mutableListOf<Said>()
        when (run.first().polarity) {
            Polarity.ASSERTED -> Unit
            Polarity.ONLY -> said += particleFor("only")
            Polarity.EXCEPT -> said += particleFor("except")
        }
        for ((position, term) in run.withIndex()) {
            if (position > 0) said += particleFor("and")
            said += termOf(term)
        }
        return said
    }

    /**
     * One term, carrying the rung the writer quantified it with and the biome they confined it to.
     *
     * Both are pages the writer laid, so both are said back: a reading that dropped the `in` would show a
     * claim about the whole Age where the book says one about a corner of it, which is the attachment this
     * whole readout exists to make visible (§4.3.1).
     */
    private fun termOf(term: Constraint): List<Said> {
        val quantified = term.quantifier?.takeUnless { Rung.isOrdinary(term.density) }
        return buildList {
            quantified?.let { add(particleFor(it)) }
            add(pageFor(term.word))

        }
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
