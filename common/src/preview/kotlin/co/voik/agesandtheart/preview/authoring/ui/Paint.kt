package co.voik.agesandtheart.preview.authoring.ui

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles

/** A run of text and how it is coloured. Plain text and its styling stay apart until the last moment. */
data class Ink(val text: String, val style: TextStyle? = null)

/**
 * One row of the screen, as text that knows its own **display** width.
 *
 * Styling is applied last on purpose: an ANSI escape has a length and no width, so a styled string cannot
 * be padded or clipped correctly. Everything here measures the plain text and colours it on the way out.
 */
class Line(val inks: List<Ink>) {

    constructor(text: String, style: TextStyle? = null) : this(listOf(Ink(text, style)))

    val width: Int get() = inks.sumOf { it.text.length }

    operator fun plus(other: Line) = Line(inks + other.inks)

    /** This line at exactly [to] columns — padded with spaces, or cut with an ellipsis where it overruns. */
    /**
     * This line broken to [width], each continuation indented by [hanging].
     *
     * **A reading is prose, and prose that is cut is not shorter, it is wrong.** A composition spelled out
     * runs to a couple of hundred characters as a matter of course — that is what a world made of nine
     * aspects reads like — and truncating it hid the half that said what the Age actually was.
     *
     * Broken on spaces, and the colour carries across the break: a span split in the middle keeps its
     * style on both sides, so a tag or a parameter name that lands on a boundary still reads as one.
     */
    fun wrapped(width: Int, hanging: String = ""): List<Line> {
        if (width <= 0 || this.width <= width) return listOf(this)
        val lines = mutableListOf<Line>()
        var current = mutableListOf<Ink>()
        var used = 0

        fun breakHere() {
            lines += Line(current.toList())
            current = mutableListOf()
            used = 0
            if (hanging.isNotEmpty() && hanging.length < width) {
                current += Ink(hanging)
                used = hanging.length
            }
        }

        for (ink in inks) {
            var text = ink.text
            while (used + text.length > width) {
                val room = width - used
                // No room left on this line at all: break first, and try the same text again.
                if (room <= 1) {
                    if (current.isEmpty()) break
                    breakHere()
                    continue
                }
                // On a space where there is one within reach, and mid-word only where there is not.
                val cut = text.lastIndexOf(' ', room - 1).takeIf { it > 0 } ?: room
                current += Ink(text.take(cut), ink.style)
                text = text.drop(cut).trimStart()
                breakHere()
            }
            if (text.isNotEmpty()) {
                current += Ink(text, ink.style)
                used += text.length
            }
        }
        if (current.isNotEmpty()) lines += Line(current.toList())
        return lines
    }

    fun sized(to: Int): Line = when {
        width == to -> this
        width < to -> Line(inks + Ink(" ".repeat(to - width)))
        else -> clipped(to)
    }

    private fun clipped(to: Int): Line {
        if (to <= 0) return Line(emptyList())
        val kept = mutableListOf<Ink>()
        var left = to - 1
        for (ink in inks) {
            if (left <= 0) break
            val taken = ink.text.take(left)
            kept += ink.copy(text = taken)
            left -= taken.length
        }
        return Line(kept + Ink(Glyph.ELIDED, Palette.faint))
    }

    /**
     * The line as the terminal should receive it.
     *
     * [colourful] is asked rather than assumed: `TextStyle.invoke` writes its escape whatever the terminal
     * turned out to be, so a run piped to a file would otherwise carry codes nothing will read. The styles
     * here are the sixteen basic ANSI colours, which every colour terminal has, so there is no level to
     * negotiate beyond having one at all.
     */
    /**
     * This line with its trailing padding dropped.
     *
     * **A line filled to the last column wraps.** The terminal moves to the next row on its own, and the
     * newline after it then moves again — so a frame padded to the full width came out double spaced.
     * Padding is for laying panes beside one another; by the time a line is printed it has done its job.
     */
    fun trimmed(): Line {
        val kept = inks.toMutableList()
        while (kept.isNotEmpty() && kept.last().text.isBlank()) kept.removeAt(kept.lastIndex)
        if (kept.isEmpty()) return Line(emptyList())
        return Line(kept.dropLast(1) + kept.last().let { it.copy(text = it.text.trimEnd()) })
    }

    fun rendered(colourful: Boolean): String = inks.joinToString("") { ink ->
        if (colourful) ink.style?.invoke(ink.text) ?: ink.text else ink.text
    }

    companion object {
        val BLANK = Line(emptyList())

        fun of(vararg inks: Ink) = Line(inks.toList())
    }
}

/**
 * The tool's colours, named for what they mean rather than for what they are — so a re-theme is one file
 * and a reader of the editor never has to remember that yellow meant "advisory".
 */
object Palette {
    val heading: TextStyle = TextStyles.bold.style + TextColors.brightWhite
    val chosen: TextStyle = TextColors.brightCyan
    val focused: TextStyle = TextStyles.bold.style + TextColors.brightCyan
    val faint: TextStyle = TextColors.gray
    val value: TextStyle = TextColors.brightWhite
    val parameter: TextStyle = TextColors.cyan
    val tag: TextStyle = TextColors.magenta

    /**
     * A part of the world, wherever one is named.
     *
     * Beside [tag] on purpose: an aspect and a tag are both names out of the world model rather than
     * values a writer typed, and reading as a family is what tells them from the text around them.
     */
    val aspect: TextStyle = TextColors.brightMagenta
    val refused: TextStyle = TextColors.brightRed
    val warned: TextStyle = TextColors.yellow
    val nudged: TextStyle = TextColors.brightBlue
    val noted: TextStyle = TextColors.gray
    val settled: TextStyle = TextColors.green
    val rule: TextStyle = TextColors.gray

