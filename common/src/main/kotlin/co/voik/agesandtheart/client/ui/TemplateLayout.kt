package co.voik.agesandtheart.client.ui

import net.minecraft.network.chat.Component

/**
 * A span of the text that means something, and how to show it.
 *
 * [start] and [end] are character offsets, end exclusive. [script] is drawn on the line beneath the span,
 * or, where [scriptUnknown], a placeholder as wide as the span. [colour] replaces the text's own,
 * [underline] runs under it, and [tooltip] answers a hover.
 */
data class TextMark(
    val start: Int,
    val end: Int,
    val script: Component? = null,
    val scriptUnknown: Boolean = false,
    val colour: Int? = null,
    val underline: Int? = null,
    val tooltip: Component? = null,
)

/** [marks] made against [text], which may no longer be the text in the box. */
data class MarkedText(val text: String, val marks: List<TextMark>)

/** One display row: the characters from [begin] until [end]. A break the row was cut at belongs to neither. */
data class DisplayRow(val begin: Int, val end: Int)

/**
 * [text] broken into rows no wider than [limit], where [advance] is how far character `i` moves the pen —
 * padding included, so a word widened for its script wraps as the wider word.
 *
 * Hard breaks at `\n`; soft ones at the last space that fits, which the break consumes; inside a word only
 * when the word alone is too wide for a row.
 */
fun wrapRows(text: String, limit: Int, advance: (Int) -> Int): List<DisplayRow> {
    val rows = mutableListOf<DisplayRow>()
    var lineStart = 0
    while (true) {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) text.length else newline
        rows += wrapLine(text, lineStart, lineEnd, limit, advance)
        if (newline < 0) return rows
        lineStart = newline + 1
    }
}

private fun wrapLine(text: String, lineStart: Int, lineEnd: Int, limit: Int, advance: (Int) -> Int): List<DisplayRow> {
    val rows = mutableListOf<DisplayRow>()
    var rowBegin = lineStart
    var lastSpace = -1
    var pen = 0
    var at = lineStart
    while (at < lineEnd) {
        if (text[at] == ' ') lastSpace = at
        val overruns = pen + advance(at) > limit && at > rowBegin
        if (!overruns) {
            pen += advance(at)
            at++
            continue
        }
        val canBreakAtASpace = lastSpace >= rowBegin
        if (canBreakAtASpace) {
            rows += DisplayRow(rowBegin, lastSpace)
            rowBegin = lastSpace + 1
        } else {
            rows += DisplayRow(rowBegin, at)
            rowBegin = at
        }
        at = rowBegin
        pen = 0
        lastSpace = -1
    }
    rows += DisplayRow(rowBegin, lineEnd)
    return rows
}

/**
 * [marks] made against [before], moved to where they fall in [after].
 *
 * A mark clear of the edit keeps its word, shifted if the edit came before it. One the edit touched is
 * dropped — including one it ran into, so typing onto the end of a word takes that word's reading away
 * until the desk has read it again.
 */
fun carriedThrough(marks: List<TextMark>, before: String, after: String): List<TextMark> {
    if (before == after) return marks
    val shortest = minOf(before.length, after.length)
    val prefix = (0 until shortest).firstOrNull { before[it] != after[it] } ?: shortest
    val suffix = (0 until shortest - prefix)
        .firstOrNull { before[before.length - 1 - it] != after[after.length - 1 - it] }
        ?: (shortest - prefix)
    val editEnd = before.length - suffix
    val shift = after.length - before.length

    fun separatedAt(index: Int) = after.getOrNull(index)?.isWhitespace() ?: true
    fun endsBeforeTheEdit(mark: TextMark) = mark.end < prefix || (mark.end == prefix && separatedAt(prefix))
    fun startsAfterTheEdit(mark: TextMark) =
        mark.start > editEnd || (mark.start == editEnd && separatedAt(editEnd + shift - 1))

    return marks.mapNotNull { mark ->
        when {
            endsBeforeTheEdit(mark) -> mark
            startsAfterTheEdit(mark) -> mark.copy(start = mark.start + shift, end = mark.end + shift)
            else -> null
        }
    }
}
