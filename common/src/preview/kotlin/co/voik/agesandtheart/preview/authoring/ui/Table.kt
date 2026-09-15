package co.voik.agesandtheart.preview.authoring.ui

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.rendering.TextStyle

/**
 * A list with columns, where some of the columns can be changed in place.
 *
 * The word lists were a name and a sentence of notes, so setting a rarity meant opening the word, setting
 * it, and coming back. Most of what anybody does on these screens is exactly that, so the columns hold it
 * instead: left and right move between them, and up and down cycle the value under the cursor.
 */
class Table(
    val title: String,
    val columns: List<Column>,
    rows: List<Row>,
    filter: String = "",
    index: Int = 0,
    column: Int = 0,
    /**
     * What to say when there is nothing in the table **at all**, as against nothing matching what was
     * typed. They are different situations and one message for both is how a screen with no rows yet ends
     * up reading as a search that failed — with no hint that the thing to do is make one.
     */
    val whenEmpty: String = "nothing here yet",
    /**
     * What to say when the *filter* leaves nothing, given what was typed.
     *
     * A lambda because a search that finds nothing is sometimes the start of something — a word list can
     * offer to write what was typed, where a list of the game's own biomes has nothing to offer at all.
     */
    val whenNothingMatches: (String) -> String = { "nothing matches '$it'" },
) {
    /**
     * [kind] says what the column holds, so the caller can act on it without counting positions — adding
     * a column in the middle would otherwise move every hotkey.
     */
    data class Column(
        val title: String,
        /**
         * The least it may be drawn at. **A floor, not a size** — how wide the column really is comes from
         * what is in it ([Columns.widths]), so a value nobody guessed the length of is not cut and a column
         * of short values does not leave a gutter.
         */
        val least: Int,
        val kind: String = "",
        /**
         * The order this column's values really run in, where that is not alphabetical.
         *
         * Rarity, ink and specificity are scales, so sorting them by their spelling puts common between
         * rare and uncommon and tells you nothing. Empty means the text sorts as text, which is right for
         * a name.
         */
        val order: List<String> = emptyList(),
        /** Whether it takes the room the other columns did not want — the notes column of most tables. */
        val grows: Boolean = false,
    ) {
        val cycles: Boolean get() = kind.isNotEmpty()
    }

    /** [key] is what the caller gets back; [cells] line up with [columns]. */
    data class Row(
        val key: String,
        val cells: List<String>,
        val mark: String = "",
        val tone: TextStyle? = null,
        val note: String = "",
    )

    var rows: List<Row> = rows
        private set

    var filter: String = filter
        private set

    var index: Int = index
        private set

    var column: Int = column
        private set

    /** Which column the rows are ordered by, and which way. Null is the order the caller supplied. */
    var sortedBy: Int? = null
        private set

    var descending: Boolean = false
        private set

    /** How many rows the last frame had room for — what a page means, set by whatever drew it. */
    var window: Int = 1

    /**
     * How many columns wide the last frame drew it, set by whatever drew it — the room [widths] divides.
     *
     * A table may be the whole canvas or one pane of three, and it is the drawer that knows which.
     */
    var room: Int = MINIMUM_ROOM

    /**
     * What each column is drawn at, measured from every row the filter left rather than from the page in
     * view — a column that resized as you scrolled would be a table with no columns at all.
     */
    val widths: List<Int>
        get() = Columns.widths(
            (listOf(columns.map { it.title }) + shown.map { it.cells }),
            columns.map { Columns.Column(it.least, it.grows) },
            room,
        )

    /**
     * Rows the filter leaves, then ordered.
     *
     * **Matched on every column**, not on the name: the question a corpus audit keeps needing is "which
     * words set `colour`" or "which are rare", and both answers are sitting in the table already.
     */
    val shown: List<Row>
        get() {
            val kept = rows.filter { row -> row.cells.any { it.contains(filter, ignoreCase = true) } }
            val by = sortedBy ?: return kept
            val scale = columns.getOrNull(by)?.order.orEmpty()
            fun rank(row: Row): Int {
                val value = row.cells.getOrElse(by) { "" }
                return scale.indexOf(value).takeIf { it >= 0 } ?: scale.size
            }
            fun said(row: Row) = row.cells.getOrElse(by) { "" }
            // **A column of numbers sorts as numbers.** Read off the column rather than declared, so a
            // count added anywhere gets it without somebody remembering to say so — and `10` stops
            // sorting before `2`, which is what sorting by `members` did.
            val counting = scale.isEmpty() &&
                kept.all { said(it).isEmpty() || said(it).toDoubleOrNull() != null } &&
                kept.any { said(it).toDoubleOrNull() != null }
            // Blank last either way: an unset rarity is not "before a" and sorting it there would bury
            // every word that still needs one at the top of the list you were trying to read.
            val ordered = kept.sortedWith(
                compareBy<Row> { said(it).isEmpty() }
                    .thenBy { if (scale.isEmpty()) 0 else rank(it) }
                    .thenBy { if (counting) said(it).toDoubleOrNull() ?: 0.0 else 0.0 }
                    .thenBy { if (scale.isEmpty() && !counting) said(it) else "" }
                    .thenBy { it.cells.firstOrNull().orEmpty() },
            )
            return if (descending) ordered.reversed() else ordered
        }

    val focused: Row? get() = shown.getOrNull(index)

    /**
     * **How wide it would like to be** — every column at its widest entry, with the gaps between them.
     *
     * For a pane laid beside others rather than given the screen: a column told to grow takes whatever
     * room it is handed, so a table with room to spare fills it and the pane next to it gets nothing.
     * Asked this, the drawer can hand it what it needs and spend the rest elsewhere.
     */
    val wanted: Int
        get() = Columns.widths(
            listOf(columns.map { it.title }) + shown.map { it.cells },
            columns.map { Columns.Column(it.least) },
            room = ROOMY,
            gap = Columns.GAP,
        ).sum() + Columns.GAP * (columns.size - 1).coerceAtLeast(0)

    fun move(by: Int) {
        val size = shown.size
        if (size == 0) return
        index = ((index + by) % size + size) % size
    }

    fun across(by: Int) {
        column = (column + by).coerceIn(0, columns.lastIndex)
    }

    /**
     * The cursor a column over, or **false where that would run off the end.**
     *
     * How a table drawn as one pane of several hands left and right on: its own columns first, and the
     * pane beside it once they are spent. A screen of one table never sees the false.
     */
    fun acrossOrOff(by: Int): Boolean {
        val next = column + by
        if (next !in columns.indices) return false
        column = next
        return true
    }

    /** The cursor put on a column outright — the near edge, for a pane just stepped into. */
    fun toColumn(at: Int) {
        column = at.coerceIn(0, columns.lastIndex)
    }

    /**
     * Ordered by whichever column the cursor is on — **and the cursor stays on the row it was on**, which
     * is the whole point of sorting from where you are rather than from the top.
     *
     * Asking again reverses it, which is what every table does and what everybody tries second.
     */
    fun sortByTheColumnInHand() {
        val standing = focused?.key
        if (sortedBy == column) descending = !descending else { sortedBy = column; descending = false }
        index = shown.indexOfFirst { it.key == standing }.coerceAtLeast(0)
    }

    fun home() { index = 0 }

    fun end() { index = (shown.size - 1).coerceAtLeast(0) }

    /** A page on, or to the far end where the whole table already fits on one. */
    fun page(by: Int) {
        if (shown.size <= window) {
            if (by > 0) end() else home()
            return
        }
        index = (index + by * window).coerceIn(0, shown.size - 1)
    }

    fun type(character: String) {
        filter += character
        index = 0
    }

    fun backspace() {
        filter = filter.dropLast(1)
        index = 0
    }

    /** Whether anything has been typed to narrow the list — what escape gives back before it leaves. */
    val isFiltered: Boolean get() = filter.isNotEmpty()

    fun clearFilter() {
        filter = ""
        index = 0
    }

    /** The rows rebuilt after an edit, keeping the cursor and the filter where they were. */
    fun withRows(later: List<Row>) {
        rows = later
        index = index.coerceAtMost((shown.size - 1).coerceAtLeast(0))
    }
}

