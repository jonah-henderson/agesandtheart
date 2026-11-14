package co.voik.agesandtheart.preview.authoring.words.ui

import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.reading.StructureWord
import co.voik.agesandtheart.age.reading.WordsInTheWorld
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Price
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueFilter
import co.voik.agesandtheart.preview.authoring.ui.Ink
import co.voik.agesandtheart.preview.authoring.ui.Palette
import co.voik.agesandtheart.preview.authoring.words.Candidate
import co.voik.agesandtheart.preview.authoring.words.WordCorpus
import co.voik.agesandtheart.preview.authoring.words.WordFile
import co.voik.agesandtheart.preview.authoring.words.WordListings
import com.github.ajalt.mordant.rendering.TextStyle

/** The sections of the word screen, in the order the decisions fall: what it is called, how it is listed, what it is. */
enum class Section(
    val title: String,
    val about: String,
    /** A blank line before it and nothing else, the grouping the tool's menu uses. */
    val startsGroup: Boolean = false,
    val perilous: Boolean = false,
) {
    WORD("word", ""),
    LISTING("listing", "kept away from the word's file: how often it is found, the ink it asks, what it is called"),
    SENSES("senses", "each a way it can be read: what it offers the words after it, what it takes, and what it means itself", startsGroup = true),
    REVIEW("review", "what takes each offer among every word, and what it becomes there"),
    COMMENT("comment", "", startsGroup = true),
    SAVE("save", "", startsGroup = true),
    SAVE_AND_LEAVE("save & exit", ""),

    /** Last, and marked, because it is the one thing here that cannot be undone. */
    DELETE("delete", "", perilous = true),
}

/** One line of a section: what it says, and the spot the editor acts on. [note] shows under the list. */
data class Row(val spot: Spot, val shown: List<Ink>, val note: String = "")

/** What a row is, which is what the editor acts on when the cursor rests on it. */
sealed interface Spot {
    /** A line the cursor passes over. */
    data object Heading : Spot

    /** A line that is read and never acted on. */
    data object Said : Spot

    data object Name : Spot

    data object Speech : Spot

    data object Price : Spot

    data object Display : Spot

    data object Rarity : Spot

    data object InkQuality : Spot

    /** A sense's own row: whether it is a thing, of which part and kind, and whether it is the default. */
    data class SenseHead(val sense: Int) : Spot

    data class OfferAt(val sense: Int, val offer: Int) : Spot

    /** One entry of an effect a reading carries: the whole effect where [key] is null, or the member or tag it names. */
    data class ReadingEntry(val sense: Int, val offer: Int, val effect: Int, val key: String?) : Spot

    data class AcceptanceAt(val sense: Int, val acceptance: Int) : Spot

    /** One effect an acceptance makes of what it takes. */
    data class AcceptanceEffect(val sense: Int, val acceptance: Int, val effect: Int) : Spot

    /** One entry of what a sense means itself. */
    data class MeansEntry(val sense: Int, val effect: Int, val key: String?) : Spot

    data class AddOffer(val sense: Int) : Spot

    data class AddReading(val sense: Int) : Spot

    data class AddAcceptance(val sense: Int) : Spot

    data class AddMeaning(val sense: Int) : Spot

    data object AddSense : Spot

    data object Comment : Spot

    data class Save(val andLeave: Boolean) : Spot

    data object Delete : Spot
}

/** What each section shows for a word: read-only, so nothing on screen is worked out twice. */
class Sections(private val corpus: WordCorpus) {

    /** The words in the world with the last candidate reviewed beside them, kept while it stands: a review sweeps every taker. */
    private var reviewed: Pair<Candidate, WordsInTheWorld>? = null

    fun rowsOf(section: Section, candidate: Candidate): List<Row> = when (section) {
        Section.WORD -> wordRows(candidate)
        Section.LISTING -> listingRows(candidate)
        Section.SENSES -> senseRows(candidate)
        Section.REVIEW -> reviewRows(candidate)
        Section.COMMENT -> commentRows(candidate)
        Section.SAVE -> listOf(Row(Spot.Save(andLeave = false), listOf(Ink("write the file", Palette.value))))
        Section.SAVE_AND_LEAVE -> listOf(Row(Spot.Save(andLeave = true), listOf(Ink("write the file and leave", Palette.value))))
        Section.DELETE -> listOf(Row(Spot.Delete, listOf(Ink("delete this word", Palette.refused)), "the file, its rarity, its ink and its name"))
    }

