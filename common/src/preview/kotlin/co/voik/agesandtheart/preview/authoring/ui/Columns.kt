package co.voik.agesandtheart.preview.authoring.ui

/**
 * **Column widths measured from what is in them**, fitted to the room there is.
 *
 * The one place column arithmetic lives. Every list here used to declare a width per column and one of
 * them worked the last one out from the canvas by hand, so a value longer than somebody's guess was cut
 * on every terminal and a short one left a gutter nobody could use. A declared width is a guess about
 * content the screen is holding already.
 */
object Columns {

    /**
     * What one column asks for: the least it may be drawn at, and whether it takes room nobody else wanted.
     *
     * [least] is a floor rather than a size — a column of two-character counts is drawn two wide however
     * long its heading is, unless the heading is what has to stay readable.
     */
    data class Column(val least: Int = 0, val grows: Boolean = false)

    /**
     * How wide each column should be drawn, given every [cells] row it has to hold.
     *
     * A column wants to be as wide as its widest entry. Where they all fit, the spare goes to whichever
     * columns said they would take it; where they do not, **the widest are levelled down to a common
     * ceiling**, so a column of short values keeps all of them and a column of long ones gives up the
     * difference — which is what stops one runaway value squeezing every other column to nothing.
     *
     * Nothing is drawn below its own [Column.least]. A [room] too small for the leasts together overruns,
     * and the line that draws it is cut at the edge of the pane, which is the honest answer: there is no
     * width at which that content fits.
     */
    fun widths(cells: List<List<String>>, columns: List<Column>, room: Int, gap: Int = GAP): List<Int> {
        if (columns.isEmpty()) return emptyList()
        val spentOnGaps = gap * (columns.size - 1)
        val forTheText = (room - spentOnGaps).coerceAtLeast(columns.size)
        val wanted = columns.mapIndexed { at, column ->
            maxOf(column.least, cells.maxOfOrNull { it.getOrElse(at) { "" }.length } ?: 0)
        }
        if (wanted.sum() <= forTheText) return spread(wanted, columns, forTheText)
        fun levelledTo(ceiling: Int) = wanted.indices.map {
            minOf(wanted[it], maxOf(columns[it].least, ceiling))
        }
        var ceiling = wanted.max()
        while (ceiling > 1 && levelledTo(ceiling).sum() > forTheText) ceiling--
        return levelledTo(ceiling)
    }

    /** Room nobody needed, handed to the columns that said they would take it. */
    private fun spread(wanted: List<Int>, columns: List<Column>, room: Int): List<Int> {
        val takers = columns.indices.filter { columns[it].grows }
        val spare = room - wanted.sum()
        if (takers.isEmpty() || spare <= 0) return wanted
        val each = spare / takers.size
        val remainder = spare % takers.size
        return wanted.mapIndexed { at, width ->
            when (at) {
                takers.last() -> width + each + remainder
                in takers -> width + each
                else -> width
            }
        }
    }

    /**
     * [cells] laid across [widths] with [separator] between them, each cut to fit its own column.
     *
     * A cell with no column to sit in is dropped rather than run on, since a row longer than the header
     * is a row whose values have stopped lining up with what they are under.
     */
    fun laid(cells: List<Ink>, widths: List<Int>, separator: Line = Line("   "), found: String = ""): Line {
        var line = Line(emptyList())
        for ((at, width) in widths.withIndex()) {
            val ink = cells.getOrNull(at) ?: Ink("")
            if (at > 0) line += separator
            line += Line(highlighted(cell(ink.text, width, found), ink.style, found))
        }
        return line
    }

    /** The gap a table leaves between columns: a space, a bar and a space. */
    const val GAP = 3
}