/** One value on from [standing], wrapping — what up and down do on a cycling column. */
fun cycled(among: List<String?>, standing: String?, by: Int): String? {
    if (among.isEmpty()) return standing
    val at = among.indexOf(standing).takeIf { it >= 0 } ?: 0
    return among[((at + by) % among.size + among.size) % among.size]
}

/**
 * [text] fitted to [width] exactly — padded, or cut with an ellipsis so a long value cannot shove a column.
 *
 * **Where [found] falls outside the cut, the view slides to it.** A row is on the list because something
 * matched, and a match scrolled off the end of its column is a row with no visible reason to be there.
 */
fun cell(text: String, width: Int, found: String = ""): String {
    if (width <= 0) return ""
    if (text.length <= width) return text.padEnd(width)
    if (width == 1) return Glyph.ELIDED
    val hit = if (found.isEmpty()) -1 else text.indexOf(found, ignoreCase = true)
    if (hit < 0 || hit + found.length <= width - 1) return text.take(width - 1) + Glyph.ELIDED
    // Enough room after the leading ellipsis for the match and a little of what follows it.
    val from = (hit - 1).coerceAtMost(text.length - (width - 1))
    return Glyph.ELIDED + text.substring(from).take(width - 1)
}

/**
 * A table drawn — title, headers, the rows that fit, and whatever key hints the screen offers.
 *
 * Shared rather than written per screen: the word lists, the auto-generated lists and the tag layer are
 * the same object with different columns, and a second copy of this is a second place for the cursor
 * arithmetic to be subtly wrong.
 */
