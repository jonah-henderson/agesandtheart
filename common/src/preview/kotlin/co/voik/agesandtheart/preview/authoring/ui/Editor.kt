package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.RANGED_PARAMETERS
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.language.Speech
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.Candidate
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.Trial
import co.voik.agesandtheart.preview.authoring.Verdict
import co.voik.agesandtheart.preview.authoring.WordFile
import co.voik.agesandtheart.preview.authoring.asserting
import co.voik.agesandtheart.preview.authoring.changingMeaning
import co.voik.agesandtheart.preview.authoring.mustAlways
import co.voik.agesandtheart.preview.authoring.withChance
import co.voik.agesandtheart.preview.authoring.withEffect
import co.voik.agesandtheart.preview.authoring.withEffectAt
import co.voik.agesandtheart.preview.authoring.withEntryValue
import co.voik.agesandtheart.preview.authoring.withMeaning
import co.voik.agesandtheart.preview.authoring.withUnaimedChance
import co.voik.agesandtheart.preview.authoring.withoutEntry
import co.voik.agesandtheart.preview.authoring.withoutMeaning
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal
import net.minecraft.core.registries.BuiltInRegistries
import java.io.File

/**
 * The word editor — a frame that stays up while a word is changed, and a verdict under it that is never more than
 * one edit old.
 *
 * **Everything is recomputed from the candidate**: there is no order the sections have to be visited in, and going
 * back is the same operation as going forward. The candidate is immutable and an edit pushes a new one, so undo is
 * dropping the top of a list.
 */