    /** What the row under the cursor can do — the contextual half of the key line. */
    fun keysFor(row: Row?): List<Pair<String, String>> = when (row?.spot) {
        null, Spot.Heading, Spot.Said -> emptyList()
        Spot.Name, Spot.Display, Spot.Price -> listOf("enter" to "type it")
        Spot.Speech, Spot.Rarity, Spot.InkQuality -> listOf("enter" to "choose one")
        is Spot.SenseHead -> listOf("enter" to "what it is", "a" to "an offer", "d" to "remove the sense")
        is Spot.OfferAt -> listOf("enter" to "change it", "- =" to "its chance", "d" to "remove it")
        is Spot.ReadingEntry, is Spot.MeansEntry -> listOf("enter" to "change it", "a" to "another effect", "- =" to "a weight", "d" to "remove it")
        is Spot.AcceptanceAt -> listOf("enter" to "change it", "a" to "an effect it makes", "d" to "remove it")
        is Spot.AcceptanceEffect -> listOf("enter" to "change it", "a" to "another effect", "d" to "remove it")
        is Spot.AddOffer -> listOf("enter" to "a new offer")
        is Spot.AddReading -> listOf("enter" to "a new reading")
        is Spot.AddAcceptance -> listOf("enter" to "a new acceptance")
        is Spot.AddMeaning -> listOf("enter" to "a new meaning of its own")
        Spot.AddSense -> listOf("enter" to "a new sense")
        Spot.Comment -> listOf("enter" to "open \$EDITOR")
        is Spot.Save -> listOf("enter" to "write the file")
        Spot.Delete -> listOf("enter" to "delete the word")
    }

    // -- the word -----------------------------------------------------------------------------------

    private fun wordRows(candidate: Candidate): List<Row> {
        val word = candidate.word
        val price = Price.of(word, corpus.words.concepts)
        val priceSaid = if (word.price == null) "$price, read off what it offers and means" else "${word.price}, as its file says"
        return listOf(
            Row(
                Spot.Name,
                labelled("name", candidate.name, Palette.value),
                "the file name, and what a book is written in" + if (WordFile.exists(candidate.name)) "\n    a file already exists for this" else "",
            ),
            Row(Spot.Speech, labelled("said as", word.speech?.serializedName ?: "an adjective", Palette.value), "how a book's prose says it"),
            Row(
                Spot.Price,
                labelled("ink", priceSaid, Palette.value),
                "what a page costs to write: each concept it offers for certain, and ${Price.INK_PER_NARROWED_PART} for each part its " +
                    "own meaning narrows\n    type a number to fix it, or nothing to read it off again",
            ),
        )
    }

    private fun listingRows(candidate: Candidate): List<Row> {
        val listed = WordListings.Listed.authored(candidate.name)
        val listing = WordListings.listingOf(listed)
        val called = WordListings.displayOf(candidate.name)
        return listOf(
            Row(
                Spot.Display,
                labelled("shown as", called ?: WordListings.fallbackDisplay(candidate.name), if (called == null) Palette.faint else Palette.value),
                "what a player reads: on the page, the desk and the book",
            ),
            Row(
                Spot.Rarity,
                labelled("rarity", listing.rarity ?: WordListings.defaultBucket() ?: "unlisted", Palette.value),
                "how often a found page or notebook holds it",
            ),
            Row(Spot.InkQuality, labelled("ink", WordListings.inkOf(listed) ?: "any", Palette.value), "the least ink that writes it"),
        )
    }

    // -- the senses ----------------------------------------------------------------------------------