    /** A key you can press, wherever one is named. */
    val key: TextStyle = TextColors.brightCyan

    /**
     * What a match is written in.
     *
     * **Inverse was unreadable.** Swapping the ink and the ground put dark letters on a light block in
     * the middle of a dim row, which is the one thing on screen you have just asked to read. A dark
     * ground with the cell's own colour brightened in front of it stands out without fighting.
     */
    fun marking(base: TextStyle?): TextStyle {
        val alreadyCyan = base == chosen || base == focused || base == parameter
        val ink = if (alreadyCyan) TextColors.brightCyan else TextColors.brightWhite
        return TextStyle(color = ink.color, bgColor = TextColors.gray.color)
    }
}

/** The few box and marker characters the editor draws with, so none of them is a bare literal in a layout. */
object Glyph {
    const val ELIDED = "…"
    const val FOCUS = "▸"
    const val BULLET = "•"
    const val FILLED = "●"
    const val HOLLOW = "○"
    const val TICK = "✓"
    const val CROSS = "✗"
    const val WARN = "!"
    const val RULE = "─"
    const val BAR = "│"
    const val FULL = "█"
    const val EMPTY = "░"
}

/**
 * [text] as inks, with anything marked as a tag coloured.
 *
 * A cell is one string so it can be sorted and filtered, but a tag inside one should read as a tag — so
 * the marking is a display rule over the text rather than a second model to keep in step.
 */
fun painted(text: String, base: TextStyle?): List<Ink> =
    Regex("(\\S+|\\s+)").findAll(text).map { run ->
        val word = run.value
        if (word.startsWith(TAG_MARK)) Ink(word, Palette.tag) else Ink(word, base)
    }.toList()

/**
 * [text] as inks, with every occurrence of [found] marked and tags coloured.
 *
 * **A list that matches on any column has to say which one matched.** Filtering used to read the name
 * alone, so a row was on screen for a reason you could see; drawing from the rarity, the ink and the
 * effects as well means a row can be there for a reason three columns away.
 */
fun highlighted(text: String, base: TextStyle?, found: String): List<Ink> {
    if (found.isEmpty()) return painted(text, base)
    return buildList {
        var at = 0
        while (at <= text.length - found.length) {
            val hit = text.indexOf(found, at, ignoreCase = true)
            if (hit < 0) break
            addAll(painted(text.substring(at, hit), base))
            add(Ink(text.substring(hit, hit + found.length), Palette.marking(base)))
            at = hit + found.length
        }
        addAll(painted(text.substring(at), base))
    }
}

/**
 * **The hint a list that filters wears**, and what has been typed into it so far.
 *
 * One thing rather than a label and a value on two pairs, so a screen cannot show the first and forget
 * the second — which four of them did, leaving a list that quietly narrowed under you with nothing on
 * screen to say why.
 */
fun searching(filter: String): Pair<String, String> =
    "" to if (filter.isEmpty()) "type to search" else "type to search: $filter"

/**
 * The row of key hints along the bottom of a screen — `[a] new book  •  [enter] write it`.
 *
 * **Bracketed and coloured, because a bare key does not read as one.** "a new book" is a key and a noun
 * phrase spelled identically to an article and a noun, and a reader has no way to tell which until they
 * try it. The brackets say it is a key and the colour says it in the corner of the eye.
 *
 * A pair with an empty key is plain text in the same row, for the things that are not keys.
 */
fun hints(vararg said: Pair<String, String>): Line {
    var line = Line("  ")
    var written = false
    for ((key, does) in said) {
        if (key.isEmpty() && does.isEmpty()) continue
        if (written) line += Line("  ${Glyph.BULLET} ", Palette.rule)
        if (key.isNotEmpty()) line += Line("[$key]", Palette.key)
        if (does.isNotEmpty()) line += Line(if (key.isEmpty()) does else " $does", Palette.faint)
        written = true
    }
    return line
}

/**
 * What `^C` throws — **it ends the program, from wherever it was pressed.**
 *
 * Raw mode takes the terminal's own interrupt away: `^C` arrives as an ordinary keystroke and every
 * screen was reading it as "go back one", so a person holding the habit of the last forty years had to
 * press it once per screen and got a menu instead of their shell. Thrown rather than returned so it
 * unwinds through every loop at once, and every `finally` on the way — the server's teardown included —
 * still runs.
 */
class Leaving : RuntimeException("^C")

/** What marks a tag wherever one is shown. */
const val TAG_MARK = "#"

/** Panes laid out side by side, each already sized, so the caller never counts columns twice. */
object Frame {

    const val GUTTER = 3

    fun beside(left: List<Line>, leftWidth: Int, right: List<Line>, rightWidth: Int): List<Line> {
        val rows = maxOf(left.size, right.size)
        return (0..<rows).map { row ->
            val here = (left.getOrNull(row) ?: Line.BLANK).sized(leftWidth)
            val there = (right.getOrNull(row) ?: Line.BLANK).sized(rightWidth)
            here + Line(" ${Glyph.BAR} ", Palette.rule) + there
        }
    }

    fun rule(width: Int, label: String? = null): Line {
        if (label == null) return Line(Glyph.RULE.repeat(width), Palette.rule)
        val head = Line("${Glyph.RULE.repeat(2)} ", Palette.rule) + Line(label, Palette.faint) + Line(" ")
        return head + Line(Glyph.RULE.repeat((width - head.width).coerceAtLeast(0)), Palette.rule)
    }
}