class Editor(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private val corpus: Corpus,
    opened: Candidate,
    /** Whether there is a screen behind this one — false when the word was named on the command line. */
    private val canLeave: Boolean,
) {

    private val parts = Parts(corpus)
    private val history = mutableListOf(opened)

    /** The word as it last stood on disk, so *changed* means changed since it was saved. */
    private var written: Candidate = opened

    /** The name the word was opened under, where it had a file — saving under another moves it. */
    private val openedAs: String? = opened.name.takeIf { WordFile.exists(it) && !opened.isDerived }
    private val rowOf = Part.entries.associateWith { 0 }.toMutableMap()
    private var part = Part.NAME

    /** Whether the arrow keys belong to the rows rather than the sections. */
    private var inside = false
    private var overlay: Overlay? = null
    private var message: String? = null

    /** Whether anything reached the disk that the corpus would read differently. */
    private var changed = false
    private var quitting = false
    private var leaving = false
    private var raw: RawModeScope? = null
    private var readerWindow = MINIMUM_BODY
    private var bandWindow = MINIMUM_BODY
    private var pickerWindow = MINIMUM_BODY

    /** The questions this one was opened over, innermost last — what `esc` and `←` walk back out through. */
    private val wentThrough = ArrayDeque<Overlay>()

    private val candidate: Candidate get() = history.last()

    private val unsaved: Boolean get() = !candidate.isDerived && candidate != written

    private var findings: List<Verdict.Finding> = emptyList()
    private var judged: Candidate? = null

    private var typing: Typing? = null

    /** A row being typed on, where the value is edited in place. */
    private class Typing(val handle: Handle, var text: String, val complaint: (String) -> String?, val commit: (String) -> Unit)

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

    // -- state ---------------------------------------------------------------------------------------

    /** The verdict, recomputed only when the word changed — moving a cursor must not cost a corpus sweep. */
    private fun judge() {
        if (judged == candidate) return
        findings = Verdict.on(candidate, corpus)
        judged = candidate
    }

    private fun edit(change: (Candidate) -> Candidate) {
        if (candidate.isDerived) {
            message = "${candidate.name} is written by the game, not by a file — only its rarity and ink can be set"
            return
        }
        history += change(candidate)
    }

    private fun word(): Word = candidate.asWord()

    private fun rows() = parts.rowsOf(part, candidate, word())

    private fun row(): Int = rowOf.getValue(part).coerceIn(0, (rows().size - 1).coerceAtLeast(0))

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
        val word = word()
        val reach = corpus.vocabulary.lexicon.placesFor(word).sortedBy { it.ordinal }.joinToString(" ") { it.page }
        val left = Line("  ") +
            Line(candidate.name.ifBlank { "(unnamed)" }, Palette.heading) +
            Line("  ${candidate.meanings.size} meaning(s)", Palette.faint) +
            Line(if (unsaved) "  ${Glyph.BULLET} unsaved" else "", Palette.warned)
        val right = Line(reach.ifEmpty { "reaches nothing" }, Palette.chosen) + Line("   ink ${word.price}", Palette.value) + Line("  ")
        return left.sized((width - right.width).coerceAtLeast(0)) + right
    }

    private fun body(width: Int, height: Int): List<Line> {
        val room = (height - CHROME_LINES).coerceAtLeast(MINIMUM_BODY)
        val rest = width - SECTION_WIDTH - Frame.GUTTER
        val asked = overlay
        // A question opens beside its answer where there is room, so the row it is about stays in view.
        val roomForThree = asked != null && rest >= WORD_PANE_LEAST + QUESTION_LEAST + Frame.GUTTER
        if (asked != null && !roomForThree) return overlayLines(asked, width, room)
        val wordWidth = if (roomForThree) {
            minOf(WORD_PANE_ASKED, rest - QUESTION_LEAST - Frame.GUTTER).coerceAtLeast(WORD_PANE_LEAST)
        } else {
            rest.coerceAtLeast(MINIMUM_WIDTH / 2)
        }
        val sections = scrolled(sectionList(), part.ordinal, room)
        val body = Frame.beside(sections, SECTION_WIDTH, partLines(wordWidth, room), wordWidth).take(room)
        if (asked == null) return body
        val questionWidth = (rest - wordWidth - Frame.GUTTER).coerceAtMost(QUESTION_MOST)
        val question = overlayLines(asked, questionWidth, room)
        return body.mapIndexed { at, line -> line + Line(" ${Glyph.BAR} ", Palette.rule) + (question.getOrNull(at) ?: Line.BLANK) }
    }

    private fun sectionList(): List<Line> = Part.entries.flatMap { entry ->
        listOfNotNull(Line.BLANK.takeIf { entry.startsGroup }) + sectionLine(entry)
    }

    private fun sectionLine(entry: Part): Line {
        val here = entry == part
        val marker = if (here && !inside) "${Glyph.FOCUS} " else "  "
        val name = when {
            entry.perilous -> Palette.refused
            here -> Palette.focused
            else -> Palette.faint
        }
        val warned = if (entry.perilous) "${Glyph.WARN} " else ""
        return Line(marker, Palette.focused) + Line(warned, Palette.refused) +
            Line(entry.title.padEnd(MARK_COLUMN - warned.length), name) + Line(filledness(entry), Palette.faint)
    }

    /** A glance at how much of each part has been said, so nothing is forgotten by not being visited. */
    private fun filledness(entry: Part): String = when (entry) {
        Part.NAME -> if (candidate.name.isBlank()) "" else Glyph.TICK
        Part.COST -> word().price.toString()
        Part.LISTING -> if (WordFile.listingOf(candidate).rarity == null) "" else Glyph.TICK
        Part.MEANINGS -> candidate.meanings.size.takeIf { it > 0 }?.toString().orEmpty()
        Part.TAKES -> candidate.takes.size.takeIf { it > 0 }?.toString().orEmpty()
        Part.REVIEW, Part.DELETE -> ""
        Part.COMMENT -> if (candidate.comment.isEmpty()) "" else Glyph.TICK
        Part.SAVE, Part.SAVE_AND_LEAVE -> if (unsaved) Glyph.WARN else Glyph.TICK
    }

    private fun partLines(width: Int, room: Int): List<Line> {
        val shown = rows()
        val at = row()
        val head = if (part.about.isEmpty()) listOf(Line.BLANK) else listOf(Line(part.about, Palette.faint), Line.BLANK)
        val listed = shown.mapIndexed { index, entry ->
            val marker = if (index == at && inside) "${Glyph.FOCUS} " else "  "
            val body = typing?.takeIf { it.handle == entry.handle }?.let { said ->
                entry.shown.dropLast(1) + Ink(said.text, Palette.value) + Ink(Glyph.FULL, Palette.chosen)
            } ?: entry.shown
            Line(marker, Palette.focused) + Line(body)
        }
        val note = shown.getOrNull(at)?.note.orEmpty()
        val said = if (note.isEmpty()) {
            emptyList()
        } else {
            listOf(Line.BLANK) + note.lines().map { line -> Line("  $line", if (line.startsWith(" ")) Palette.faint else Palette.value) }
        }
        val tail = axisUnderTheCursor(width) + said
        val forTheList = listed.size.coerceAtMost((room - head.size - NOTE_LEAST).coerceAtLeast(1))
        val forTheNote = (room - head.size - forTheList).coerceAtLeast(0)
        val window = scrolled(listed, at, forTheList)
        val padding = List((room - head.size - window.size - minOf(tail.size, forTheNote)).coerceAtLeast(0)) { Line.BLANK }
        return (head + window + padding + tail.take(forTheNote)).map { it.sized(width) }
    }

    /** The axis a ranged parameter under the cursor holds, with what the word asks for shaded on it. */
    private fun axisUnderTheCursor(width: Int): List<Line> {
        val entry = rows().getOrNull(row())?.handle as? Handle.Entry ?: return emptyList()
        val effect = candidate.meanings.getOrNull(entry.meaning)?.effects?.getOrNull(entry.effect) ?: return emptyList()
        val (key, said) = when (effect) {
            is Effect.Sets -> effect.parameter to effect.value
            is Effect.Bends -> effect.parameter to effect.value
            else -> return emptyList()
        }
        val parameter = parameterFor(key) ?: return emptyList()
        if (parameter.holds != Holds.RANGE) return emptyList()
        return listOf(Line.BLANK) + Axis.chart(parameter = parameter, said = said, width = width)
    }

    private fun scrolled(lines: List<Line>, at: Int, room: Int): List<Line> {
        if (room <= 0) return emptyList()
        if (lines.size <= room) return lines
        val first = (at - room / 2).coerceIn(0, lines.size - room)
        return lines.subList(first, first + room)
    }

    private fun rowsInView(): Int {
        val room = (canvas.height - CHROME_LINES).coerceAtLeast(MINIMUM_BODY)
        val head = if (part.about.isEmpty()) 1 else 2
        return (room - head - NOTE_LEAST).coerceAtLeast(1)
    }

    private fun overlayLines(shown: Overlay, width: Int, room: Int): List<Line> = when (shown) {
        is Prompt -> promptLines(shown, width)
        is Picker -> pickerLines(shown, width, room)
        is Reader -> readerLines(shown, width, room)
        is Band -> bandLines(shown, width, room)
    }

    /** The axis, then the three shapes a value can take, then how the corpus already says it. */
    private fun bandLines(band: Band, width: Int, room: Int): List<Line> {
        val head = listOf(Line(band.title, Palette.heading)) +
            Axis.chart(
                parameter = band.parameter,
                said = band.drawn.ifEmpty { null },
                width = width,
                handle = band.handle,
                from = band.drawnFrom,
                forExample = band.illustrated,
            ) +
            Line.BLANK
        fun saidBy(at: Int) = when (Band.Row.entries.getOrNull(at)) {
            Band.Row.BAND -> band.band.ifEmpty { "nothing" }
            Band.Row.NUDGE -> Setting.Shift(band.nudge).spelled()
            Band.Row.SPREAD -> Setting.Spread(band.spread).spelled()
            null -> ""
        }
        val cells = band.shown.mapIndexed { at, option -> listOf(option.label, saidBy(at), option.note) }
        val widths = Columns.widths(cells, BAND_COLUMNS, (width - CURSOR_ROOM).coerceAtLeast(MINIMUM_ROOM), gap = COLUMN_GAP)
        val listed = band.shown.mapIndexed { at, option ->
            val here = at == band.index
            val row = listOf(
                Ink(option.label, if (here) Palette.value else Palette.faint),
                Ink(saidBy(at), if (here) Palette.chosen else Palette.faint),
                Ink(option.note, Palette.faint),
            )
            listOfNotNull(Line.BLANK.takeIf { option.startsGroup }) +
                listOf(Line(if (here) "${Glyph.FOCUS} " else "  ", Palette.focused) + Columns.laid(row, widths, Line(" ".repeat(COLUMN_GAP))))
        }.flatten()
        bandWindow = (room - head.size).coerceAtLeast(1)
        return (head + scrolled(listed, band.index, bandWindow)).map { it.sized(width) }
    }

    private fun promptLines(prompt: Prompt, width: Int) = listOf(
        Line(prompt.title, Palette.heading),
        Line(prompt.hint, Palette.faint),
        Line.BLANK,
        Line("  > ", Palette.chosen) + Line(prompt.typed, Palette.value) + Line(Glyph.FULL, Palette.chosen),
        Line.BLANK,
        Line("  ${prompt.says.orEmpty()}", Palette.refused),
    ).map { it.sized(width) }

    /** A picker drawn, its two columns measured off its rows. */
    private fun pickerLines(picker: Picker, width: Int, room: Int): List<Line> {
        val head = listOf(Line(picker.title, Palette.heading), hints(searching(picker.filter))) +
            picker.chart?.invoke(picker.focused, width).orEmpty() + Line.BLANK
        val shown = picker.shown
        val marking = shown.maxOfOrNull { it.mark.length }?.takeIf { it > 0 }?.plus(1) ?: 0
        val chrome = CURSOR_ROOM + marking + TICK_ROOM
        val widths = Columns.widths(shown.map { listOf(it.label, it.note) }, PICKER_COLUMNS, (width - chrome).coerceAtLeast(MINIMUM_ROOM), gap = COLUMN_GAP)
        val listed = shown.mapIndexed { index, option ->
            val here = index == picker.index
            val tone = option.tone ?: if (here) Palette.value else Palette.faint
            Line(if (here) "${Glyph.FOCUS} " else "  ", Palette.focused) +
                Line(" ".repeat(marking).let { if (option.mark.isEmpty()) it else "${option.mark} " }, option.tone ?: Palette.faint) +
                Line(cell(option.label, widths[0]), tone) +
                Line(if (picker.isMarked(option)) " ${Glyph.TICK} " else "   ", Palette.settled) +
                Line(cell(option.note, widths[1]), Palette.faint)
        }
        val body = listed.ifEmpty { listOf(Line("  nothing matches", Palette.warned)) }
        pickerWindow = (room - head.size).coerceAtLeast(1)
        return (head + scrolled(body, picker.index, pickerWindow)).map { it.sized(width) }
    }

    private fun readerLines(reader: Reader, width: Int, room: Int): List<Line> {
        val head = listOf(Line(reader.title, Palette.heading), Line.BLANK)
        readerWindow = (room - head.size).coerceAtLeast(1)
        val whole = reader.lines.flatMap { it.wrapped(width, READER_HANGING) }
        reader.rows = whole.size
        return (head + whole.drop(reader.offset).take(readerWindow)).map { it.sized(width) }
    }

    private fun strip(width: Int): List<Line> {
        message?.let { return listOf(Line("  $it", Palette.settled)) + List(STRIP_LINES - 1) { Line.BLANK } }
        val ordered = findings.sortedBy { it.standing.ordinal }
        val counted = Verdict.Standing.entries.mapNotNull { standing ->
            val many = findings.count { it.standing == standing }
            if (many == 0) null else Ink("$many ${standing.name.lowercase()}  ", standing.style)
        }
        val summary = if (counted.isEmpty()) Line("  ${Glyph.TICK} nothing to answer for", Palette.settled) else Line("  ") + Line(counted)
        val said = ordered.take(STRIP_LINES - 1).map { finding ->
            Line("  ${finding.standing.mark} ", finding.standing.style) +
                Line(finding.says, Palette.value) +
                Line(finding.because?.let { " ${Glyph.BULLET} $it" }.orEmpty(), Palette.faint) +
                Line(finding.heldBy?.let { "  [$it]" }.orEmpty(), Palette.faint)
        }
        return (listOf(summary) + said + List(STRIP_LINES) { Line.BLANK }).take(STRIP_LINES).map { it.sized(width) }
    }

    /** The key line, in two rows: what the thing under the cursor does, then how to get around. */
    private fun keys(width: Int): List<Line> = listOf(doingKeys(), gettingAround()).map { it.sized(width) }

    private fun doingKeys(): Line = typing?.let { said ->
        hints("enter" to "accept", "↑↓" to "accept and move on", "" to said.complaint(said.text).orEmpty())
    } ?: when (val shown = overlay) {
        is Prompt -> hints("enter" to "accept", "" to shown.says.orEmpty())
        is Picker -> if (shown.marking) {
            hints("enter" to "mark", "→" to if (shown.marked.isEmpty()) "take this one" else "take the ${shown.marked.size} marked")
        } else {
            hints("enter" to "pick", "→" to "pick", if (shown.onClear == null) "" to "" else "^d" to "clear")
        }
        is Reader -> hints("" to "nothing to change here")
        is Band -> when {
            shown.onAPreset -> hints("enter" to "take it")
            shown.row == Band.Row.BAND -> hints(
                "- =" to "move the ${if (shown.onTheHighEnd) "top" else "bottom"}",
                "tab" to "the other end",
                "_ +" to "drop the bottom or top",
                "enter" to "take it",
            )
            else -> hints("- =" to "step it", "enter" to "take it")
        }
        null -> if (!inside) hints("→" to "open this part") else hints(*parts.keysFor(rows().getOrNull(row())).toTypedArray())
    }

    private fun gettingAround(): Line = when {
        typing != null -> hints("esc" to "leave it alone")
        overlay is Prompt -> hints("esc" to "cancel")
        overlay is Band -> hints("↑↓" to "move", "←" to "back", searching((overlay as Band).filter))
        overlay != null -> hints("↑↓" to "move", "pgup/pgdn" to "a page", "home/end" to "ends", "←" to "back")
        !inside -> hints(
            "↑↓" to "part", if (canLeave) "←" to "back" else "" to "",
            "?" to "help", "^t" to "try", "^f" to "faults", "^z" to "undo", "^s" to "save",
        )
        else -> hints(
            "↑↓" to "row", "pgup/pgdn" to "a page", "←" to "back",
            "?" to "help", "^t" to "try", "^f" to "faults", "^z" to "undo", "^s" to "save",
        )
    }

    // -- keys ----------------------------------------------------------------------------------------

    private fun handle(key: KeyboardEvent) {
        typing?.let { return handleTyping(it, key) }
        overlay?.let { return handleOverlay(it, key) }
        val here = rows().getOrNull(row())?.handle
        when {
            key.ctrl && key.key == "q" -> if (unsaved) askBeforeLeaving(quits = true) else quitting = true
            key.ctrl && key.key == "s" -> save()
            key.ctrl && key.key == "z" -> undo()
            key.ctrl && key.key == "f" -> overlay = faults()
            key.ctrl && key.key == "t" -> askForASentence()
            key.key == "?" -> openTheHelp()
            key.key == "ArrowUp" -> if (inside) move(-1) else turnTo(previousPart())
            key.key == "ArrowDown" -> if (inside) move(1) else turnTo(nextPart())
            key.key == "ArrowRight" -> inside = true
            key.key == "ArrowLeft" -> if (inside) inside = false else askBeforeLeaving()
            key.key == "Enter" -> if (inside) act() else inside = true
            key.key == "Escape" -> if (inside) inside = false else askBeforeLeaving()
            key.key == "PageUp" -> if (inside) move(-rowsInView()) else turnTo(previousPart())
            key.key == "PageDown" -> if (inside) move(rowsInView()) else turnTo(nextPart())
            key.key == "Home" -> if (inside) moveTo(0) else turnTo(Part.entries.first())
            key.key == "End" -> if (inside) moveTo(rows().size - 1) else turnTo(Part.entries.last())
            !inside -> Unit
            key.key == "=" -> step(here, A_STEP)
            key.key == "-" -> step(here, -A_STEP)
            key.key == "m" && here is Handle.MeaningHead -> edit { it.changingMeaning(here.meaning) { meaning -> meaning.mustAlways(!meaning.mustAlwaysApply) } }
            key.key == "s" && here is Handle.MeaningHead -> edit { it.changingMeaning(here.meaning) { meaning -> meaning.asserting(!meaning.asserts) } }
            key.key == "a" && parts.isAList(part) -> add(here)
            key.key == "d" && parts.isAList(part) -> remove(here)
        }
    }

    /** A question opened over another goes back to it, rather than back to the word. */
    private fun handleOverlay(shown: Overlay, key: KeyboardEvent) {
        val goingBack = if (shown is Prompt) key.key == "Escape" else key.key == "Escape" || key.key == "ArrowLeft"
        if (goingBack && wentThrough.isNotEmpty()) {
            overlay = wentThrough.removeLast()
            return
        }
        dispatchOverlay(shown, key)
        val now = overlay
        when {
            now == null -> wentThrough.clear()
            now.title == shown.title -> Unit
            else -> wentThrough.addLast(shown)
        }
    }

    private fun dispatchOverlay(shown: Overlay, key: KeyboardEvent) {
        when (shown) {
            is Prompt -> when {
                key.key == "Escape" -> overlay = null
                key.key == "Enter" -> if (shown.says == null) {
                    overlay = null
                    shown.onDone(shown.typed)
                }
                key.key == "Backspace" -> shown.backspace()
                key.key.length == 1 && !key.ctrl && !key.alt -> shown.type(key.key)
            }
            is Picker -> when {
                key.key == "Escape" || key.key == "ArrowLeft" -> overlay = null
                key.ctrl && key.key == "d" -> {
                    overlay = null
                    shown.onClear?.invoke()
                }
                // Enter marks where several may be taken, leaving the list standing; right takes the marked.
                key.key == "Enter" -> shown.focused?.let { picked ->
                    if (shown.marking) shown.mark(picked.value) else {
                        overlay = null
                        shown.onPick(picked)
                    }
                }
                key.key == "ArrowRight" -> shown.focused?.let { picked ->
                    overlay = null
                    val takingTheMarked = shown.onOnly
                    if (takingTheMarked == null) shown.onPick(picked) else takingTheMarked(picked, shown.marked.ifEmpty { setOf(picked.value) })
                }
                key.key == "ArrowUp" -> shown.move(-1)
                key.key == "ArrowDown" -> shown.move(1)
                key.key == "PageUp" -> shown.page(-pickerWindow)
                key.key == "PageDown" -> shown.page(pickerWindow)
                key.key == "Home" -> shown.page(-shown.shown.size)
                key.key == "End" -> shown.page(shown.shown.size)
                key.key == "Backspace" -> shown.backspace()
                key.key.length == 1 && !key.ctrl && !key.alt -> shown.type(key.key)
            }
            is Band -> when {
                key.key == "Escape" || key.key == "ArrowLeft" -> overlay = null
                key.key == "Enter" -> {
                    val said = shown.spelled
                    overlay = null
                    if (said.isNotEmpty()) shown.onDone(said)
                }
                key.key == "ArrowUp" -> shown.move(-1)
                key.key == "ArrowDown" -> shown.move(1)
                key.key == "Tab" -> shown.turn()
                key.key == "=" -> shown.step(A_STEP)
                key.key == "-" -> shown.step(-A_STEP)
                key.key == "+" -> shown.dropTheEnd(high = true)
                key.key == "_" -> shown.dropTheEnd(high = false)
                key.key == "Backspace" -> shown.backspace()
                key.key.length == 1 && !key.ctrl && !key.alt -> shown.type(key.key)
            }
            is Reader -> when {
                key.ctrl && key.key == "q" -> { overlay = null; quitting = true }
                key.key == "Escape" || key.key == "Enter" || key.key == "ArrowLeft" || (key.key == "q" && !key.ctrl) -> overlay = null
                key.key == "ArrowUp" -> shown.scroll(-1, readerWindow)
                key.key == "ArrowDown" -> shown.scroll(1, readerWindow)
                key.key == "PageUp" -> shown.scroll(-readerWindow, readerWindow)
                key.key == "PageDown" -> shown.scroll(readerWindow, readerWindow)
                key.key == "Home" -> shown.scroll(-shown.lines.size, readerWindow)
                key.key == "End" -> shown.scroll(shown.lines.size, readerWindow)
            }
        }
    }

    private fun turnTo(wanted: Part) {
        part = wanted
        rowOf[part] = restingPlace(rowOf.getValue(part).coerceIn(0, (rows().size - 1).coerceAtLeast(0)), 1)
        wentThrough.clear()
    }

    private fun nextPart() = Part.entries[(part.ordinal + 1) % Part.entries.size]

    private fun previousPart() = Part.entries[(part.ordinal - 1 + Part.entries.size) % Part.entries.size]

    /** Leaving, with saving offered first: three answers, so somebody who wanted to save is not made to say no. */
    private fun askBeforeLeaving(quits: Boolean = false) {
        fun go() = if (quits) quitting = true else leaving = canLeave
        if (!unsaved || (!canLeave && !quits)) return go()
        val name = candidate.name.ifBlank { "this word" }
        overlay = Picker(
            title = "'$name' has changes that are not saved",
            options = listOf(
                Picker.Option(SAVE_AND_LEAVE, "save it and leave", "write the file, then go"),
                Picker.Option(LEAVE_ANYWAY, "leave without saving", "the changes are lost"),
                Picker.Option(STAY, "keep editing", "", startsGroup = true),
            ),
        ) { picked ->
            when (picked.value) {
                SAVE_AND_LEAVE -> {
                    save()
                    if (!unsaved) go()
                }
                LEAVE_ANYWAY -> go()
                else -> Unit
            }
        }
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

    private fun typeOn(handle: Handle, standing: String, complaint: (String) -> String? = { null }, commit: (String) -> Unit) {
        typing = Typing(handle, standing, complaint, commit)
    }

    private fun move(by: Int) {
        val size = rows().size
        if (size == 0) return
        val wanted = if (kotlin.math.abs(by) > 1) (row() + by).coerceIn(0, size - 1) else row() + by
        rowOf[part] = restingPlace(((wanted % size) + size) % size, if (by < 0) -1 else 1)
    }

    /** The nearest row the cursor may rest on, walking [towards] — a heading is read, never landed on. */
    private fun restingPlace(from: Int, towards: Int): Int {
        val listed = rows()
        if (listed.isEmpty()) return 0
        var at = from.coerceIn(0, listed.size - 1)
        repeat(listed.size) {
            if (!parts.isAHeading(listed[at])) return at
            at = ((at + towards) % listed.size + listed.size) % listed.size
        }
        return from
    }

    private fun moveTo(where: Int) {
        val size = rows().size
        if (size == 0) return
        rowOf[part] = restingPlace(where.coerceIn(0, size - 1), if (where == 0) 1 else -1)
    }

    /** The cursor onto [handle], wherever its section put it. */
    private fun landOn(handle: Handle) {
        val at = rows().indexOfFirst { it.handle == handle }
        if (at >= 0) rowOf[part] = at
    }

    // -- what a row does -----------------------------------------------------------------------------

    private fun act() {
        when (val handle = rows().getOrNull(row())?.handle ?: return) {
            Handle.Heading, Handle.Said -> Unit
            Handle.Name -> renameTo()
            Handle.Display -> retitle()
            Handle.Speech -> pickASpeech()
            Handle.Rarity -> relist()
            Handle.InkQuality -> reink()
            is Handle.MeaningHead -> askHowLikely(handle.meaning)
            is Handle.Entry -> change(handle)
            is Handle.AddEffect -> pickAKind(handle.meaning)
            Handle.AddMeaning -> addAMeaning()
            is Handle.Take -> message = "d takes it out"
            Handle.AddTake -> pickATake()
            Handle.Comment -> openTheEditor()
            is Handle.Save -> { save(); if (handle.andLeave && !unsaved) quitting = true }
            Handle.Delete -> askAboutDeleting()
        }
    }

    private fun add(here: Handle?) = when (here) {
        is Handle.MeaningHead -> pickAKind(here.meaning)
        is Handle.Entry -> pickAKind(here.meaning)
        is Handle.AddEffect -> pickAKind(here.meaning)
        is Handle.Take, Handle.AddTake -> pickATake()
        else -> addAMeaning()
    }

    private fun remove(here: Handle?) {
        when (here) {
            is Handle.MeaningHead -> edit { it.withoutMeaning(here.meaning) }
            is Handle.Entry -> edit { it.changingMeaning(here.meaning) { meaning -> meaning.withoutEntry(here.effect, here.key) } }
            is Handle.Take -> edit { candidate -> candidate.copy(takes = candidate.takes.filterNot { it.path == here.page }.toSet()) }
            else -> message = "nothing here to remove"
        }
        moveTo(row())
    }

    /** One step of the number under the cursor — a chance aimed, a lean's weight or a bar. */
    private fun step(here: Handle?, by: Double) {
        when (here) {
            is Handle.MeaningHead -> edit { it.changingMeaning(here.meaning) { meaning -> meaning.withChance(meaning.chanceToApply + by) } }
            is Handle.Entry -> {
                val key = here.key ?: return
                val effect = candidate.meanings.getOrNull(here.meaning)?.effects?.getOrNull(here.effect) ?: return
                val standing = weightOf(effect, key) ?: return
                val stepped = if (effect is Effect.Restricts) steppedBar(standing, by) else standing + by
                edit { it.changingMeaning(here.meaning) { meaning -> meaning.withEntryValue(here.effect, key, rounded(stepped)) } }
            }
            else -> Unit
        }
    }

    private fun weightOf(effect: Effect, key: String): Double? = when (effect) {
        is Effect.Leans -> effect.weights[key]
        is Effect.LeansEverywhere -> effect.weights[key]
        is Effect.Restricts -> effect.bars[key]
        else -> null
    }

    /** A bar stepped toward or away from what it bars, never through nothing: at least and at most keep their sign. */
    private fun steppedBar(bar: Double, by: Double): Double {
        val sign = if (Bars.isAtLeast(bar)) 1.0 else -1.0
        return sign * (kotlin.math.abs(bar) + by).coerceIn(LOWEST_BAR, Bars.STRICT)
    }

    private fun rounded(value: Double) = kotlin.math.round(value * HUNDREDTHS) / HUNDREDTHS

    // -- a meaning's chances -------------------------------------------------------------------------

    private fun askHowLikely(at: Int) {
        val meaning = candidate.meanings.getOrNull(at) ?: return
        overlay = Picker(
            title = "How likely is meaning ${at + 1}?",
            options = listOf(
                Picker.Option(MUST, "must always apply", "aimed it does; laid bare, at one place it reaches at least", marked = meaning.mustAlwaysApply),
                Picker.Option(CERTAIN, "certain where aimed", "and the Art's share of that laid bare", marked = !meaning.mustAlwaysApply && meaning.chanceToApply == Meaning.CERTAIN),
                Picker.Option(AIMED, "a chance where aimed", "${meaning.chanceToApply}"),
                Picker.Option(BARE, "a chance laid bare", meaning.unaimedChanceToApply?.toString() ?: "the Art's share of the aimed chance"),
                Picker.Option(ASSERTS, if (meaning.asserts) "stop asserting" else "asserts", "needs nothing to land on, and lands alone where nothing takes it", startsGroup = true),
            ),
        ) { picked ->
            when (picked.value) {
                MUST -> edit { it.changingMeaning(at) { meaning -> meaning.mustAlways(true) } }
                CERTAIN -> edit { it.changingMeaning(at) { meaning -> meaning.mustAlways(false).withChance(Meaning.CERTAIN) } }
                AIMED -> askForAChance("The chance meaning ${at + 1} applies where aimed", meaning.chanceToApply.toString()) { chance ->
                    edit { it.changingMeaning(at) { meaning -> meaning.withChance(chance ?: Meaning.CERTAIN) } }
                }
                BARE -> askForAChance("The chance it applies laid bare, or nothing for the Art's share", meaning.unaimedChanceToApply?.toString().orEmpty()) { chance ->
                    edit { it.changingMeaning(at) { meaning -> meaning.withUnaimedChance(chance) } }
                }
                ASSERTS -> edit { it.changingMeaning(at) { meaning -> meaning.asserting(!meaning.asserts) } }
            }
        }
    }

    private fun askForAChance(title: String, standing: String, done: (Double?) -> Unit) {
        overlay = Prompt(
            title = title,
            hint = "a share from 0 to 1",
            typed = standing,
            complaint = { typed ->
                val chance = typed.toDoubleOrNull()
                if (typed.isBlank() || (chance != null && chance in Meaning.NEVER..Meaning.CERTAIN)) null else "a share from 0 to 1"
            },
            onDone = { typed -> done(typed.toDoubleOrNull()) },
        )
    }

    private fun addAMeaning() {
        edit { it.withMeaning(Meaning(emptyList())) }
        val at = candidate.meanings.lastIndex
        landOn(Handle.MeaningHead(at))
        pickAKind(at)
    }

    // -- adding an effect: what kind, then where, then what -------------------------------------------

    private fun pickAKind(meaning: Int) {
        if (candidate.isDerived) {
            message = "${candidate.name} is written by the game, not by a file — only its rarity and ink can be set"
            return
        }
        val kinds = listOf(
            Picker.Option(SETS, "set a property", "a parameter to a value: a temperature, a colour, a size"),
            Picker.Option(BENDS, "bend a range", "move a range without bounding it"),
            Picker.Option(CHOOSES, "choose a member", "this landform, this sea, this creature, and no other", startsGroup = true),
            Picker.Option(ADMITS, "add a member", "put a member in the pool that curation left out"),
            Picker.Option(KEEPS, "keep what carries a tag", "only what answers it survives"),
            Picker.Option(STRIKES, "strike a member or a tag", "take them out of the pool"),
            Picker.Option(LEANS, "lean toward or away", "tilt the draw, never removing anything"),
            Picker.Option(MINTS, "mint a pattern", "a feature made of what its clause lays", startsGroup = true),
            Picker.Option(TEMPLATE, "begin from a world", "the world the book starts from"),
            Picker.Option(ABUNDANCE, "ask for more or fewer", "how many of what it is laid before"),
        )
        overlay = Picker("Meaning ${meaning + 1}: what does it do?", kinds) { picked ->
            when (picked.value) {
                SETS -> pickAParameter(meaning, rangedOnly = false)
                BENDS -> pickAParameter(meaning, rangedOnly = true)
                CHOOSES -> pickAnAspect(meaning, "Choose in which part?", ::holdsMembers) { aspect -> pickAMember(meaning, aspect) }
                ADMITS -> pickAnAspect(meaning, "Add to which pool?", ::isAPool) { aspect -> pickMembersToAdmit(meaning, aspect) }
                KEEPS -> pickAnAspect(meaning, "Keep by a tag, in which part?", ::holdsMembers) { aspect -> pickATagToKeep(meaning, aspect) }
                STRIKES -> pickAnAspect(meaning, "Strike in which part?", ::holdsMembers) { aspect -> pickSomethingToStrike(meaning, aspect) }
                LEANS -> pickWhereToLean(meaning)
                MINTS -> pickAPattern(meaning, standing = null)
                TEMPLATE -> pickATemplate(meaning, standing = null)
                ABUNDANCE -> pickARung(meaning, standing = null)
            }
        }
    }

    private fun adding(meaning: Int, effect: Effect) = edit { it.changingMeaning(meaning) { standing -> standing.withEffect(effect) } }

    private fun holdsMembers(aspect: Aspect) = corpus.vocabulary.candidatesFor(aspect).isNotEmpty()

    private fun isAPool(aspect: Aspect) = aspect.holds == Holds.WEIGHTED_SET

    private fun pickAnAspect(meaning: Int, title: String, offers: (Aspect) -> Boolean, then: (Aspect) -> Unit) {
        val options = Aspect.entries.filter(offers).map { aspect ->
            Picker.Option(aspect.page, aspect.page, "${corpus.vocabulary.candidatesFor(aspect).size} members")
        }
        overlay = Picker(title, options) { picked -> Aspect.byPage(picked.value)?.let(then) }
    }

    /** Every parameter by name, with the parts of the world that have it — then which of them, then its value. */
    private fun pickAParameter(meaning: Int, rangedOnly: Boolean) {
        val byName = Aspect.entries.flatMap { aspect -> aspect.understood.map { it.name to aspect } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, aspects) -> aspects.distinct() }
            .filterKeys { name -> name != Parameter.CAST && (!rangedOnly || name in RANGED_PARAMETERS) }
        val options = byName.entries.sortedBy { it.key }.map { (name, aspects) ->
            Picker.Option(name, name, aspects.joinToString(" ") { it.page })
        }
        overlay = Picker(if (rangedOnly) "Bend which range?" else "Set which property?", options) { picked ->
            val owners = byName[picked.value].orEmpty()
            if (owners.size <= 1) askForAValue(meaning, listOf(ParameterKey(picked.value)), rangedOnly, standing = null)
            else pickWhichOwners(meaning, picked.value, owners, rangedOnly)
        }
    }

    /** Which parts of the world one name lands on: every one, unfenced, or those marked, each fenced to its part. */
    private fun pickWhichOwners(meaning: Int, name: String, owners: List<Aspect>, rangedOnly: Boolean) {
        val options = listOf(Picker.Option(EVERYWHERE, "every part that has it", owners.joinToString(" ") { it.page })) +
            owners.map { Picker.Option(it.page, it.page, "only here", startsGroup = it == owners.first()) }
        overlay = Picker(
            title = "'$name' on which parts? enter marks, → takes them",
            options = options,
            marking = true,
            onOnly = { _, marked ->
                val keys = if (EVERYWHERE in marked) listOf(ParameterKey(name)) else marked.mapNotNull(Aspect::byPage).map { ParameterKey(name, it) }
                askForAValue(meaning, keys, rangedOnly, standing = null)
            },
        ) {}
    }

    /** The value one or more keys of a parameter take: on its axis, from its options, or a block. */
    private fun askForAValue(meaning: Int, keys: List<ParameterKey>, rangedOnly: Boolean, standing: String?, replacing: Int? = null) {
        val first = keys.firstOrNull() ?: return
        val parameter = parameterFor(first) ?: return run { message = "no part of the world has '${first.spelled}'" }
        fun take(value: String) {
            val effects = keys.map { key -> if (rangedOnly) Effect.Bends(key, value) else Effect.Sets(key, value) }
            edit { word ->
                word.changingMeaning(meaning) { held ->
                    val cleared = replacing?.let { held.withoutEntry(it, null) } ?: held
                    effects.fold(cleared) { at, effect -> at.withEffect(effect) }
                }
            }
        }
        when {
            parameter.holds == Holds.RANGE -> overlay = Band(
                title = "${first.spelled}${if (keys.size > 1) " and ${keys.size - 1} more" else ""}",
                parameter = parameter,
                presets = howTheCorpusSays(first.name),
                said = standing.orEmpty(),
                onDone = ::take,
            )
            parameter.material -> pickAMaterial(first.spelled, standing, ::take)
            else -> overlay = Picker(
                title = "${first.spelled} as what?",
                options = parameter.options.map { option ->
                    Picker.Option(option, option, parameter.optionHelp[option].orEmpty(), marked = option == standing)
                },
            ) { picked -> take(picked.value) }
        }
    }

    /** How the words already written set [name], so "the same as `arid`" is a thing to pick. */
    private fun howTheCorpusSays(name: String): List<Picker.Option> = corpus.vocabulary.authoredWords
        .flatMap { word -> word.effects.filterIsInstance<Effect.Sets>().filter { it.parameter.name == name }.map { word.name to it.value } }
        .distinctBy { it.second }
        .map { (word, value) -> Picker.Option(value, word, value) }

    /** A block a world may be made of, or a tag it is drawn from: `#frozen`. */
    private fun pickAMaterial(title: String, standing: String?, take: (String) -> Unit) {
        val tags = corpus.vocabulary.carriedTags.sorted().filter { corpus.vocabulary.materials.carriersOf(it).isNotEmpty() }
            .map { tag -> Picker.Option("${Materials.QUERY_MARK}$tag", "${Materials.QUERY_MARK}$tag", "${corpus.vocabulary.materials.carriersOf(tag).size} blocks") }
        val blocks = BuiltInRegistries.BLOCK.keySet().map { it.toString() }.sorted().map { Picker.Option(it, it) }
        overlay = Picker(
            title = "$title as what? a block, or a tag it is drawn from",
            options = tags + blocks.mapIndexed { at, option -> if (at == 0) option.copy(startsGroup = true) else option },
            filter = "",
        ) { picked -> take(picked.value) }
        if (standing != null) message = "now $standing"
    }

    private fun pickAMember(meaning: Int, aspect: Aspect, replacing: Int? = null) {
        val options = corpus.vocabulary.candidatesFor(aspect).map { member ->
            Picker.Option(member.key, member.key, corpus.vocabulary.tagsOf(member).keys.sorted().joinToString(" ") { "#$it" })
        }
        overlay = Picker("Which ${aspect.page}?", options) { picked ->
            edit { word ->
                word.changingMeaning(meaning) { held ->
                    val effect = Effect.Chooses(aspect, picked.value)
                    replacing?.let { held.withEffectAt(it, effect) } ?: held.withEffect(effect)
                }
            }
        }
    }

    private fun pickMembersToAdmit(meaning: Int, aspect: Aspect) {
        val curated = corpus.vocabulary.availableToBroadWordsIn(aspect).map { it.key }.toSet()
        val options = corpus.vocabulary.candidatesFor(aspect).filterNot { it.key in curated }.map { Picker.Option(it.key, it.key, "kept from broad words") }
        overlay = Picker(
            title = "Add which to the ${aspect.page}? enter marks, → takes them",
            options = options.ifEmpty { listOf(Picker.Option(NOTHING, "every member is in the pool already")) },
            marking = true,
            onOnly = { _, marked ->
                val admitted = marked - NOTHING
                if (admitted.isNotEmpty()) adding(meaning, Effect.Admits(aspect, admitted))
            },
        ) {}
    }

    private fun tagsIn(aspect: Aspect): List<String> =
        corpus.vocabulary.candidatesFor(aspect).flatMap { corpus.vocabulary.tagsOf(it).keys }.distinct().sorted()

    private fun carriersNote(aspect: Aspect, tag: String): String {
        val carriers = corpus.vocabulary.candidatesFor(aspect).count { (corpus.vocabulary.tagsOf(it)[tag] ?: 0.0) > 0.0 }
        return "$carriers carry it"
    }

    private fun pickATagToKeep(meaning: Int, aspect: Aspect) {
        val options = tagsIn(aspect).map { tag -> Picker.Option(tag, "#$tag", carriersNote(aspect, tag)) }
        overlay = Picker("Keep in the ${aspect.page} what carries which tag?", options) { picked ->
            adding(meaning, Effect.Restricts(aspect, mapOf(picked.value to DEFAULT_BAR)))
        }
    }

    private fun pickSomethingToStrike(meaning: Int, aspect: Aspect) {
        val tags = tagsIn(aspect).map { tag -> Picker.Option("#$tag", "#$tag", carriersNote(aspect, tag)) }
        val members = corpus.vocabulary.candidatesFor(aspect).map { Picker.Option(it.key, it.key, "", startsGroup = false) }
        overlay = Picker(
            title = "Strike what from the ${aspect.page}? enter marks, → takes them",
            options = tags + members.mapIndexed { at, option -> if (at == 0) option.copy(startsGroup = true) else option },
            marking = true,
            onOnly = { _, marked -> adding(meaning, Effect.Excludes(aspect, marked)) },
        ) {}
    }

    private fun pickWhereToLean(meaning: Int) {
        val options = listOf(Picker.Option(EVERYWHERE, "everything", "every part it likes something in")) +
            Aspect.entries.filter(::holdsMembers).map { Picker.Option(it.page, it.page, "${corpus.vocabulary.candidatesFor(it).size} members") }
        overlay = Picker("Lean where?", options) { picked ->
            val aspect = Aspect.byPage(picked.value)
            val tags = (aspect?.let(::tagsIn) ?: corpus.vocabulary.carriedTags.sorted()).map { Picker.Option("#$it", "#$it") }
            val members = aspect?.let { corpus.vocabulary.candidatesFor(it).map { member -> Picker.Option(member.key, member.key) } }.orEmpty()
            overlay = Picker("Lean toward what? then - = on its row", tags + members) { named ->
                val weights = mapOf(named.value to A_WHOLE_LEAN)
                adding(meaning, if (aspect == null) Effect.LeansEverywhere(weights) else Effect.Leans(aspect, weights))
            }
        }
    }

    /** The patterns the corpus mints, and the placed features a pattern can be. */
    private fun pickAPattern(meaning: Int, standing: Effect.Mints?, at: Int? = null) {
        val minted = corpus.vocabulary.words.mapNotNull { it.mints }.distinct().sorted()
        val features = corpus.vocabulary.candidatesFor(Aspect.FEATURES).map { it.key }.filterNot(minted::contains).sorted()
        val options = minted.map { Picker.Option(it, it, "minted by a word already") } +
            features.mapIndexed { index, it -> Picker.Option(it, it, "", startsGroup = index == 0) }
        overlay = Picker("Mint which pattern?", options) { picked ->
            val effect = Effect.Mints(picked.value, standing?.unstated, standing?.flows ?: false)
            edit { word -> word.changingMeaning(meaning) { held -> at?.let { held.withEffectAt(it, effect) } ?: held.withEffect(effect) } }
        }
    }

    private fun changeAMinting(meaning: Int, at: Int, mints: Effect.Mints) {
        overlay = Picker(
            title = "The minting of ${mints.pattern}",
            options = listOf(
                Picker.Option(PATTERN, "the pattern", mints.pattern),
                Picker.Option(UNSTATED, "made of, where its clause lays nothing", mints.unstated ?: "nothing: it mints nothing then"),
                Picker.Option(FLOWS, if (mints.flows) "a block, not a fluid" else "a fluid, flowing", "what the substance does"),
            ),
        ) { picked ->
            when (picked.value) {
                PATTERN -> pickAPattern(meaning, mints, at)
                UNSTATED -> pickAMaterial("made of", mints.unstated) { substance ->
                    edit { it.changingMeaning(meaning) { held -> held.withEffectAt(at, mints.copy(unstated = substance)) } }
                }
                FLOWS -> edit { it.changingMeaning(meaning) { held -> held.withEffectAt(at, mints.copy(flows = !mints.flows)) } }
            }
        }
    }

    private fun pickATemplate(meaning: Int, standing: String?, at: Int? = null) {
        val options = AgeTemplate.entries.map { Picker.Option(it.key, it.key, "", marked = it.key == standing) }
        overlay = Picker("Begin from which world?", options) { picked ->
            val effect = Effect.Template(picked.value)
            edit { word -> word.changingMeaning(meaning) { held -> at?.let { held.withEffectAt(it, effect) } ?: held.withEffect(effect) } }
        }
    }

    private fun pickARung(meaning: Int, standing: Double?, at: Int? = null) {
        val options = RUNGS.map { rung -> Picker.Option(rung.toString(), "${Rung.spelled(rung)} times as many", marked = rung == standing) }
        overlay = Picker("How many?", options) { picked ->
            val effect = Effect.Abundance(picked.value.toDouble())
            edit { word -> word.changingMeaning(meaning) { held -> at?.let { held.withEffectAt(it, effect) } ?: held.withEffect(effect) } }
        }
    }

    /** An entry re-opened: each asks again what adding it asked. */
    private fun change(entry: Handle.Entry) {
        val effect = candidate.meanings.getOrNull(entry.meaning)?.effects?.getOrNull(entry.effect) ?: return
        val key = entry.key
        when (effect) {
            is Effect.Sets -> askForAValue(entry.meaning, listOf(effect.parameter), rangedOnly = false, standing = effect.value, replacing = entry.effect)
            is Effect.Bends -> askForAValue(entry.meaning, listOf(effect.parameter), rangedOnly = true, standing = effect.value, replacing = entry.effect)
            is Effect.Chooses -> pickAMember(entry.meaning, effect.aspect, replacing = entry.effect)
            is Effect.Admits, is Effect.Excludes -> message = "d takes it out; a to add another"
            is Effect.Restricts -> if (key != null) retypeWeight(entry, key, effect.bars[key] ?: DEFAULT_BAR, isABar = true)
            is Effect.Leans -> if (key != null) retypeWeight(entry, key, effect.weights[key] ?: A_WHOLE_LEAN, isABar = false)
            is Effect.LeansEverywhere -> if (key != null) retypeWeight(entry, key, effect.weights[key] ?: A_WHOLE_LEAN, isABar = false)
            is Effect.Mints -> changeAMinting(entry.meaning, entry.effect, effect)
            is Effect.Template -> pickATemplate(entry.meaning, effect.name, entry.effect)
            is Effect.Abundance -> pickARung(entry.meaning, effect.rung, entry.effect)
            is Effect.MadeOf -> Unit
        }
    }

    private fun retypeWeight(entry: Handle.Entry, key: String, standing: Double, isABar: Boolean) {
        val shown = if (isABar) Bars.spell(standing) else standing.toString()
        typeOn(
            handle = entry,
            standing = shown,
            complaint = { typed ->
                when {
                    isABar && Bars.read(typed).result().isEmpty -> "a bar: '>0.6' for at least, '<0.2' for at most"
                    !isABar && typed.toDoubleOrNull() == null -> "a weight: a number, below nought to lean away"
                    else -> null
                }
            },
        ) { typed ->
            val value = if (isABar) Bars.read(typed).result().orElse(standing) else typed.toDouble()
            edit { it.changingMeaning(entry.meaning) { meaning -> meaning.withEntryValue(entry.effect, key, value) } }
        }
    }

    // -- the word itself -----------------------------------------------------------------------------

    private fun renameTo() = typeOn(
        handle = Handle.Name,
        standing = candidate.name,
        complaint = { typed ->
            when {
                typed.isBlank() -> "a word needs a name"
                !WordFile.couldBeAName(typed) -> "lower case, digits and _ - . / only"
                else -> null
            }
        },
    ) { typed -> edit { it.copy(name = typed) } }

    private fun retitle() {
        val id = candidate.listingKey
        typeOn(Handle.Display, WordFile.displayOf(id).orEmpty()) { typed ->
            runCatching { WordFile.setDisplay(id, typed.ifBlank { null }) }
                .onSuccess { message = typed.ifBlank { null }?.let { "shown as $it" } ?: "back to the fallback" }
                .onFailure { message = "could not rename: ${it.message}" }
            judged = null
        }
    }

    private fun pickASpeech() {
        val options = listOf(Picker.Option(UNSET, "as said of something", "the default", marked = candidate.speech == null)) +
            Speech.entries.map { Picker.Option(it.serializedName, it.serializedName, "", marked = it == candidate.speech) }
        overlay = Picker("How is it said?", options) { picked ->
            edit { it.copy(speech = Speech.entries.firstOrNull { speech -> speech.serializedName == picked.value }) }
        }
    }

    private fun pickATake() {
        overlay = Prompt(
            title = "Take which page?",
            hint = "a word this takes when it is laid just before it",
            typed = "",
            complaint = { typed -> if (corpus.vocabulary.word(typed) == null) "no word is called that" else null },
            onDone = { typed -> edit { it.copy(takes = it.takes + typed.location()) } },
        )
    }

    private fun relist() {
        val default = WordFile.defaultBucket()
        val standing = WordFile.listingOf(candidate).rarity.takeUnless { it == default }
        val buckets = WordFile.rarityBuckets().map { Picker.Option(it, it, if (it == standing) "current" else "", marked = it == standing) }
        val keptOut = Picker.Option(WordFile.NO_LOOT, WordFile.NO_LOOT, "never on a found page or in a found notebook", marked = standing == WordFile.NO_LOOT)
        val unset = Picker.Option(UNSET, default ?: UNSET, "every word listed nowhere else", marked = standing == null)
        overlay = Picker("Which rarity?", listOf(unset) + buckets + keptOut, onClear = { writeRarity(null) }) { picked ->
            writeRarity(picked.value.takeIf { it != UNSET })
        }
    }

    private fun writeRarity(bucket: String?) {
        WordFile.setRarity(candidate, bucket)
        message = when (bucket) {
            null -> "rarity set to ${WordFile.defaultBucket() ?: "the default"}"
            WordFile.NO_LOOT -> "kept out of loot"
            else -> "rarity set to $bucket"
        }
        judged = null
    }

    private fun reink() {
        val standing = WordFile.inkOf(candidate)
        val tiers = WordFile.inkTiers().map { Picker.Option(it, it, if (it == standing) "current" else "", marked = it == standing) }
        overlay = Picker(
            "Which ink quality?",
            listOf(Picker.Option(UNSET, UNSET, "any ink writes it", marked = standing == null)) + tiers,
            onClear = { writeInk(null) },
        ) { picked -> writeInk(picked.value.takeIf { it != UNSET }) }
    }

    private fun writeInk(tier: String?) {
        WordFile.setInk(candidate, tier)
        message = tier?.let { "ink set to $it" } ?: "ink cleared"
        judged = null
    }

    /** The reasoning, in `$EDITOR`, with raw mode given up for the duration. */
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

    private fun openTheHelp() {
        raw?.close()
        Help(terminal, canvas, corpus).run()
        raw = terminal.enterRawMode(MouseTracking.Off)
    }

    private fun askAboutDeleting() {
        val had = openedAs
        if (candidate.isDerived || had == null) {
            message = "nothing to delete: this word has no file yet"
            return
        }
        val answers = listOf(
            Picker.Option("keep", "keep it", "nothing changes"),
            Picker.Option("delete", "delete it", "the file, its rarity, its ink and what it is shown as. There is no undo.", mark = Glyph.WARN, tone = Palette.refused),
        )
        overlay = Picker("Delete '${candidate.name}' for good?", answers) { picked ->
            if (picked.value != "delete") return@Picker
            runCatching { WordFile.deleteWord(had) }
                .onSuccess { changed = true; leaving = true }
                .onFailure { message = "could not delete: ${it.message}" }
        }
    }

    // -- the errands ---------------------------------------------------------------------------------

    private fun undo() {
        if (history.size <= 1) {
            message = "nothing to undo"
            return
        }
        history.removeAt(history.lastIndex)
    }

    private fun save() {
        if (candidate.isDerived) {
            message = "${candidate.name} has no file — its rarity and ink are already saved"
            return
        }
        val refused = Verdict.refusals(findings)
        if (refused.isNotEmpty()) {
            message = "not saved: ${refused.size} refused. ^f to see them."
            return
        }
        val file = runCatching {
            openedAs?.takeIf { it != candidate.name }?.let { WordFile.renameWord(it, candidate.name) }
            WordFile.write(candidate)
            WordFile.fileFor(candidate.name)
        }
        message = file.fold(onSuccess = { "saved ${it.path}" }, onFailure = { "could not save: ${it.message}" })
        changed = changed || file.isSuccess
        if (file.isSuccess) written = candidate
    }

    private fun faults(): Reader = Reader(
        "What is said of ${candidate.name.ifBlank { "this word" }}",
        findings.sortedBy { it.standing.ordinal }.map { finding ->
            Line("${finding.standing.mark} ", finding.standing.style) + Line(finding.says, Palette.value) +
                Line(finding.because?.let { " ${Glyph.BULLET} $it" }.orEmpty(), Palette.faint) +
                Line(finding.heldBy?.let { "  [$it]" }.orEmpty(), Palette.faint)
        }.ifEmpty { listOf(Line("nothing to answer for", Palette.settled)) },
    )

    /** A book read and resolved with this word standing in for its saved self. */
    private fun askForASentence() {
        overlay = Prompt(
            title = "Try a book",
            hint = "words in order, with `age`: '${candidate.name} age', or 'age ${candidate.name} sky'",
            typed = "${candidate.name} age",
            complaint = { typed -> if (typed.isBlank()) "type something" else null },
            onDone = { typed -> overlay = tried(typed) },
        )
    }

    private fun tried(typed: String): Reader {
        val pages = typed.trim().split(Regex("\\s+"))
        val tried = Trial.of(candidate, pages, corpus)
            ?: return Reader("'$typed'", listOf(Line("a book needs an `age` page", Palette.refused)))
        val flaws = tried.resolution.instability.flaws.map { Line("${Glyph.WARN} ", Palette.warned) + Line(it.describe(), Palette.faint) }
        return Reader(
            "'$typed' at seed ${Trial.SEED}, ${tried.resolution.cost} ink",
            tried.reading.readout.lines().map { Line(it, Palette.value) } + Line.BLANK +
                Line(tried.spelled, Palette.faint) + Line.BLANK +
                flaws.ifEmpty { listOf(Line("coherent", Palette.settled)) },
        )
    }

    private fun parameterFor(key: ParameterKey): Parameter? =
        Aspect.entries.filter(key::landsOn).firstNotNullOfOrNull { aspect -> aspect.understood.firstOrNull { it.name == key.name } }

    private companion object {
        val MARK_COLUMN: Int = Part.entries.maxOf { it.title.length + if (it.perilous) WARNING_ROOM else 0 } + 1
        val SECTION_WIDTH: Int = CURSOR_ROOM + MARK_COLUMN + MARK_ROOM
        const val CURSOR_ROOM = 2
        const val WARNING_ROOM = 2
        const val MARK_ROOM = 3
        const val WORD_PANE_ASKED = 60
        const val WORD_PANE_LEAST = 34
        const val QUESTION_LEAST = 46
        const val QUESTION_MOST = 92
        const val MINIMUM_WIDTH = 60
        const val MINIMUM_BODY = 8
        const val NOTE_LEAST = 4
        const val STRIP_LINES = 4
        const val CHROME_LINES = 5 + STRIP_LINES
        const val TICK_ROOM = 3
        const val COLUMN_GAP = 2
        const val READER_HANGING = "      "
        const val DEFAULT_EDITOR = "vi"
        val PICKER_COLUMNS = listOf(Columns.Column(), Columns.Column(grows = true))
        val BAND_COLUMNS = listOf(Columns.Column(), Columns.Column(), Columns.Column(grows = true))

        /** How far `-` and `=` move a chance, a weight or a bar. */
        const val A_STEP = 0.1
        const val HUNDREDTHS = 100.0
        const val A_WHOLE_LEAN = 1.0

        /** What a new restriction starts at: at least three tenths. */
        const val DEFAULT_BAR = 0.3

        /** The lowest a stepped bar sits; a bar of nothing asks for nothing. */
        const val LOWEST_BAR = 0.05

        /** The amounts a writer asks for, as the quantifiers already do. */
        val RUNGS = listOf(0.25, 0.5, 2.0, 4.0)

        const val SAVE_AND_LEAVE = "\u0000save"
        const val LEAVE_ANYWAY = "\u0000leave"
        const val STAY = "\u0000stay"
        const val UNSET = "default"
        const val EVERYWHERE = "\u0000all"
        const val NOTHING = "\u0000nothing"
        const val MUST = "must"
        const val CERTAIN = "certain"
        const val AIMED = "aimed"
        const val BARE = "bare"
        const val ASSERTS = "asserts"
        const val SETS = "sets"
        const val BENDS = "bends"
        const val CHOOSES = "chooses"
        const val ADMITS = "admits"
        const val KEEPS = "keeps"
        const val STRIKES = "strikes"
        const val LEANS = "leans"
        const val MINTS = "mints"
        const val TEMPLATE = "template"
        const val ABUNDANCE = "abundance"
        const val PATTERN = "pattern"
        const val UNSTATED = "unstated"
        const val FLOWS = "flows"
    }
}
