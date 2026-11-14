package co.voik.agesandtheart.preview.authoring.words.ui

import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Speech
import co.voik.agesandtheart.preview.authoring.ui.Canvas
import co.voik.agesandtheart.preview.authoring.ui.Frame
import co.voik.agesandtheart.preview.authoring.ui.Glyph
import co.voik.agesandtheart.preview.authoring.ui.Ink
import co.voik.agesandtheart.preview.authoring.ui.Leaving
import co.voik.agesandtheart.preview.authoring.ui.Line
import co.voik.agesandtheart.preview.authoring.ui.Palette
import co.voik.agesandtheart.preview.authoring.ui.Picker
import co.voik.agesandtheart.preview.authoring.ui.Questions
import co.voik.agesandtheart.preview.authoring.ui.Reader
import co.voik.agesandtheart.preview.authoring.ui.hints
import co.voik.agesandtheart.preview.authoring.words.Candidate
import co.voik.agesandtheart.preview.authoring.words.Verdict
import co.voik.agesandtheart.preview.authoring.words.WordCorpus
import co.voik.agesandtheart.preview.authoring.words.WordFile
import co.voik.agesandtheart.preview.authoring.words.WordListings
import co.voik.agesandtheart.preview.authoring.words.changingOffer
import co.voik.agesandtheart.preview.authoring.words.changingSense
import co.voik.agesandtheart.preview.authoring.words.withChance
import co.voik.agesandtheart.preview.authoring.words.withChanceFromWholeAge
import co.voik.agesandtheart.preview.authoring.words.withDefaultSense
import co.voik.agesandtheart.preview.authoring.words.withOffer
import co.voik.agesandtheart.preview.authoring.words.withSense
import co.voik.agesandtheart.preview.authoring.words.withoutOffer
import co.voik.agesandtheart.preview.authoring.words.withoutSense
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import kotlin.math.round

/**
 * The word editor over the new model — a frame that stays up while a word is changed, and a verdict under it that is
 * never more than one edit old. Everything shown is worked out again from the candidate, which is immutable: an edit
 * pushes a new one, so undo drops the top of a list.
 */
