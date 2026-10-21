package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.preview.authoring.Candidate
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.WordFile

/**
 * The sections of the word screen, in the order the decisions fall: what it is called and costs, what it means,
 * then the review, the comment and the file.
 */
enum class Part(
    val title: String,
    val about: String,
    /** A blank line before it and nothing else — the same grouping the tool's own menu uses. */
    val startsGroup: Boolean = false,
    val perilous: Boolean = false,
) {
    NAME("word", ""),
    COST("cost", "read off what it means: the sections below are how it changes"),
    LISTING("rarity", ""),

    /** The word itself: each meaning one idea, with how likely it is to apply. */
    MEANINGS("meanings", "each one idea, and how likely it is to apply — aimed, and laid bare", startsGroup = true),

    REVIEW("review", "where it lands, what it takes and what takes it", startsGroup = true),
    COMMENT("comment", ""),

    SAVE("save", "", startsGroup = true),
    SAVE_AND_LEAVE("save & exit", ""),

    /** Last, and marked, because it is the one thing here that cannot be undone. */
    DELETE("delete", "", perilous = true),
}

/** One line of a section: what it says, and the handle the editor acts on. [note] shows under the list. */
data class Row(val handle: Handle, val shown: List<Ink>, val note: String = "")

/** What a row is, which is what the editor acts on when the cursor rests on it. */
sealed interface Handle {
    /** A line the cursor passes over. */
    data object Heading : Handle

    /** A line that is read and never acted on. */
    data object Said : Handle

    data object Name : Handle

    data object Display : Handle

    data object Speech : Handle

    data object Rarity : Handle

    data object InkQuality : Handle

    /** A meaning's own row, which holds its chances. */
    data class MeaningHead(val meaning: Int) : Handle

    /**
     * One entry of one effect: the whole effect where [key] is null, or the member or tag [key] it names. [isAWeight]
     * where the entry is a number stepped on its row — a lean's weight or a bar.
     */
    data class Entry(val meaning: Int, val effect: Int, val key: String?, val isAWeight: Boolean = false) : Handle

    data class AddEffect(val meaning: Int) : Handle

    data object AddMeaning : Handle

    data object Comment : Handle

    data class Save(val andLeave: Boolean) : Handle

    data object Delete : Handle
}

/** What each section shows for a word: read-only and pure, so nothing on screen is worked out twice. */
class Parts(private val corpus: Corpus) {

    fun rowsOf(part: Part, candidate: Candidate, word: Word): List<Row> = when (part) {
        Part.NAME -> nameRows(candidate)
        Part.COST -> costRows(word)
        Part.LISTING -> listingRows(candidate)
        Part.MEANINGS -> meaningRows(candidate)
        Part.REVIEW -> reviewRows(word)
        Part.COMMENT -> commentRows(candidate)
        Part.SAVE -> listOf(Row(Handle.Save(andLeave = false), listOf(Ink("write the file", Palette.value))))
        Part.SAVE_AND_LEAVE -> listOf(Row(Handle.Save(andLeave = true), listOf(Ink("write the file and leave", Palette.value))))
        Part.DELETE -> listOf(Row(Handle.Delete, listOf(Ink("delete this word", Palette.refused)), "the file, its rarity, its ink and its name"))
    }

    /** Whether this section holds a list you add to and delete from, which decides what `a` and `d` mean. */
    fun isAList(part: Part) = part == Part.MEANINGS

    fun isAHeading(row: Row): Boolean = row.handle == Handle.Heading

    /** What the row under the cursor can do — the contextual half of the key line. */
    fun keysFor(row: Row?): List<Pair<String, String>> = when (val handle = row?.handle) {
        null, Handle.Heading, Handle.Said -> emptyList()
        Handle.Name, Handle.Display -> listOf("enter" to "rename it")
        Handle.Speech, Handle.Rarity, Handle.InkQuality -> listOf("enter" to "choose one")
        is Handle.MeaningHead -> listOf(
            "enter" to "how likely", "- =" to "the chance aimed", "m" to "must always", "s" to "asserts", "d" to "remove it",
        )
        is Handle.Entry -> if (handle.isAWeight) {
            listOf("enter" to "type it", "- =" to "step it", "d" to "remove it")
        } else {
            listOf("enter" to "change it", "d" to "remove it")
        }
        is Handle.AddEffect -> listOf("enter" to "add to this meaning")
        Handle.AddMeaning -> listOf("enter" to "a new meaning")
        Handle.Comment -> listOf("enter" to "open \$EDITOR")
        is Handle.Save -> listOf("enter" to "write the file")
        Handle.Delete -> listOf("enter" to "delete the word")
    }

    private fun spacer() = Row(Handle.Heading, emptyList())

    // -- the word -----------------------------------------------------------------------------------

