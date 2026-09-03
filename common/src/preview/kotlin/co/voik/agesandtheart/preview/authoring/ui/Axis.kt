package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Span

/**
 * A numeric axis, drawn — **what the number means, and where the value lands on it.**
 *
 * A range runs -1 to 1 and the number says nothing on its own. `Parameter.landmarks` says what the game
 * does at points along it, and this puts them on a line so `<-0.5` can be seen to be somewhere between
 * snowy and plains rather than read as a bare figure.
 *
 * Three rows: the scale, the landmarks under their ticks, and the value shaded across the ground it
 * actually claims. The third is `Setting.settle`'s answer rather than a reading of the text, so a floor,
 * a ceiling, a nudge and a spread all show where they really end up.
 */
object Axis {

    /**
     * The whole chart for [parameter], with [said] shaded where it falls.
     *
     * **Laid on its side where the pane is narrow.** A scale across the screen wants room for every label
     * at once and there is a width below which it can only drop them; turned upright it wants one row per
     * landmark instead, which a narrow pane has and a short one does not. Empty only where the axis has
     * nothing to say about itself.
     */
    fun chart(
        parameter: Parameter,
        said: String?,
        width: Int,
        handle: Double? = null,
        /**
         * The band [said] is applied to, where it is a claim about one rather than a band itself.
         *
         * A nudge and a spread say what becomes of whatever the Age had, so on the whole axis they move
         * nothing and draw as everything. Given a band to work on they show what they do, which is the
         * only way either of them can be seen at all.
         */
        from: Span = Span.NATURAL,
        /** Whether what is drawn is an illustration rather than the value itself — coloured apart, and said. */
        forExample: Boolean = false,
    ): List<Line> {
        // **A scale with nothing named on it is still a scale.** An axis nobody has written landmarks for
        // used to draw nothing at all, so the one parameter where a number says least about itself was the
        // one shown no ruler to read it against.
        val marks = parameter.landmarks.ifEmpty { BARE }
        if (width < NARROWEST) return upright(marks, said)
        val ruler = width - ENDS
        return listOfNotNull(scaleLine(marks, ruler), labelLine(marks, ruler)) +
            said?.let { bandLines(it, ruler, handle, from, forExample) }.orEmpty()
    }

    /**
     * The axis stood upright: highest at the top, a row each, and the ones the value covers marked.
     *
     * The band is a column beside the labels rather than a stretch under them — the same information in
     * the shape a narrow screen has room for.
     */
    private fun upright(marks: List<Parameter.Landmark>, said: String?): List<Line> {
        val settled = said?.let { Setting.read(it) }?.let { Setting.settle(listOf(it)) }
        val ordered = marks.sortedByDescending { it.at }
        val rows = ordered.map { mark ->
            val covered = settled != null && mark.at >= settled.least && mark.at <= settled.most
            Line("  ") +
                Line("%5.2f".format(mark.at), if (covered) Palette.value else Palette.faint) +
                Line(" ${if (mark.isVanilla) VANILLA_TICK else Glyph.BAR.single()} ", if (mark.isVanilla) Palette.chosen else Palette.rule) +
                Line(if (covered) Glyph.FULL else Glyph.EMPTY, Palette.chosen) +
                Line("  ${mark.said}", if (covered) Palette.value else Palette.faint)
        }
        return rows + listOfNotNull(settled?.let { Line("  ${it.spelled()}", Palette.faint) })
    }

    /**
     * `-1.0 ├───┬────╪──────┬─────┤ 1.0`, a tick at each landmark.
     *
     * **The game's own value wears a different tick and is lit**, because it is the one point on the axis
     * a writer is measuring against rather than reading about: everything a word does here is a departure
     * from it, and a scale that does not say where it starts is a scale of nothing.
     */
    private fun scaleLine(marks: List<Parameter.Landmark>, ruler: Int): Line {
        val row = CharArray(ruler) { RULE }
        for (mark in marks) row[columnFor(mark.at, ruler)] = if (mark.isVanilla) VANILLA_TICK else TICK
        row[0] = LEFT_END
        row[ruler - 1] = RIGHT_END
        val ends = Line("  ") + Line("%.1f ".format(Span.NATURAL_LEAST), Palette.faint)
        val lit = marks.firstOrNull { it.isVanilla }?.let { columnFor(it.at, ruler) }
        val scale = if (lit == null) {
            Line(String(row), Palette.rule)
        } else {
            Line(String(row.copyOfRange(0, lit)), Palette.rule) +
                Line(VANILLA_TICK.toString(), Palette.chosen) +
                Line(String(row.copyOfRange((lit + 1).coerceAtMost(ruler), ruler)), Palette.rule)
        }
        return ends + scale + Line(" %.1f".format(Span.NATURAL_MOST), Palette.faint)
    }

