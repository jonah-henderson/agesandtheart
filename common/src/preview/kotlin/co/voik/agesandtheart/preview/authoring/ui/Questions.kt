package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Setting
import com.github.ajalt.mordant.input.KeyboardEvent

/**
 * The questions a screen asks over itself — a prompt, a picker, a band on an axis, something to read — drawn in the
 * screen's own frame, with the trail of questions each was opened over, which `esc` and `←` walk back out through.
 *
 * A question's answer may ask the next ([ask] from inside `onPick`), and the one it was asked over joins the trail.
 */
class Questions {

    /** What answering a key came to, for the screen to act on. */
    enum class Answered { STILL_ASKING, QUIT }

    var shown: Overlay? = null
        private set

    private val trail = ArrayDeque<Overlay>()
    private var readerWindow = MINIMUM_WINDOW
    private var bandWindow = MINIMUM_WINDOW
    private var pickerWindow = MINIMUM_WINDOW

    val isOpen: Boolean get() = shown != null

    fun ask(question: Overlay) {
        shown = question
    }

    /** Every question put away, the trail with them. */
    fun close() {
        shown = null
        trail.clear()
    }

    // -- drawing -------------------------------------------------------------------------------------

    fun lines(width: Int, room: Int): List<Line> = when (val question = shown) {
        null -> emptyList()
        is Prompt -> promptLines(question, width)
        is Picker -> pickerLines(question, width, room)
        is Reader -> readerLines(question, width, room)
        is Band -> bandLines(question, width, room)
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
            listOfNotNull(Line.BLANK.takeIf { option.startsGroup && picker.filter.isEmpty() }) + (
                Line(if (here) "${Glyph.FOCUS} " else "  ", Palette.focused) +
                    Line(" ".repeat(marking).let { if (option.mark.isEmpty()) it else "${option.mark} " }, option.tone ?: Palette.faint) +
                    Line(cell(option.label, widths[0]), tone) +
                    Line(if (picker.isMarked(option)) " ${Glyph.TICK} " else "   ", Palette.settled) +
                    (option.gauge ?: Line.BLANK) +
                    Line(cell(option.note, widths[1]), Palette.faint)
                )
        }
        val focusedAt = listed.take(picker.index + 1).sumOf { it.size } - 1
        val body = listed.flatten().ifEmpty { listOf(Line("  nothing matches", Palette.warned)) }
        pickerWindow = (room - head.size).coerceAtLeast(1)
        return (head + scrolled(body, focusedAt.coerceAtLeast(0), pickerWindow)).map { it.sized(width) }
    }

    private fun readerLines(reader: Reader, width: Int, room: Int): List<Line> {
        val head = listOf(Line(reader.title, Palette.heading), Line.BLANK)
        readerWindow = (room - head.size).coerceAtLeast(1)
        val whole = reader.lines.flatMap { it.wrapped(width, READER_HANGING) }
        reader.rows = whole.size
        return (head + whole.drop(reader.offset).take(readerWindow)).map { it.sized(width) }
    }

    /** What the keys do in the question open, as the first of the screen's two key lines. */
    fun doingKeys(): Line = when (val question = shown) {
        null -> Line.BLANK
        is Prompt -> hints("enter" to "accept", "" to question.says.orEmpty())
        is Picker -> when {
            question.marking -> hints("enter" to "mark", "→" to if (question.marked.isEmpty()) "take this one" else "take the ${question.marked.size} marked")
            question.onStep != null -> hints("- =" to "step it", "enter" to "pick", if (question.onClear == null) "" to "" else "^d" to "clear")
            else -> hints("enter" to "pick", "→" to "pick", if (question.onClear == null) "" to "" else "^d" to "clear")
        }
        is Reader -> hints("" to "nothing to change here")
        is Band -> when {
            question.onAPreset -> hints("enter" to "take it")
            question.row == Band.Row.BAND -> hints(
                "- =" to "move the ${if (question.onTheHighEnd) "top" else "bottom"}",
                "tab" to "the other end",
                "_ +" to "drop the bottom or top",
                "enter" to "take it",
            )
            else -> hints("- =" to "step it", "enter" to "take it")
        }
    }

    /** How to get around the question open, as the second key line. */
    fun gettingAround(): Line = when (val question = shown) {
        null -> Line.BLANK
        is Prompt -> hints("esc" to "cancel")
        is Band -> hints("↑↓" to "move", "←" to "back", searching(question.filter))
        else -> hints("↑↓" to "move", "pgup/pgdn" to "a page", "home/end" to "ends", "←" to "back")
    }

    // -- keys ----------------------------------------------------------------------------------------

    /** [key] answered by the question open; a question asked over another goes back to it rather than to the screen. */
    fun handle(key: KeyboardEvent): Answered {
        val question = shown ?: return Answered.STILL_ASKING
        val goingBack = if (question is Prompt) key.key == "Escape" else key.key == "Escape" || key.key == "ArrowLeft"
        if (goingBack && trail.isNotEmpty()) {
            shown = trail.removeLast()
            return Answered.STILL_ASKING
        }
        val answered = dispatch(question, key)
        val now = shown
        when {
            now == null -> trail.clear()
            now === question || now.title == question.title -> Unit
            else -> trail.addLast(question)
        }
        return answered
    }

    private fun dispatch(question: Overlay, key: KeyboardEvent): Answered {
        val typed = key.key.length == 1 && !key.ctrl && !key.alt
        when (question) {
            is Prompt -> when {
                key.key == "Escape" -> shown = null
                key.key == "Enter" -> if (question.says == null) {
                    shown = null
                    question.onDone(question.typed)
                }
                key.key == "Backspace" -> question.backspace()
                typed -> question.type(key.key)
            }
            is Picker -> when {
                key.key == "Escape" || key.key == "ArrowLeft" -> shown = null
                key.ctrl && key.key == "d" -> {
                    shown = null
                    question.onClear?.invoke()
                }
                // Enter marks where several may be taken, leaving the list standing; right takes the marked.
                key.key == "Enter" -> question.focused?.let { picked ->
                    if (question.marking) question.mark(picked.value) else {
                        shown = null
                        question.onPick(picked)
                    }
                }
                key.key == "ArrowRight" -> question.focused?.let { picked ->
                    shown = null
                    val takingTheMarked = question.onOnly
                    if (takingTheMarked == null) question.onPick(picked) else takingTheMarked(picked, question.marked.ifEmpty { setOf(picked.value) })
                }
                question.onStep != null && (key.key == "=" || key.key == "-") -> question.focused?.let { focused ->
                    question.onStep.invoke(focused, if (key.key == "=") A_STEP else -A_STEP)
                }
                key.key == "ArrowUp" -> question.move(-1)
                key.key == "ArrowDown" -> question.move(1)
                key.key == "PageUp" -> question.page(-pickerWindow)
                key.key == "PageDown" -> question.page(pickerWindow)
                key.key == "Home" -> question.page(-question.shown.size)
                key.key == "End" -> question.page(question.shown.size)
                key.key == "Backspace" -> question.backspace()
                typed -> question.type(key.key)
            }
            is Band -> when {
                key.key == "Escape" || key.key == "ArrowLeft" -> shown = null
                key.key == "Enter" -> {
                    val said = question.spelled
                    shown = null
                    if (said.isNotEmpty()) question.onDone(said)
                }
                key.key == "ArrowUp" -> question.move(-1)
                key.key == "ArrowDown" -> question.move(1)
                key.key == "Tab" -> question.turn()
                key.key == "=" -> question.step(A_STEP)
                key.key == "-" -> question.step(-A_STEP)
                key.key == "+" -> question.dropTheEnd(high = true)
                key.key == "_" -> question.dropTheEnd(high = false)
                key.key == "Backspace" -> question.backspace()
                typed -> question.type(key.key)
            }
            is Reader -> when {
                key.ctrl && key.key == "q" -> {
                    shown = null
                    return Answered.QUIT
                }
                key.key == "Escape" || key.key == "Enter" || key.key == "ArrowLeft" || key.key == "q" -> shown = null
                key.key == "ArrowUp" -> question.scroll(-1, readerWindow)
                key.key == "ArrowDown" -> question.scroll(1, readerWindow)
                key.key == "PageUp" -> question.scroll(-readerWindow, readerWindow)
                key.key == "PageDown" -> question.scroll(readerWindow, readerWindow)
                key.key == "Home" -> question.scroll(-question.lines.size, readerWindow)
                key.key == "End" -> question.scroll(question.lines.size, readerWindow)
            }
        }
        return Answered.STILL_ASKING
    }

    private fun scrolled(lines: List<Line>, at: Int, room: Int): List<Line> {
        if (room <= 0) return emptyList()
        if (lines.size <= room) return lines
        val first = (at - room / 2).coerceIn(0, lines.size - room)
        return lines.subList(first, first + room)
    }

    companion object {
        /** How far `-` and `=` move a chance, a weight, a bar or an end of a band. */
        const val A_STEP = 0.1

        private const val MINIMUM_WINDOW = 8
        private const val CURSOR_ROOM = 2
        private const val TICK_ROOM = 3
        private const val COLUMN_GAP = 2
        private const val MINIMUM_ROOM = 10
        private const val READER_HANGING = "      "
        private val PICKER_COLUMNS = listOf(Columns.Column(), Columns.Column(grows = true))
        private val BAND_COLUMNS = listOf(Columns.Column(), Columns.Column(), Columns.Column(grows = true))
    }
}