    private fun nameRows(candidate: Candidate): List<Row> {
        val id = candidate.listingKey
        val called = WordFile.displayOf(id)
        return listOf(
            Row(
                Handle.Name,
                listOf(Ink("id       ", Palette.faint), Ink(candidate.name.ifBlank { "(unnamed)" }, Palette.value)),
                "the file name, and what a book is written in" +
                    if (WordFile.exists(candidate.name)) "\n    a file already exists for this" else "",
            ),
            Row(
                Handle.Display,
                listOf(
                    Ink("shown as ", Palette.faint),
                    Ink(called ?: WordFile.fallbackDisplay(id), if (called == null) Palette.faint else Palette.value),
                ),
                "what a player reads — on the page, the desk and the book",
            ),
            Row(
                Handle.Speech,
                listOf(Ink("said as  ", Palette.faint), Ink(candidate.speech?.serializedName ?: "an adjective", Palette.value)),
                "how a book's prose says it: of something, one of many, already plural, or a substance",
            ),
        )
    }

    private fun costRows(word: Word): List<Row> {
        val certain = word.meanings.filter { it.mustAlwaysApply || it.chanceToApply == Meaning.CERTAIN }
        val parts = certain.flatMap { it.effects }.filter(Effect::narrows).flatMap(Word::partsNarrowedBy).toSet()
        val spelled = if (parts.isEmpty()) {
            "narrows nothing certain, so the least a page costs"
        } else {
            "${Word.INK_PER_NARROWED_PART} ink × ${parts.size}: ${parts.sortedBy { it.ordinal }.joinToString(" ") { it.page }}"
        }
        return listOf(
            Row(Handle.Said, listOf(Ink("ink      ", Palette.faint), Ink("${word.price}", Palette.value), Ink("   $spelled", Palette.faint))),
            Row(
                Handle.Said,
                listOf(Ink("free     ", Palette.faint), Ink("leans, bends, abundance and what only might apply", Palette.faint)),
            ),
        )
    }

    private fun listingRows(candidate: Candidate): List<Row> {
        val listing = WordFile.listingOf(candidate)
        return listOf(
            Row(
                Handle.Rarity,
                listOf(Ink("rarity   ", Palette.faint), Ink(listing.rarity ?: WordFile.defaultBucket() ?: "unlisted", Palette.value)),
                "how often a found page or notebook holds it",
            ),
            Row(
                Handle.InkQuality,
                listOf(Ink("ink      ", Palette.faint), Ink(WordFile.inkOf(candidate) ?: "any", Palette.value)),
                "the least ink that writes it",
            ),
        )
    }

    // -- the meanings ------------------------------------------------------------------------------

    private fun meaningRows(candidate: Candidate): List<Row> = buildList {
            for ((at, meaning) in candidate.meanings.withIndex()) {
                add(Row(Handle.MeaningHead(at), meaningHead(at, meaning), chancesNote(meaning)))
                for ((index, effect) in meaning.effects.withIndex()) {
                    for ((key, shown) in entriesOf(effect)) {
                        val handle = Handle.Entry(at, index, key, isAWeight = key != null && isAWeight(effect))
                        add(Row(handle, listOf(Ink("    ")) + shown, noteOn(effect)))
                    }
                }
                add(Row(Handle.AddEffect(at), listOf(Ink("    + ", Palette.chosen), Ink("add to this meaning", Palette.faint))))
                add(spacer())
            }
            add(Row(Handle.AddMeaning, listOf(Ink("+ ", Palette.chosen), Ink("a new meaning", Palette.faint))))
    }

    private fun isAWeight(effect: Effect) =
        effect is Effect.Leans || effect is Effect.LeansEverywhere || effect is Effect.Restricts

    private fun meaningHead(at: Int, meaning: Meaning): List<Ink> {
        val how = when {
            meaning.mustAlwaysApply -> "must always apply"
            else -> {
                val aimed = "${percent(meaning.chanceToApply)} aimed"
                val bare = meaning.unaimedChanceToApply?.let { "${percent(it)} bare" }
                    ?: "${percent(meaning.chanceToApply * Meaning.BARE_CHANCE)} bare"
                "$aimed, $bare"
            }
        }
        return listOf(
            Ink("meaning ${at + 1}  ", Palette.heading),
            Ink(how, Palette.value),
            Ink(if (meaning.asserts) "  · asserts" else "", Palette.chosen),
            Ink(if (meaning.isOffered) "  · offered to ${meaning.offeredTo.sorted().joinToString(" ")}" else "", Palette.chosen),
        )
    }

    private fun chancesNote(meaning: Meaning): String = buildString {
        append("how likely it is to apply\n")
        append("    aimed, a chance; laid bare, its own or the aimed chance times the Art's ${percent(Meaning.BARE_CHANCE)}\n")
        append("    must always apply: aimed, it does; laid bare, at one place it reaches at least\n")
        if (meaning.asserts) append("    asserts: it needs nothing to land on, and lands alone where nothing takes it\n")
        if (meaning.isOffered) append("    offered: it applies only where one of the words it is offered to takes it, and lands whole there")
    }

