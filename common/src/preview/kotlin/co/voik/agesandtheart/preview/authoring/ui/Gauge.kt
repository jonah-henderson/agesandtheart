package co.voik.agesandtheart.preview.authoring.ui

import com.github.ajalt.mordant.rendering.TextStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **A number drawn as a bar** — the one place a weight becomes something to look at rather than read.
 *
 * A weight is a figure between -1 and 1 and every screen here has several of them at once, where what a
 * reader wants is which is the big one. A bar answers that at a glance, and it does a second job the
 * figure cannot: a row wearing one is visibly a row with a value in it, so a list that is **set** rather
 * than picked from says so without the header having to.
 */
object Gauge {

    /**
     * [of] out of [most], filled from the left — for a quantity with no other end to it.
     *
     * Empty where [most] is nothing, rather than full: no strongest member means nothing has been claimed,
     * and drawing that as everything at once is the wrong way round.
     */
    fun filled(of: Double, most: Double, width: Int, style: TextStyle = Palette.settled): Line {
        if (width <= 0) return Line("")
        val full = if (most <= 0.0) 0 else (of / most * width).roundToInt().coerceIn(0, width)
        return Line(Glyph.FULL.repeat(full), style) + Line(Glyph.EMPTY.repeat(width - full), Palette.faint)
    }

    /**
     * A signed bar about a centre: right of it **pulls toward**, left of it **pushes away**.
     *
     * Two colours rather than one, because the sign is the half of a lean that changes what it does — a
     * green bar growing right and a red one growing left are the same claim read in opposite directions,
     * and a single colour would leave the reader deciding it off the figure again.
     */
    fun signed(at: Double, width: Int): Line {
        val half = (width - 1) / 2
        if (half <= 0) return Line("")
        val reach = (abs(at).coerceAtMost(1.0) * half).roundToInt()
        val pushed = if (at < 0.0) reach else 0
        val pulled = if (at > 0.0) reach else 0
        return Line(Glyph.EMPTY.repeat(half - pushed), Palette.faint) +
            Line(Glyph.FULL.repeat(pushed), Palette.refused) +
            Line(Glyph.BAR, Palette.rule) +
            Line(Glyph.FULL.repeat(pulled), Palette.settled) +
            Line(Glyph.EMPTY.repeat(half - pulled), Palette.faint)
    }
}