    private fun senseRows(candidate: Candidate): List<Row> = buildList {
        val senses = candidate.word.senses
        for ((at, written) in senses.withIndex()) {
            val sense = withItsKind(written)
            add(Row(Spot.SenseHead(at), senseHead(at, sense, isOneOfSeveral = senses.size > 1), senseNote(sense)))
            add(heading("offers"))
            for ((index, offer) in sense.offers.withIndex()) {
                add(Row(Spot.OfferAt(at, index), listOf(Ink(INDENT)) + offerSaid(offer), offerNote(offer)))
                for ((effectAt, effect) in offer.reading.withIndex()) {
                    for ((key, shown) in entriesOf(effect)) add(Row(Spot.ReadingEntry(at, index, effectAt, key), listOf(Ink(INDENT + INDENT)) + shown))
                }
            }
            add(adding(Spot.AddOffer(at), "an offer"))
            add(adding(Spot.AddReading(at), "a reading", "what only this word means of one part, which no concept says"))
            add(heading("takes"))
            for ((index, acceptance) in sense.accepts.withIndex()) {
                add(Row(Spot.AcceptanceAt(at, index), listOf(Ink(INDENT)) + acceptanceSaid(acceptance), ACCEPTANCE_NOTE))
                val made = (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty()
                for ((effectAt, template) in made.withIndex()) {
                    add(Row(Spot.AcceptanceEffect(at, index, effectAt), listOf(Ink(INDENT + INDENT), Ink(templateSaid(template), Palette.value))))
                }
            }
            for (inherited in sense.acceptances.drop(sense.accepts.size)) {
                add(Row(Spot.Said, listOf(Ink(INDENT)) + acceptanceSaid(inherited) + Ink("  from its kind", Palette.faint)))
            }
            add(adding(Spot.AddAcceptance(at), "an acceptance", "a concept this takes from the words before it, its own over its kind's"))
            add(heading("means"))
            for ((index, effect) in sense.means.withIndex()) {
                for ((key, shown) in entriesOf(effect)) add(Row(Spot.MeansEntry(at, index, key), listOf(Ink(INDENT)) + shown))
            }
            add(adding(Spot.AddMeaning(at), "a meaning of its own", "what it means itself where it ends a phrase"))
            add(Row(Spot.Heading, emptyList()))
        }
        add(Row(Spot.AddSense, listOf(Ink("+ ", Palette.chosen), Ink("a sense", Palette.faint))))
    }

    private fun adding(spot: Spot, what: String, note: String = "") =
        Row(spot, listOf(Ink(INDENT), Ink("+ ", Palette.chosen), Ink(what, Palette.faint)), note)

    /** [sense] with its kind attached, as loading would attach it, so whether it is a thing and its part read true. */
    fun withItsKind(sense: Sense): Sense = sense.kind?.let(corpus.words.kinds::get)?.let(sense::withKind) ?: sense

    private fun heading(said: String) = Row(Spot.Heading, listOf(Ink("  $said", Palette.faint)))

    private fun senseHead(at: Int, sense: Sense, isOneOfSeveral: Boolean): List<Ink> {
        val role = if (sense.endsAPhrase) "a thing" else "a describing word"
        val part = sense.speaksFor?.let { " of the ${it.page}" }.orEmpty()
        val kind = sense.kind?.let { " · a ${it.path}" }.orEmpty()
        val default = if (isOneOfSeveral && sense.isDefault) "  · the default" else ""
        val whole = if (sense.consumesWholePage) "  · takes the whole page" else ""
        val structure = sense.behaviour?.let { "  · runs as ${it.path}" }.orEmpty()
        return listOf(
            Ink("sense ${at + 1}  ", Palette.heading),
            Ink("$role$part", Palette.value),
            Ink(kind, Palette.parameter),
            Ink(default + whole + structure, Palette.chosen),
        )
    }

    private fun senseNote(sense: Sense): String = buildString {
        append("a thing ends a phrase, turning what reaches it into effects; a describing word passes what it offers on\n")
        append("    its part is the part of the world it speaks for, and its kind the acceptances it inherits")
        if (sense.terminal == null && sense.kind != null) append("\n    whether it is a thing, and its part, come from its kind where it does not say")
    }

    private fun offerSaid(offer: Offer): List<Ink> {
        val what = if (offer.isAReading) listOf(Ink("a reading", Palette.parameter)) else listOf(
            Ink(offer.concept.path.padEnd(CONCEPT_WIDTH), Palette.parameter),
            Ink(valueSaid(offer.value), Palette.value),
        )
        return what + Ink(audienceSaid(offer.audience), Palette.chosen) + Ink(chancesSaid(offer), Palette.faint)
    }

    private fun offerNote(offer: Offer): String = buildString {
        append("what it offers the words after it, taken by the first that accepts it\n")
        append("    said of a thing, it lands on its chance; handed out by `age`, on its chance from the whole Age")
        if (offer.isAReading) append("\n    a reading is what only this word means of one part, landing together")
    }

    private fun chancesSaid(offer: Offer): String {
        val said = if (offer.chance == Offer.CERTAIN) "" else "  ${percent(offer.chance)}"
        val fromTheAge = offer.ownChanceFromWholeAge?.let { "  ${percent(it)} from the whole Age" }.orEmpty()
        return said + fromTheAge
    }

    private fun acceptanceSaid(acceptance: Acceptance): List<Ink> {
        val only = when (val filter = acceptance.only) {
            ValueFilter.Anything -> ""
            is ValueFilter.OneOf -> " only ${filter.keys.joinToString(" ")}"
            is ValueFilter.Clearing -> " only ${filter.bars.entries.joinToString(" ") { (tag, bar) -> "#$tag ${Bars.spell(bar)}" }}"
        }
        val outcome = when (val outcome = acceptance.outcome) {
            is Outcome.Becomes -> if (outcome.effects.isEmpty()) "makes nothing" else "makes"
            else -> AcceptanceAsking.outcomeSaid(outcome)
        }
        val fromTheAge = if (acceptance.chanceFromWholeAge == Offer.CERTAIN) "" else "  ${percent(acceptance.chanceFromWholeAge)} from the whole Age"
        val dangling = if (acceptance.canResolveDanglingOffers) "" else "  never a dangling page"
        val tagsForIt = if (acceptance.tagsOnlyWhenOfferedForIt) "  a #tag only for it" else ""
        return listOf(
            Ink(acceptance.concept.path.padEnd(CONCEPT_WIDTH), Palette.parameter),
            Ink(only, Palette.chosen),
            Ink(" → ", Palette.faint),
            Ink(outcome, Palette.value),
            Ink(fromTheAge + dangling + tagsForIt, Palette.faint),
        )
    }

    private fun templateSaid(template: EffectTemplate): String = when (template) {
        is EffectTemplate.Taking -> "${template.verb.key} ${template.target}" + if (template.values.isEmpty()) "" else " as a table"
        is EffectTemplate.Fixed -> entriesOf(template.effect).joinToString("; ") { (_, shown) -> shown.joinToString(" ") { it.text.trim() } }
        is EffectTemplate.When -> "${templateSaid(template.template)} when ${template.choice}"
    }

    // -- the review -----------------------------------------------------------------------------------

    /** Each offer of each sense, and every sense among all the words that would take it, with what it would make. */
    private fun reviewRows(candidate: Candidate): List<Row> = buildList {
        val world = worldWith(candidate)
        for ((at, sense) in candidate.word.senses.withIndex()) {
            add(Row(Spot.Said, listOf(Ink("sense ${at + 1}", Palette.heading))))
            if (sense.offers.isEmpty()) add(Row(Spot.Said, listOf(Ink("${INDENT}offers nothing", Palette.faint))))
            for (offer in sense.offers) {
                add(Row(Spot.Said, listOf(Ink(INDENT)) + offerSaid(offer)))
                val takers = world.takenAs(offer).map(::takenSaid).distinct()
                if (takers.isEmpty()) add(Row(Spot.Said, listOf(Ink(INDENT + INDENT), Ink("nothing takes it: it will always dangle", Palette.warned))))
                for ((who, made) in takers) {
                    add(Row(Spot.Said, listOf(Ink(INDENT + INDENT), Ink("$who ".padEnd(TAKER_WIDTH), Palette.parameter), Ink(made, Palette.value))))
                }
            }
            add(Row(Spot.Heading, emptyList()))
        }
    }

    private fun worldWith(candidate: Candidate): WordsInTheWorld {
        reviewed?.takeIf { it.first == candidate }?.let { return it.second }
        val world = WordsInTheWorld(corpus.with(listOf(candidate.word)), corpus.vocabulary, corpus.membership, corpus.tagsOnlyAServerGrants)
        reviewed = candidate to world
        return world
    }

    /** Who takes an offer — the part, and the kind where it is one of its own rather than the part's page — and what it makes. */
    private fun takenSaid(taken: WordsInTheWorld.Taken): Pair<String, String> {
        val sense = taken.by
        val part = sense.speaksFor?.let { "the ${it.page}" }
        val ownKind = sense.kind?.path?.takeIf { it != sense.speaksFor?.page }
        val who = when {
            sense.behaviour == StructureWord.Age.id -> "the Age"
            else -> listOfNotNull(part ?: "a word of no part", ownKind?.let { "a $it" }).joinToString(", ")
        }
        val made = when (val outcome = taken.acceptance.outcome) {
            is Outcome.Becomes -> taken.effects.joinToString("; ", transform = ::effectSpelled).ifEmpty { "makes nothing of this value" }
            else -> AcceptanceAsking.outcomeSaid(outcome)
        }
        return who to made
    }

    private fun commentRows(candidate: Candidate): List<Row> =
        candidate.comment.map { Row(Spot.Said, listOf(Ink(it, Palette.faint))) } +
            Row(Spot.Comment, listOf(Ink(if (candidate.comment.isEmpty()) "write one in \$EDITOR" else "edit it in \$EDITOR", Palette.value)))

    private fun labelled(label: String, said: String, tone: TextStyle?) =
        listOf(Ink(label.padEnd(LABEL_WIDTH), Palette.faint), Ink(said, tone))

    companion object {
        private const val INDENT = "    "
        private const val LABEL_WIDTH = 10
        private const val CONCEPT_WIDTH = 12
        private const val VERB_WIDTH = 9
        private const val WHERE_WIDTH = 14
        private const val PERCENT = 100
        private const val TAKER_WIDTH = 28
        private const val ACCEPTANCE_NOTE = "a concept this takes from the words before it, which of its values, and what it makes of them\n" +
            "    its own acceptance of a concept wins over its kind's"

        fun percent(share: Double) = "${(share * PERCENT).toInt()}%"

        fun audienceSaid(audience: Audience): String = when (audience) {
            Audience.Anyone -> ""
            Audience.TheAge -> "  for the Age"
            is Audience.Part -> "  for the ${audience.aspect.page}"
        }

        fun valueSaid(value: Value): String = when (value) {
            is Value.Text -> value.text
            is Value.Amount -> "${value.multiple}×"
            is Value.Weights -> value.weights.entries.joinToString(" ") { (key, weight) -> "$key ${signed(weight)}" }
            is Value.Bars -> value.bars.entries.joinToString(" ") { (tag, bar) -> "#$tag ${Bars.spell(bar)}" }
            is Value.Parts -> value.parts.entries.joinToString(" ") { (part, given) -> "$part $given" }
            Value.None -> ""
        }

        private fun signed(weight: Double) = if (weight >= 0) "+$weight" else "$weight"

        /** An offer in one line of plain text: its concept and value, or its reading, and who it is for. */
        fun offerSpelled(offer: Offer): String {
            val what = if (offer.isAReading) "a reading: ${offer.reading.joinToString("; ", transform = ::effectSpelled)}"
            else "${offer.concept.path} ${valueSaid(offer.value)}".trim()
            return what + audienceSaid(offer.audience)
        }

        /** An effect in one line of plain text. */
        fun effectSpelled(effect: Effect): String =
            entriesOf(effect).joinToString("; ") { (_, shown) -> shown.joinToString(" ") { it.text.trim() }.trim() }

        /** Each row an effect is shown as: one, or one per member or tag it names. */
        fun entriesOf(effect: Effect): List<Pair<String?, List<Ink>>> = when (effect) {
            is Effect.Sets -> listOf(null to said("sets", effect.parameter.spelled, effect.value))
            is Effect.Bends -> listOf(null to said("bends", effect.parameter.spelled, effect.value))
            is Effect.Chooses -> listOf(null to said("chooses", effect.aspect.page, effect.member))
            is Effect.Admits -> effect.members.sorted().map { it to said("adds", effect.aspect.page, it) }
            is Effect.Excludes -> effect.members.sorted().map { it to said("strikes", effect.aspect.page, it) }
            is Effect.Restricts -> effect.bars.entries.map { (tag, bar) -> tag to said("keeps", effect.aspect.page, "#$tag ${Bars.spell(bar)}") }
            is Effect.Leans -> effect.weights.entries.map { (named, weight) -> named to said("leans", effect.aspect.page, "$named ${signed(weight)}") }
            is Effect.LeansEverywhere -> effect.weights.entries.map { (named, weight) -> named to said("leans", "everything", "$named ${signed(weight)}") }
            is Effect.Mints -> listOf(null to said("mints", effect.pattern, effect.unstated?.let { "of $it" } ?: "of what its clause lays"))
            is Effect.Template -> listOf(null to said("begins", "from", effect.name))
            is Effect.Abundance -> listOf(null to said("asks", "for", "${Rung.spelled(effect.rung)} times as many"))
            is Effect.MadeOf -> listOf(null to said("made", "of", effect.substance))
            is Effect.Bans -> listOf(null to said("bans", "", effect.block))
            is Effect.LinksIn -> listOf(null to said("links in", effect.anchor.aspect.page, effect.anchor.members.joinToString(" ")))
        }

        private fun said(verb: String, where: String, what: String) = listOf(
            Ink(verb.padEnd(VERB_WIDTH), Palette.faint),
            Ink(where.padEnd(WHERE_WIDTH), Palette.parameter),
            Ink(what, Palette.value),
        )
    }
}