    /** Each landmark's name under its tick, dropped where the one before has taken the room. */
    private fun labelLine(marks: List<Parameter.Landmark>, ruler: Int): Line {
        val row = CharArray(ruler) { ' ' }
        var taken = 0
        for (mark in marks.sortedBy { it.at }) {
            val tick = columnFor(mark.at, ruler).coerceAtMost(ruler - 1)
            // The last one is pulled left to end at the edge rather than cut to its first letter — a
            // label at the top of the axis is the one somebody is reaching for.
            val from = if (tick + mark.said.length > ruler) {
                (ruler - mark.said.length).coerceAtLeast(0)
            } else {
                tick
            }
            if (from < taken) continue
            val said = mark.said.take(ruler - from)
            said.forEachIndexed { at, letter -> row[from + at] = letter }
            taken = from + said.length + 1
        }
        return Line(" ".repeat(LEAD)) + Line(String(row), Palette.value)
    }

    /**
     * Where [said] lands, shaded.
     *
     * Through `Setting.settle` rather than by reading the text: `>0.4` is a floor and `+0.3` is a nudge,
     * and what a writer wants to see is the band each of them leaves rather than the number they typed.
     */
    private fun bandLines(
        said: String,
        ruler: Int,
        handle: Double?,
        natural: Span,
        forExample: Boolean,
    ): List<Line> {
        val settled = Setting.read(said)?.let { Setting.settle(listOf(it), natural) } ?: return emptyList()
        val tone = if (forExample) Palette.nudged else Palette.chosen
        // **On its own row.** The scale is laid to the pane exactly, so anything after the band ran past
        // the edge and was cut — which read as a stray character under the top of the axis.
        val aside = Line(" ".repeat(LEAD)) + if (forExample) {
            Line("${natural.spelled()} becomes ${settled.spelled()}", Palette.nudged)
        } else {
            Line(settled.spelled(), Palette.faint)
        }
        val from = columnFor(settled.least, ruler)
        val to = columnFor(settled.most, ruler)
        val row = CharArray(ruler) { at -> if (at in from..to) Glyph.FULL.single() else Glyph.EMPTY.single() }
        // **The end the keys are moving, lit.** Two ends and one pair of keys is a guess about which is
        // about to move; this is the answer, and it is the whole of what makes the band editable rather
        // than merely drawn.
        val lit = handle?.let { columnFor(it, ruler) }
        val band = if (lit == null) {
            Line(" ".repeat(LEAD)) + Line(String(row), tone)
        } else {
            Line(" ".repeat(LEAD)) +
                Line(String(row.copyOfRange(0, lit)), tone) +
                Line(Glyph.FILLED, Palette.focused) +
                Line(String(row.copyOfRange((lit + 1).coerceAtMost(ruler), ruler)), tone)
        }
        return listOf(band, aside)
    }

    private fun columnFor(value: Double, ruler: Int): Int {
        val fraction = (value - Span.NATURAL_LEAST) / (Span.NATURAL_MOST - Span.NATURAL_LEAST)
        return (fraction * (ruler - 1)).toInt().coerceIn(0, ruler - 1)
    }

    /** Two spaces, `-1.0`, a space — what the scale's left label occupies before the ruler starts. */
    private const val LEAD = 7

    /** The same again on the right, for ` 1.0`. */
    private const val ENDS = LEAD + 4

    /** Below this there is no room for a scale worth reading. */
    private const val NARROWEST = 24

    /**
     * What an axis with no landmarks of its own is marked with.
     *
     * **The middle rather than "the default"**, which it is not: a ranged parameter nobody speaks to keeps
     * the whole span and a value is drawn inside it, so nothing about zero is what an Age would have got.
     * It is a place to read the ruler against, and says so.
     */
    private val BARE = listOf(Parameter.Landmark(0.0, "the middle"))

    private const val RULE = '─'
    private const val TICK = '┬'

    /** Where the game's own value sits, told apart from an ordinary landmark at a glance. */
    private const val VANILLA_TICK = '╪'
    private const val LEFT_END = '├'
    private const val RIGHT_END = '┤'
}