fun tableLines(table: Table, canvas: Canvas, hints: List<Line>): List<Line> = buildList {
    table.room = (canvas.width - CURSOR_COLUMN).coerceAtLeast(MINIMUM_ROOM)
    add(Line("  ${table.title}", Palette.heading))
    add(Line.BLANK)
    val room = (canvas.height - CHROME_AROUND_A_TABLE - hints.size).coerceAtLeast(1)
    val body = paneLines(table, room + 1, focused = true)
    add(body.first())
    add(Frame.rule(canvas.width))
    addAll(body.drop(1))
    add(Frame.rule(canvas.width))
    addAll(hints)
}

/**
 * A table drawn as **one pane of a screen** — its header and as many rows as [rows] lines will hold.
 *
 * The same body [tableLines] draws, so a screen of three tables scrolls, pages and sorts exactly like a
 * screen of one. The window arithmetic in particular is the part that is subtly wrong in a second copy,
 * and a pane needs it as much as a page does.
 *
 * [focused] is whether the cursor is in *this* pane: an unfocused one shows neither its row marker nor
 * its chosen column, so which list the keys are going to is never in doubt.
 */
fun paneLines(table: Table, rows: Int, focused: Boolean): List<Line> = buildList {
    val room = (rows - 1).coerceAtLeast(1)
    table.window = room
    add(headerLine(table, focused))
    val shown = table.shown
    val first = (table.index - room / 2).coerceIn(0, (shown.size - room).coerceAtLeast(0))
    for ((offset, row) in shown.drop(first).take(room).withIndex()) {
        add(rowLine(table, row, here = focused && first + offset == table.index))
    }
    if (shown.isEmpty()) {
        add(
            if (table.rows.isEmpty()) {
                Line("    ${table.whenEmpty}", Palette.faint)
            } else {
                Line("    ${table.whenNothingMatches(table.filter)}", Palette.warned)
            },
        )
    }
}

/**
 * The keys a table answers the same way wherever it is drawn: the cursor, the page, the sort, the filter.
 *
 * Says whether it took the key, so the screen around it handles only what is its own. **Left and right
 * are deliberately not here** — on a screen of one table they walk the columns and on a screen of several
 * they have to reach the pane beside it, and which of those it is is the screen's to know.
 */
fun Table.tookTheKey(key: KeyboardEvent): Boolean {
    when {
        key.ctrl || key.alt -> return false
        key.key == "ArrowUp" -> move(-1)
        key.key == "ArrowDown" -> move(1)
        key.key == "Home" -> home()
        key.key == "End" -> end()
        key.key == "PageUp" -> page(-1)
        key.key == "PageDown" -> page(1)
        key.key == "Tab" -> sortByTheColumnInHand()
        key.key == "Backspace" -> backspace()
        key.key.length == 1 -> type(key.key)
        else -> return false
    }
    return true
}

/** Title, blank, header, two rules, and a line the terminal keeps for itself. */
private const val CHROME_AROUND_A_TABLE = 7

/** The `  ▸ ` a focused row wears, which every row is indented by so none of them moves. */
const val CURSOR_COLUMN = 4

/** Narrow enough that nothing is readable, and the point below which arithmetic stops meaning anything. */
const val MINIMUM_ROOM = 8

/** Wider than any terminal, for asking a table what it would like rather than what it has. */
private const val ROOMY = 10_000

/**
 * What stands between two columns of a table.
 *
 * A function rather than a top-level value on purpose: a styled constant here would run [Palette] while
 * this file's class was still loading, and `cell` is pure string arithmetic that ought to be usable —
 * and checkable — without a terminal library on the classpath at all.
 */
private fun columnRule() = Line(" ${Glyph.BAR} ", Palette.rule)

fun headerLine(table: Table, focused: Boolean = true): Line {
    val titles = table.columns.mapIndexed { at, column ->
        val sorted = table.sortedBy == at
        val title = if (sorted) "${column.title} ${if (table.descending) "↓" else "↑"}" else column.title
        Ink(title, if (focused && at == table.column) Palette.chosen else Palette.faint)
    }
    return Line("    ") + Columns.laid(titles, table.widths, columnRule())
}

fun rowLine(table: Table, row: Table.Row, here: Boolean): Line {
    val cells = table.columns.mapIndexed { at, column ->
        val underCursor = here && at == table.column
        val tone = when {
            underCursor && column.cycles -> Palette.chosen
            at == 0 -> row.tone ?: if (here) Palette.value else Palette.faint
            else -> row.tone ?: Palette.faint
        }
        Ink(row.cells.getOrElse(at) { "" }, tone)
    }
    return Line(if (here) "  ${Glyph.FOCUS} " else "    ", Palette.focused) +
        Columns.laid(cells, table.widths, columnRule(), found = table.filter)
}
