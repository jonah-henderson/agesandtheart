package co.voik.agesandtheart.preview.authoring.ui

import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal

/**
 * The blocking dialogs the list screens share: a line typed, a deletion confirmed, and a page read.
 *
 * Each holds raw mode only while it is open. The word editor keeps a frame up around its questions, so it
 * draws them as overlays instead (`Overlays.kt`).
 */
object Dialogs {

    /**
     * One line typed, with a live [complaint] — null where it was left alone.
     *
     * [aside] is a line under what is typed, saying something about it — what it will be written down as,
     * say. The line is there only where [aside] is given, and blank while [complaint] objects.
     */
    fun ask(
        terminal: Terminal,
        canvas: Canvas,
        title: String,
        hint: String,
        standing: String = "",
        aside: ((String) -> String)? = null,
        complaint: (String) -> String?,
    ): String? {
        var typed = standing
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                val wrong = complaint(typed)
                val asideLine = aside?.let { says -> Line(if (wrong == null) "  ${says(typed)}" else "", Palette.faint) }
                canvas.show(
                    listOfNotNull(
                        Line("  $title", Palette.heading),
                        Line("  $hint", Palette.faint),
                        Line.BLANK,
                        Line("  ${Glyph.FOCUS} ", Palette.focused) + Line(typed, Palette.value) +
                            Line(TYPING_CURSOR, Palette.faint),
                        Line.BLANK,
                        asideLine,
                        Line("  ${wrong.orEmpty()}", Palette.refused),
                        Frame.rule(canvas.width),
                        hints("enter" to "accept", "escape" to "leave it alone"),
                    ),
                )
                val key = scope.readKey() ?: return null
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && key.key == "q" -> return null
                    key.key == "Escape" -> return null
                    key.key == "Enter" -> if (wrong == null) return typed
                    key.key == "Backspace" -> typed = typed.dropLast(1)
                    key.key.length == 1 && !key.ctrl && !key.alt -> typed += key.key
                }
            }
        }
    }

    /**
     * Whether to delete something that cannot be undone, **defaulting to keeping it**: the cursor starts on
     * "keep it", and enter answers whichever row it is on.
     */
    fun confirmDeletion(terminal: Terminal, canvas: Canvas, title: String, says: List<Line>): Boolean {
        var deleting = false
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    listOf(Line("  ${Glyph.WARN} $title", Palette.refused), Line.BLANK) +
                        says.flatMap { (Line("  ") + it).wrapped(canvas.width) } +
                        listOf(
                            Line.BLANK,
                            Line("  ${if (deleting) Glyph.FOCUS else " "} ", Palette.focused) +
                                Line("delete it", if (deleting) Palette.refused else Palette.faint),
                            Line("  ${if (deleting) " " else Glyph.FOCUS} ", Palette.focused) +
                                Line("keep it", if (deleting) Palette.faint else Palette.value),
                            Line.BLANK,
                            Frame.rule(canvas.width),
                            hints("↑↓" to "move", "enter" to "do it", "escape" to "keep it"),
                        ),
                )
                val key = scope.readKey() ?: return false
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && key.key == "q" -> return false
                    key.key == "Escape" || key.key == "ArrowLeft" -> return false
                    key.key == "ArrowUp" || key.key == "ArrowDown" -> deleting = !deleting
                    key.key == "Enter" -> return deleting
                }
            }
        }
    }

    /** [reader] shown until it is put down, scrolling where it runs past the screen. */
    fun read(terminal: Terminal, canvas: Canvas, reader: Reader) {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                val window = (canvas.height - READER_CHROME).coerceAtLeast(1)
                canvas.show(readerLines(canvas, reader, window))
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && key.key == "q" -> return
                    key.key == "Escape" || key.key == "ArrowLeft" || key.key == "Enter" -> return
                    key.key == "ArrowUp" -> reader.scroll(-1, window)
                    key.key == "ArrowDown" -> reader.scroll(1, window)
                }
            }
        }
    }

    private fun readerLines(canvas: Canvas, reader: Reader, window: Int): List<Line> = buildList {
        add(Line("  ${reader.title}", Palette.heading))
        add(Line.BLANK)
        val whole = reader.lines.flatMap { (Line("  ") + it).wrapped(canvas.width, READER_HANGING) }
        reader.rows = whole.size
        addAll(whole.drop(reader.offset).take(window))
        add(Frame.rule(canvas.width))
        add(hints("↑↓" to "scroll", "←" to "back"))
    }

    private const val TYPING_CURSOR = "_"

    /** Where a wrapped line's continuation starts, so a break reads as one. */
    private const val READER_HANGING = "      "

    /** Title, blank, rule and the key line — what a page is not allowed to use. */
    private const val READER_CHROME = 4
}