class WordEditor(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private val corpus: WordCorpus,
    opened: Candidate,
    /** Whether there is a screen behind this one — false when the word was named on the command line. */
    private val canLeave: Boolean,
) {
    private val sections = Sections(corpus)
    private val questions = Questions()
    private val asking = Asking(corpus, questions)
    private val history = mutableListOf(opened)

    /** The word as it last stood on disk, so *unsaved* means changed since it was saved. */
    private var written: Candidate = opened

    /** The name the word was opened under, where it had a file — saving under another moves it. */
    private var openedAs: String? = opened.name.takeIf(WordFile::exists)

    private val rowOf = Section.entries.associateWith { 0 }.toMutableMap()
    private var section = Section.WORD

    /** Whether the arrow keys belong to the rows rather than the sections. */
    private var inside = false
    private var message: String? = null
    private var changed = false
    private var quitting = false
    private var leaving = false
    private var raw: RawModeScope? = null
    private var typing: Typing? = null

    private var findings: List<Verdict.Finding> = emptyList()
    private var judged: Candidate? = null

    private val candidate: Candidate get() = history.last()

    private val unsaved: Boolean get() = candidate != written

    /** A row being typed on, the value edited in place. */
    private class Typing(val spot: Spot, var text: String, val complaint: (String) -> String?, val commit: (String) -> Unit)

    /** Runs until the writer leaves. True if a file was written. */
    fun run(): Boolean {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            raw = scope
            while (!quitting && !leaving) {
                judge()
                draw()
                val key = scope.readKey()
                if (key.ctrl && key.key == "c") throw Leaving()
                message = null
                handle(key)
            }
        }
        raw = null
        return changed
    }

    /** The verdict, worked out again only when the word changed: moving a cursor must not cost a corpus sweep. */
    private fun judge() {
        if (judged == candidate) return
        findings = Verdict.on(candidate, corpus)
        judged = candidate
    }

    private fun edit(change: (Candidate) -> Candidate) {
        history += change(candidate)
    }

    private fun rows() = sections.rowsOf(section, candidate)

    private fun row(): Int = rowOf.getValue(section).coerceIn(0, (rows().size - 1).coerceAtLeast(0))

    // -- drawing -------------------------------------------------------------------------------------

    private fun draw() {
        val width = canvas.width
        canvas.show(
            buildList {
                add(header(width))
                add(Frame.rule(width))
                addAll(body(width, canvas.height))
                add(Frame.rule(width))
                addAll(strip(width))
                addAll(keys(width))
            },
        )
    }

    private fun header(width: Int): Line {
        val senses = candidate.word.senses.size
        val left = Line("  ") + Line(candidate.name, Palette.heading) +
            Line("  $senses sense${if (senses == 1) "" else "s"}", Palette.faint) +
            Line(if (unsaved) "  ${Glyph.BULLET} unsaved" else "", Palette.warned)
        val right = Line("ink ${corpus.words.priceOf(candidate.word)}", Palette.value) + Line("  ")
        return left.sized((width - right.width).coerceAtLeast(0)) + right
    }

    private fun body(width: Int, height: Int): List<Line> {
        val room = (height - CHROME_LINES).coerceAtLeast(MINIMUM_BODY)
        val rest = width - SECTION_WIDTH - Frame.GUTTER
        // A question opens beside its answer where there is room, so the row it is about stays in view.
        val roomForThree = questions.isOpen && rest >= WORD_PANE_LEAST + QUESTION_LEAST + Frame.GUTTER
        if (questions.isOpen && !roomForThree) return questions.lines(width, room)
        val wordWidth = if (roomForThree) minOf(WORD_PANE_ASKED, rest - QUESTION_LEAST - Frame.GUTTER).coerceAtLeast(WORD_PANE_LEAST) else rest
        val listed = scrolled(sectionList(), section.ordinal, room)
        val body = Frame.beside(listed, SECTION_WIDTH, sectionLines(wordWidth, room), wordWidth).take(room)
        if (!questions.isOpen) return body
        val question = questions.lines((rest - wordWidth - Frame.GUTTER).coerceAtMost(QUESTION_MOST), room)
        return body.mapIndexed { at, line -> line + Line(" ${Glyph.BAR} ", Palette.rule) + (question.getOrNull(at) ?: Line.BLANK) }
    }

    private fun sectionList(): List<Line> = Section.entries.flatMap { entry ->
        listOfNotNull(Line.BLANK.takeIf { entry.startsGroup }) + sectionLine(entry)
    }

    private fun sectionLine(entry: Section): Line {
        val here = entry == section
        val marker = if (here && !inside) "${Glyph.FOCUS} " else "  "
        val tone = when {
            entry.perilous -> Palette.refused
            here -> Palette.focused
            else -> Palette.faint
        }
        val warned = if (entry.perilous) "${Glyph.WARN} " else ""
        return Line(marker, Palette.focused) + Line(warned, Palette.refused) +
            Line(entry.title.padEnd(MARK_COLUMN - warned.length), tone) + Line(filledness(entry), Palette.faint)
    }

    /** A glance at how much of each section has been said, so nothing is forgotten by not being visited. */
    private fun filledness(entry: Section): String = when (entry) {
        Section.WORD -> corpus.words.priceOf(candidate.word).toString()
        Section.LISTING -> if (WordListings.listingOf(WordListings.Listed.authored(candidate.name)).rarity == null) "" else Glyph.TICK
        Section.SENSES -> candidate.word.senses.size.toString()
        Section.COMMENT -> if (candidate.comment.isEmpty()) "" else Glyph.TICK
        Section.SAVE, Section.SAVE_AND_LEAVE -> if (unsaved) Glyph.WARN else Glyph.TICK
        Section.DELETE -> ""
    }

    private fun sectionLines(width: Int, room: Int): List<Line> {
        val shown = rows()
        val at = row()
        val head = if (section.about.isEmpty()) listOf(Line.BLANK) else listOf(Line(section.about, Palette.faint), Line.BLANK)
        val listed = shown.mapIndexed { index, entry ->
            val marker = if (index == at && inside) "${Glyph.FOCUS} " else "  "
            val body = typing?.takeIf { it.spot == entry.spot }?.let { said ->
                entry.shown.dropLast(1) + Ink(said.text, Palette.value) + Ink(Glyph.FULL, Palette.chosen)
            } ?: entry.shown
            Line(marker, Palette.focused) + Line(body)
        }
        val note = shown.getOrNull(at)?.note.orEmpty()
        val said = if (note.isEmpty()) emptyList() else {
            listOf(Line.BLANK) + note.lines().map { line -> Line("  $line", if (line.startsWith(" ")) Palette.faint else Palette.value) }
        }
        val forTheList = listed.size.coerceAtMost((room - head.size - NOTE_LEAST).coerceAtLeast(1))
        val forTheNote = (room - head.size - forTheList).coerceAtLeast(0)
        val window = scrolled(listed, at, forTheList)
        val padding = List((room - head.size - window.size - minOf(said.size, forTheNote)).coerceAtLeast(0)) { Line.BLANK }
        return (head + window + padding + said.take(forTheNote)).map { it.sized(width) }
    }

    private fun scrolled(lines: List<Line>, at: Int, room: Int): List<Line> {
        if (room <= 0) return emptyList()
        if (lines.size <= room) return lines
        val first = (at - room / 2).coerceIn(0, lines.size - room)
        return lines.subList(first, first + room)
    }

    private fun rowsInView(): Int {
        val room = (canvas.height - CHROME_LINES).coerceAtLeast(MINIMUM_BODY)
        val head = if (section.about.isEmpty()) 1 else 2
        return (room - head - NOTE_LEAST).coerceAtLeast(1)
    }

    private fun strip(width: Int): List<Line> {
        message?.let { return listOf(Line("  $it", Palette.settled)) + List(STRIP_LINES - 1) { Line.BLANK } }
        val counted = Verdict.Standing.entries.mapNotNull { standing ->
            val many = findings.count { it.standing == standing }
            if (many == 0) null else Ink("$many ${standing.name.lowercase()}  ", standing.style)
        }
        val summary = if (counted.isEmpty()) Line("  ${Glyph.TICK} nothing to answer for", Palette.settled) else Line("  ") + Line(counted)
        val said = findings.sortedBy { it.standing.ordinal }.take(STRIP_LINES - 1).map(::findingLine)
        return (listOf(summary) + said + List(STRIP_LINES) { Line.BLANK }).take(STRIP_LINES).map { it.sized(width) }
    }

    private fun findingLine(finding: Verdict.Finding): Line =
        Line("  ${finding.standing.mark} ", finding.standing.style) + Line(finding.says, Palette.value) +
            Line(finding.because?.let { " ${Glyph.BULLET} $it" }.orEmpty(), Palette.faint) +
            Line(finding.heldBy?.let { "  [$it]" }.orEmpty(), Palette.faint)

    /** The key line, in two rows: what the thing under the cursor does, then how to get around. */
    private fun keys(width: Int): List<Line> = listOf(doingKeys(), gettingAround()).map { it.sized(width) }

    private fun doingKeys(): Line = when {
        typing != null -> hints("enter" to "accept", "↑↓" to "accept and move on", "" to typing?.let { it.complaint(it.text) }.orEmpty())
        questions.isOpen -> questions.doingKeys()
        !inside -> hints("→" to "open this section")
        else -> hints(*sections.keysFor(rows().getOrNull(row())).toTypedArray())
    }

    private fun gettingAround(): Line = when {
        typing != null -> hints("esc" to "leave it alone")
        questions.isOpen -> questions.gettingAround()
        !inside -> hints("↑↓" to "section", if (canLeave) "←" to "back" else "" to "", "^f" to "faults", "^z" to "undo", "^s" to "save")
        else -> hints("↑↓" to "row", "pgup/pgdn" to "a page", "←" to "back", "^f" to "faults", "^z" to "undo", "^s" to "save")
    }

    // -- keys ----------------------------------------------------------------------------------------

    private fun handle(key: KeyboardEvent) {
        typing?.let { return handleTyping(it, key) }
        if (questions.isOpen) {
            if (questions.handle(key) == Questions.Answered.QUIT) quitting = true
            return
        }
        val here = rows().getOrNull(row())?.spot
        when {
            key.ctrl && key.key == "q" -> if (unsaved) askBeforeLeaving(quits = true) else quitting = true
            key.ctrl && key.key == "s" -> save()
            key.ctrl && key.key == "z" -> undo()
            key.ctrl && key.key == "f" -> questions.ask(faults())
            key.key == "ArrowUp" -> if (inside) move(-1) else turnTo(Section.entries[(section.ordinal - 1 + Section.entries.size) % Section.entries.size])
            key.key == "ArrowDown" -> if (inside) move(1) else turnTo(Section.entries[(section.ordinal + 1) % Section.entries.size])
            key.key == "ArrowRight" -> inside = true
            key.key == "ArrowLeft" || key.key == "Escape" -> if (inside) inside = false else askBeforeLeaving()
            key.key == "Enter" -> if (inside) act() else inside = true
            key.key == "PageUp" -> if (inside) move(-rowsInView())
            key.key == "PageDown" -> if (inside) move(rowsInView())
            key.key == "Home" -> if (inside) moveTo(0)
            key.key == "End" -> if (inside) moveTo(rows().size - 1)
            !inside -> Unit
            key.key == "=" -> stepAChance(here, Questions.A_STEP)
            key.key == "-" -> stepAChance(here, -Questions.A_STEP)
            key.key == "a" -> add(here)
            key.key == "d" -> remove(here)
        }
    }

    private fun turnTo(wanted: Section) {
        section = wanted
        rowOf[section] = restingPlace(row(), 1)
    }

    private fun handleTyping(said: Typing, key: KeyboardEvent) {
        when {
            key.key == "Escape" -> typing = null
            key.key == "Enter" -> settleTyping()
            key.key == "ArrowUp" -> { settleTyping(); move(-1) }
            key.key == "ArrowDown" -> { settleTyping(); move(1) }
            key.key == "Backspace" -> said.text = said.text.dropLast(1)
            key.key.length == 1 && !key.ctrl && !key.alt -> said.text += key.key
        }
    }

    private fun settleTyping() {
        val said = typing ?: return
        typing = null
        val wrong = said.complaint(said.text)
        if (wrong == null) said.commit(said.text) else message = wrong
    }

    private fun move(by: Int) {
        val size = rows().size
        if (size == 0) return
        val wanted = if (kotlin.math.abs(by) > 1) (row() + by).coerceIn(0, size - 1) else row() + by
        rowOf[section] = restingPlace(((wanted % size) + size) % size, if (by < 0) -1 else 1)
    }

    /** The nearest row the cursor may rest on, walking [towards]: a heading is read, never landed on. */
    private fun restingPlace(from: Int, towards: Int): Int {
        val listed = rows()
        if (listed.isEmpty()) return 0
        var at = from.coerceIn(0, listed.size - 1)
        repeat(listed.size) {
            if (listed[at].spot != Spot.Heading) return at
            at = ((at + towards) % listed.size + listed.size) % listed.size
        }
        return from
    }

    private fun moveTo(where: Int) {
        val size = rows().size
        if (size == 0) return
        rowOf[section] = restingPlace(where.coerceIn(0, size - 1), if (where == 0) 1 else -1)
    }

    private fun landOn(spot: Spot) {
        val at = rows().indexOfFirst { it.spot == spot }
        if (at >= 0) rowOf[section] = at
    }

    // -- what a row does -----------------------------------------------------------------------------

    private fun act() {
        when (val spot = rows().getOrNull(row())?.spot ?: return) {
            Spot.Heading, Spot.Said -> Unit
            Spot.Name -> rename()
            Spot.Speech -> pickASpeech()
            Spot.Price -> retypeThePrice()
            Spot.Display -> retitle()
            Spot.Rarity -> relist()
            Spot.InkQuality -> reink()
            is Spot.SenseHead -> askWhatTheSenseIs(spot.sense)
            is Spot.OfferAt -> askAboutTheOffer(spot.sense, spot.offer)
            is Spot.ReadingEntry, is Spot.MeansEntry, is Spot.AcceptanceAt -> message = "effects and acceptances are edited in the next step of the rework"
            is Spot.AddOffer -> addAnOffer(spot.sense)
            Spot.AddSense -> addASense()
            Spot.Comment -> openTheEditor()
            is Spot.Save -> {
                save()
                if (spot.andLeave && !unsaved) quitting = true
            }
            Spot.Delete -> askAboutDeleting()
        }
    }

    private fun add(here: Spot?) = when (here) {
        is Spot.SenseHead -> addAnOffer(here.sense)
        is Spot.OfferAt -> addAnOffer(here.sense)
        is Spot.AddOffer -> addAnOffer(here.sense)
        Spot.AddSense -> addASense()
        else -> message = "nothing to add here"
    }

    private fun remove(here: Spot?) {
        when (here) {
            is Spot.SenseHead -> edit { it.changingWord { word -> word.withoutSense(here.sense) } }
            is Spot.OfferAt -> edit { it.changingWord { word -> word.changingSense(here.sense) { sense -> sense.withoutOffer(here.offer) } } }
            else -> message = "nothing here to remove"
        }
        moveTo(row())
    }

    private fun stepAChance(here: Spot?, by: Double) {
        val at = here as? Spot.OfferAt ?: return
        changeTheOffer(at.sense, at.offer) { offer -> offer.withChance(round((offer.chance + by) * HUNDREDTHS) / HUNDREDTHS) }
    }

    private fun changeTheOffer(sense: Int, offer: Int, change: (Offer) -> Offer) =
        edit { it.changingWord { word -> word.changingSense(sense) { held -> held.changingOffer(offer, change) } } }

    private fun changeTheSense(sense: Int, change: (Sense) -> Sense) = edit { it.changingWord { word -> word.changingSense(sense, change) } }

    // -- a sense -------------------------------------------------------------------------------------

    private fun addASense() {
        edit { it.changingWord { word -> word.withSense() } }
        val at = candidate.word.senses.lastIndex
        landOn(Spot.SenseHead(at))
        askWhatTheSenseIs(at)
    }

    private fun askWhatTheSenseIs(at: Int) {
        val written = candidate.word.senses.getOrNull(at) ?: return
        val sense = sections.withItsKind(written)
        val role = when (written.terminal) {
            true -> "a thing"
            false -> "a describing word"
            null -> if (sense.endsAPhrase) "a thing, as its kind says" else "a describing word, as its kind says"
        }
        val options = listOf(
            Picker.Option(ROLE, "a thing, or a describing word", role),
            Picker.Option(PART, "of which part", written.part?.page ?: sense.speaksFor?.let { "${it.page}, as its kind says" } ?: "none"),
            Picker.Option(KIND, "of which kind", written.kind?.path ?: "none"),
            Picker.Option(IS_DEFAULT, if (written.isDefault) "no longer the default" else "the default", "the sense read where the neighbours fit two"),
            Picker.Option(WHOLE_PAGE, if (written.consumesWholePage) "leaves the rest of a page" else "takes the whole page", "of whatever it takes something of"),
        )
        questions.ask(Picker("Sense ${at + 1}: what is it?", options) { picked ->
            when (picked.value) {
                ROLE -> askTheRole(at, written.terminal)
                PART -> asking.aPart("Of which part?", written.part, "its kind's, or none") { part -> changeTheSense(at) { it.copy(part = part) } }
                KIND -> asking.aKind(written.kind) { kind -> changeTheSense(at) { it.copy(kind = kind) } }
                IS_DEFAULT -> edit { it.changingWord { word -> word.withDefaultSense(if (written.isDefault) null else at) } }
                WHOLE_PAGE -> changeTheSense(at) { it.copy(consumesWholePage = !it.consumesWholePage) }
            }
        })
    }

    private fun askTheRole(at: Int, standing: Boolean?) {
        val options = listOf(
            Picker.Option(THING, "a thing", "ends a phrase, turning what reaches it into effects", marked = standing == true),
            Picker.Option(DESCRIBING, "a describing word", "passes what it offers on to the words after it", marked = standing == false),
            Picker.Option(AS_ITS_KIND, "as its kind says", "", marked = standing == null),
        )
        questions.ask(Picker("A thing, or a describing word?", options) { picked ->
            val terminal = when (picked.value) {
                THING -> true
                DESCRIBING -> false
                else -> null
            }
            changeTheSense(at) { it.copy(terminal = terminal) }
        })
    }

    // -- an offer ------------------------------------------------------------------------------------

    private fun addAnOffer(sense: Int) {
        asking.aConcept("Offer what?") { concept ->
            asking.aValue(concept, standing = null) { value ->
                changeTheSense(sense) { it.withOffer(Offer(concept.id, value)) }
                landOn(Spot.OfferAt(sense, candidate.word.senses[sense].offers.lastIndex))
            }
        }
    }

    private fun askAboutTheOffer(sense: Int, at: Int) {
        val offer = candidate.word.senses.getOrNull(sense)?.offers?.getOrNull(at) ?: return
        if (offer.isAReading) {
            message = "a reading's effects are edited in the next step of the rework"
            return
        }
        val concept = corpus.words.concepts[offer.concept]
        val options = listOfNotNull(
            Picker.Option(VALUE, "its value", Sections.valueSaid(offer.value).ifEmpty { "none: the concept is the whole of it" }).takeIf { concept != null },
            Picker.Option(CONCEPT, "its concept", offer.concept.path),
            Picker.Option(FOR, "for whom", audienceNote(offer)),
            Picker.Option(CHANCE, "its chance said of a thing", Sections.percent(offer.chance)),
            Picker.Option(FROM_THE_AGE, "its chance from the whole Age", offer.ownChanceFromWholeAge?.let(Sections::percent) ?: "as its chance"),
        )
        questions.ask(Picker("The offer of ${offer.concept.path}", options) { picked ->
            when (picked.value) {
                VALUE -> concept?.let { asking.aValue(it, offer.value) { value -> changeTheOffer(sense, at) { held -> held.copy(value = value) } } }
                CONCEPT -> asking.aConcept("Offer which concept instead?") { chosen ->
                    asking.aValue(chosen, standing = null) { value -> changeTheOffer(sense, at) { held -> held.copy(concept = chosen.id, value = value) } }
                }
                FOR -> asking.anAudience(offer.audience) { audience -> changeTheOffer(sense, at) { held -> held.copy(audience = audience) } }
                CHANCE -> asking.aChance("Its chance, said of a thing", offer.chance, "a share from 0 to 1", mayBeNothing = false) { chance ->
                    changeTheOffer(sense, at) { held -> held.withChance(chance ?: Offer.CERTAIN) }
                }
                FROM_THE_AGE -> asking.aChance(
                    "Its chance where `age` hands it out",
                    offer.ownChanceFromWholeAge,
                    "a share from 0 to 1, or nothing to follow its chance",
                    mayBeNothing = true,
                ) { chance -> changeTheOffer(sense, at) { held -> held.withChanceFromWholeAge(chance) } }
            }
        })
    }

    private fun audienceNote(offer: Offer): String = when (val audience = offer.audience) {
        Audience.Anyone -> "anyone"
        Audience.TheAge -> "the Age"
        is Audience.Part -> "the ${audience.aspect.page}"
    }

    // -- the word itself -----------------------------------------------------------------------------

    private fun rename() = typeOn(
        Spot.Name,
        candidate.name,
        complaint = { typed -> if (WordFile.couldBeAName(typed)) null else "lower case, digits and _ - . / only" },
    ) { typed -> edit { it.renamed(typed) } }

    private fun pickASpeech() {
        val standing = candidate.word.speech
        val options = listOf(Picker.Option(UNSET, "as said of something", "the default", marked = standing == null)) +
            Speech.entries.map { Picker.Option(it.serializedName, it.serializedName, "", marked = it == standing) }
        questions.ask(Picker("How is it said?", options) { picked ->
            val speech = Speech.entries.firstOrNull { it.serializedName == picked.value }
            edit { it.changingWord { word -> word.copy(speech = speech) } }
        })
    }

    private fun retypeThePrice() = typeOn(
        Spot.Price,
        candidate.word.price?.toString().orEmpty(),
        complaint = { typed -> if (typed.isBlank() || (typed.toIntOrNull() ?: -1) >= 0) null else "a whole number, or nothing to read it off" },
    ) { typed -> edit { it.changingWord { word -> word.copy(price = typed.toIntOrNull()) } } }

    private fun retitle() {
        val name = candidate.name
        typeOn(Spot.Display, WordListings.displayOf(name).orEmpty()) { typed ->
            runCatching { WordListings.setDisplay(name, typed.ifBlank { null }) }
                .onSuccess { message = typed.ifBlank { null }?.let { "shown as $it" } ?: "back to the fallback" }
                .onFailure { message = "could not rename: ${it.message}" }
        }
    }

    private fun relist() {
        val listed = WordListings.Listed.authored(candidate.name)
        val default = WordListings.defaultBucket()
        val standing = WordListings.listingOf(listed).rarity.takeUnless { it == default }
        val buckets = WordListings.rarityBuckets().map { Picker.Option(it, it, "", marked = it == standing) }
        val keptOut = Picker.Option(WordListings.NO_LOOT, WordListings.NO_LOOT, "never on a found page or in a found notebook", marked = standing == WordListings.NO_LOOT)
        val unset = Picker.Option(UNSET, default ?: UNSET, "every word listed nowhere else", marked = standing == null)
        questions.ask(Picker("Which rarity?", listOf(unset) + buckets + keptOut, onClear = { setRarity(null) }) { picked ->
            setRarity(picked.value.takeIf { it != UNSET })
        })
    }

    private fun setRarity(bucket: String?) {
        WordListings.setRarity(WordListings.Listed.authored(candidate.name), bucket)
        message = "rarity set to ${bucket ?: WordListings.defaultBucket() ?: "the default"}"
    }

    private fun reink() {
        val listed = WordListings.Listed.authored(candidate.name)
        val standing = WordListings.inkOf(listed)
        val tiers = WordListings.inkTiers().map { Picker.Option(it, it, "", marked = it == standing) }
        questions.ask(
            Picker("Which ink quality?", listOf(Picker.Option(UNSET, UNSET, "any ink writes it", marked = standing == null)) + tiers, onClear = { setInk(null) }) { picked ->
                setInk(picked.value.takeIf { it != UNSET })
            },
        )
    }

    private fun setInk(tier: String?) {
        WordListings.setInk(WordListings.Listed.authored(candidate.name), tier)
        message = tier?.let { "ink set to $it" } ?: "ink cleared"
    }

    private fun typeOn(spot: Spot, standing: String, complaint: (String) -> String? = { null }, commit: (String) -> Unit) {
        typing = Typing(spot, standing, complaint, commit)
    }

    /** The comment, in `$EDITOR`, with raw mode given up for the duration. */
    private fun openTheEditor() {
        val editor = System.getenv("EDITOR") ?: System.getenv("VISUAL") ?: DEFAULT_EDITOR
        val scratch = File.createTempFile("word-comment-", ".md")
        scratch.writeText(candidate.comment.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" })
        val ran = runCatching {
            raw?.close()
            canvas.lending { ProcessBuilder(editor, scratch.absolutePath).inheritIO().start().waitFor() }
        }
        raw = terminal.enterRawMode(MouseTracking.Off)
        if (ran.getOrNull() == 0) edit { it.copy(comment = scratch.readLines().dropLastWhile(String::isBlank)) } else message = "could not run $editor; set \$EDITOR"
        scratch.delete()
    }

    private fun askAboutDeleting() {
        val had = openedAs ?: return run { message = "nothing to delete: this word has no file yet" }
        val answers = listOf(
            Picker.Option(KEEP, "keep it", "nothing changes"),
            Picker.Option(DELETE, "delete it", "the file, its rarity, its ink and what it is shown as. There is no undo.", mark = Glyph.WARN, tone = Palette.refused),
        )
        questions.ask(Picker("Delete '${candidate.name}' for good?", answers) { picked ->
            if (picked.value != DELETE) return@Picker
            runCatching { WordFile.delete(had) }
                .onSuccess { changed = true; leaving = true }
                .onFailure { message = "could not delete: ${it.message}" }
        })
    }

    // -- the errands ---------------------------------------------------------------------------------

    /** Leaving, with saving offered first: three answers, so somebody who wanted to save is not made to say no. */
    private fun askBeforeLeaving(quits: Boolean = false) {
        fun go() = if (quits) quitting = true else leaving = canLeave
        if (!unsaved || (!canLeave && !quits)) return go()
        val options = listOf(
            Picker.Option(SAVE_AND_LEAVE, "save it and leave", "write the file, then go"),
            Picker.Option(LEAVE_ANYWAY, "leave without saving", "the changes are lost"),
            Picker.Option(STAY, "keep editing", "", startsGroup = true),
        )
        questions.ask(Picker("'${candidate.name}' has changes that are not saved", options) { picked ->
            when (picked.value) {
                SAVE_AND_LEAVE -> {
                    save()
                    if (!unsaved) go()
                }
                LEAVE_ANYWAY -> go()
            }
        })
    }

    private fun undo() {
        if (history.size <= 1) return run { message = "nothing to undo" }
        history.removeAt(history.lastIndex)
    }

    private fun save() {
        val refused = Verdict.refusals(findings)
        if (refused.isNotEmpty()) return run { message = "not saved: ${refused.size} refused. ^f to see them." }
        val file = runCatching {
            openedAs?.takeIf { it != candidate.name }?.let { WordFile.rename(it, candidate.name) }
            WordFile.write(candidate)
            WordFile.fileFor(candidate.name)
        }
        message = file.fold(onSuccess = { "saved ${it.path}" }, onFailure = { "could not save: ${it.message}" })
        if (file.isSuccess) {
            changed = true
            written = candidate
            openedAs = candidate.name
        }
    }

    private fun faults(): Reader = Reader(
        "What is said of ${candidate.name}",
        findings.sortedBy { it.standing.ordinal }.map(::findingLine).ifEmpty { listOf(Line("nothing to answer for", Palette.settled)) },
    )

    private companion object {
        val MARK_COLUMN: Int = Section.entries.maxOf { it.title.length + if (it.perilous) WARNING_ROOM else 0 } + 1
        val SECTION_WIDTH: Int = CURSOR_ROOM + MARK_COLUMN + MARK_ROOM
        const val CURSOR_ROOM = 2
        const val WARNING_ROOM = 2
        const val MARK_ROOM = 3
        const val WORD_PANE_ASKED = 64
        const val WORD_PANE_LEAST = 34
        const val QUESTION_LEAST = 46
        const val QUESTION_MOST = 92
        const val MINIMUM_BODY = 8
        const val NOTE_LEAST = 4
        const val STRIP_LINES = 4
        const val CHROME_LINES = 5 + STRIP_LINES
        const val HUNDREDTHS = 100.0
        const val DEFAULT_EDITOR = "vi"

        const val SAVE_AND_LEAVE = "\u0000save"
        const val LEAVE_ANYWAY = "\u0000leave"
        const val STAY = "\u0000stay"
        const val KEEP = "\u0000keep"
        const val DELETE = "\u0000delete"
        const val UNSET = "default"
        const val ROLE = "role"
        const val PART = "part"
        const val KIND = "kind"
        const val IS_DEFAULT = "the default"
        const val WHOLE_PAGE = "whole page"
        const val THING = "thing"
        const val DESCRIBING = "describing"
        const val AS_ITS_KIND = "\u0000kind"
        const val VALUE = "value"
        const val CONCEPT = "concept"
        const val FOR = "for"
        const val CHANCE = "chance"
        const val FROM_THE_AGE = "from the Age"
    }
}