    private fun percent(share: Double) = "${(share * PERCENT).toInt()}%"

    /** Each row an effect is shown as: one, or one per member or tag it names. */
    private fun entriesOf(effect: Effect): List<Pair<String?, List<Ink>>> = when (effect) {
        is Effect.Sets -> listOf(null to said("sets", effect.parameter.spelled, effect.value))
        is Effect.Bends -> listOf(null to said("bends", effect.parameter.spelled, effect.value))
        is Effect.Chooses -> listOf(null to said("chooses", effect.aspect.page, effect.member))
        is Effect.Admits -> effect.members.sorted().map { it to said("adds", effect.aspect.page, it) }
        is Effect.Excludes -> effect.members.sorted().map { it to said("strikes", effect.aspect.page, it) }
        is Effect.Restricts -> effect.bars.entries.map { (tag, bar) -> tag to said("keeps", effect.aspect.page, "#$tag ${Bars.spell(bar)}") }
        is Effect.Leans -> effect.weights.entries.map { (named, weight) -> named to said("leans", effect.aspect.page, "$named ${signed(weight)}") }
        is Effect.LeansEverywhere -> effect.weights.entries.map { (named, weight) -> named to said("leans", "everything", "$named ${signed(weight)}") }
        is Effect.Mints -> listOf(null to said("mints", effect.pattern, mintedOf(effect)))
        is Effect.Template -> listOf(null to said("begins", "from", effect.name))
        is Effect.Abundance -> listOf(null to said("asks", "for", "${Rung.spelled(effect.rung)} times as many"))
        is Effect.MadeOf -> listOf(null to said("made", "of", effect.substance))
        is Effect.Bans -> listOf(null to said("bans", "", effect.block))
        is Effect.LinksIn -> listOf(null to said("links in", effect.anchor.aspect.page, effect.anchor.members.joinToString(" ")))
    }

    private fun mintedOf(mints: Effect.Mints): String {
        val of = mints.unstated?.let { "of $it" } ?: "of what its clause lays"
        return if (mints.flows) "$of, flowing" else of
    }

    private fun said(verb: String, where: String, what: String) = listOf(
        Ink(verb.padEnd(VERB_WIDTH), Palette.faint),
        Ink(where.padEnd(WHERE_WIDTH), Palette.parameter),
        Ink(what, Palette.value),
    )

    private fun signed(weight: Double) = if (weight >= 0) "+$weight" else "$weight"

    /** What a parameter is for, and every part of the world an unfenced one reaches. */
    private fun noteOn(effect: Effect): String {
        val key = (effect as? Effect.Sets)?.parameter ?: (effect as? Effect.Bends)?.parameter ?: return ""
        val parts = Aspect.entries.filter(key::landsOn)
        val parameter = parts.firstNotNullOfOrNull { aspect -> aspect.understood.firstOrNull { it.name == key.name } }
        val reach = if (key.fencedTo == null && parts.size > 1) "\n    lands on ${parts.joinToString(" ") { it.page }}" else ""
        return (parameter?.help ?: key.name) + reach
    }

    // -- where it lands ------------------------------------------------------------------------------

    private fun reviewRows(word: Word): List<Row> {
        val lexicon = corpus.vocabulary.lexicon
        val bare = lexicon.placesFor(word).sortedBy { it.ordinal }
        val acceptance = lexicon.acceptance(word)
        fun takesAny(page: Word) = lexicon.acceptance(page)?.let { taking -> word.effects.any { taking.take(it) != null } } == true
        val takers = Aspect.entries.mapNotNull { aspect -> corpus.vocabulary.word(aspect.page) }.filter(::takesAny)
        fun line(label: String, said: String) = Row(Handle.Said, listOf(Ink(label.padEnd(LABEL_WIDTH), Palette.faint), Ink(said, Palette.value)))
        return listOf(
            line("laid bare", bare.joinToString(" ") { it.page }.ifEmpty { "lands nowhere" }),
            line("it takes", acceptance?.let { "what is laid before it, at ${it.place.aspect.page}" } ?: "nothing; it waits for a word that takes it"),
            line("taken by", takers.joinToString(" ") { it.name }.ifEmpty { "no aspect's page" }),
            line("asserts", if (word.meanings.any(Meaning::asserts)) "something, so it lands alone where nothing takes it" else "nothing"),
            spacer(),
            Row(Handle.Said, listOf(Ink("^t reads it in a book", Palette.faint))),
        )
    }

    private fun commentRows(candidate: Candidate): List<Row> =
        candidate.comment.map { Row(Handle.Said, listOf(Ink(it, Palette.faint))) } +
            Row(Handle.Comment, listOf(Ink(if (candidate.comment.isEmpty()) "write one in \$EDITOR" else "edit it in \$EDITOR", Palette.value)))

    companion object {
        private const val VERB_WIDTH = 9
        private const val WHERE_WIDTH = 14
        private const val LABEL_WIDTH = 11
        private const val PERCENT = 100
    }
}
