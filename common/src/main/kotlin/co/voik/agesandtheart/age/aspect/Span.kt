package co.voik.agesandtheart.age.aspect

import kotlin.math.ln
import kotlin.math.pow

/**
 * A stretch of a continuous axis a word will allow — `beautiful` keeping temperature between 0.4 and 0.6.
 *
 * **Numbers here are permitted.** Design §3.2 forbids numbers exposed to the *player*, not numbers in
 * resolution: a span is named by the word carrying it, priced by that word's tier, and gated on holding
 * the page. A writer never types one.
 *
 * A span rather than a ladder of named bands because named steps need a preset per combination of axes,
 * which explodes as axes are added — and because it makes the word the correlation: `beautiful` names
 * temperature *and* humidity in one breath, so nothing can draw the two independently.
 */
data class Span(val least: Double, val most: Double, val bend: Double = EVEN) {
    init {
        require(least <= most) { "a span cannot run backwards: $least..$most" }
        require(bend > 0.0 && bend < 1.0) { "a bend is where the middle falls inside the span: $bend" }
    }

    /** How wide it is — nothing, for a span pinned to a single value. */
    val width: Double get() = most - least

    /** Whether this and [other] share any ground at all. */
    fun overlaps(other: Span): Boolean = least <= other.most && other.least <= most

    /**
     * The smallest span holding both — what several words that do not disagree compose to. The convex hull
     * rather than a set union: two disjoint stretches on one axis is a *fracture*, which is the resolver's
     * business rather than this type's.
     */
    fun broadenedTo(other: Span): Span = Span(minOf(least, other.least), maxOf(most, other.most))

    /**
     * The stretch both of these admit — what two words *agreeing* about an axis leave between them.
     *
     * Safe to fold over any set of spans that pairwise [overlaps], and that is not luck: these are
     * intervals on a line, where pairwise overlap guarantees a common point (Helly's theorem in one
     * dimension). A group is built by requiring every member to agree with every other, so its intersection
     * can never come out empty.
     */
    fun narrowedTo(other: Span): Span =
        Span(maxOf(least, other.least), minOf(most, other.most), bend)

    /**
     * [value] remapped from the axis's natural full range into this span — monotone, so the spatial
     * structure of the climate noise survives and a warm world still has its warmer and cooler places.
     *
     * [bend] decides **where the middle of the world falls inside the span**, which is the whole of what an
     * evocative word does to an axis (§4.4). At [EVEN] this is linear and half the world sits either side
     * of the midpoint; bent low, more of the world crowds the cool end of a band it still fills entirely.
     */
    fun remap(value: Float): Float {
        val fraction = ((value - NATURAL_LEAST) / NATURAL_WIDTH).coerceIn(0.0, 1.0)
        return (least + bent(fraction) * width).toFloat()
    }

    /**
     * [fraction] pulled toward [bend], leaving 0 and 1 where they are.
     *
     * A power curve, whose exponent is chosen so the *median* lands on [bend] — `0.5^gamma == bend`. That
     * is what makes the number mean something a reader can hold: not a strength, but the place half the
     * world sits below.
     */
    private fun bent(fraction: Double): Double {
        if (bend == EVEN) return fraction
        return fraction.pow(ln(bend) / ln(EVEN))
    }

    /**
     * How a recipe holds it: `0.4..0.6`, and `0.4..0.6~0.3` where an evocative word bent it. Only ever what
     * `/age list` prints and `/age compose` reads.
     */
    fun spelled(): String {
        val stretch = "${trimmed(least)}$SPAN_MARK${trimmed(most)}"
        return if (bend == EVEN) stretch else "$stretch$BEND_MARK${trimmed(bend)}"
    }

    /** This span with its middle moved to [where] — what an evocative word's preference amounts to. */
    fun bentToward(where: Double): Span = copy(bend = where.coerceIn(NARROWEST_BEND, 1.0 - NARROWEST_BEND))

    /** Where [value] sits across this span, as a fraction — the inverse of the linear half of [remap]. */
    fun fractionOf(value: Double): Double =
        if (width <= 0.0) EVEN else ((value - least) / width).coerceIn(0.0, 1.0)

    /**
     * A band's end, as a recipe should carry it.
     *
     * **Rounded, because a recipe is written down and read by people.** Bands are arithmetic now — a nudge
     * of `+0.3` on a band starting at `-0.2` lands on `0.10000000000000009`, which is the same number and a
     * far worse thing to find in a saved Age or a `/age list`. Four places is more than any authored band
     * uses and far more than a climate axis can tell apart.
     */
    private fun trimmed(number: Double): String = Companion.trimmed(number)

    override fun toString(): String = spelled()

    companion object {
        /** `..`, the same range operator Kotlin spells, so a reader needs no key to it. */
        const val SPAN_MARK = ".."

        /** `~`, against `..` for the stretch — a wave for a curve, and nothing else in a recipe uses it. */
        const val BEND_MARK = "~"

        /** A span nobody bent: the middle of the world falls in the middle of the band. */
        const val EVEN = 0.5

        /** How far a bend may be pushed, so a curve stays a curve and never becomes a pin. */
        private const val NARROWEST_BEND = 0.05

        /** Climate parameters live in [-1, 1], and every axis a span can describe shares that convention. */
        const val NATURAL_LEAST = -1.0
        const val NATURAL_MOST = 1.0
        private const val NATURAL_WIDTH = NATURAL_MOST - NATURAL_LEAST

        /** The whole axis — what an Age that was told nothing gets, and the identity of [broadenedTo]. */
        val NATURAL = Span(NATURAL_LEAST, NATURAL_MOST)

        /** Four decimal places, as [trimmed] argues. */
        private const val PLACES = 10_000.0

        /** An axis pinned to one point, which is how a recipe asks for a value outright. */
        fun at(value: Double): Span = Span(value, value)

        /** A number as this file writes one — see [Span.spelled], whose argument is the whole of why. */
        fun trimmed(number: Double): String {
            val rounded = Math.round(number * PLACES) / PLACES
            return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
        }

        /** The span [spelled] describes, or null where the text is not one. */
        fun read(spelled: String): Span? {
            val stretch = spelled.substringBefore(BEND_MARK)
            val bend = if (BEND_MARK in spelled) {
                spelled.substringAfter(BEND_MARK).toDoubleOrNull()?.takeIf { it > 0.0 && it < 1.0 } ?: return null
            } else {
                EVEN
            }
            val least = stretch.substringBefore(SPAN_MARK).toDoubleOrNull() ?: return null
            val most = stretch.substringAfter(SPAN_MARK, missingDelimiterValue = "").toDoubleOrNull() ?: return null
            if (least > most) return null
            return Span(least, most, bend)
        }

        /** Whether [option] is a well-formed span, which is what a ranged parameter accepts. */
        fun describes(option: String): Boolean = read(option) != null
    }
}
