package co.voik.agesandtheart.age.aspect

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
data class Span(val least: Double, val most: Double) {
    init {
        require(least <= most) { "a span cannot run backwards: $least..$most" }
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
     * [value] remapped from the axis's natural full range into this span. Linear, so the spatial structure
     * of the climate noise survives — a warm world still has its warmer and cooler places.
     */
    fun remap(value: Float): Float {
        val fraction = ((value - NATURAL_LEAST) / NATURAL_WIDTH).coerceIn(0.0, 1.0)
        return (least + fraction * width).toFloat()
    }

    /** How a recipe holds it: `0.4..0.6`. Only ever what `/age list` prints and `/age compose` reads. */
    fun spelled(): String = "${trimmed(least)}$SPAN_MARK${trimmed(most)}"

    private fun trimmed(number: Double): String =
        if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

    override fun toString(): String = spelled()

    companion object {
        /** `..`, the same range operator Kotlin spells, so a reader needs no key to it. */
        const val SPAN_MARK = ".."

        /** Climate parameters live in [-1, 1], and every axis a span can describe shares that convention. */
        const val NATURAL_LEAST = -1.0
        const val NATURAL_MOST = 1.0
        private const val NATURAL_WIDTH = NATURAL_MOST - NATURAL_LEAST

        /** The whole axis — what an Age that was told nothing gets, and the identity of [broadenedTo]. */
        val NATURAL = Span(NATURAL_LEAST, NATURAL_MOST)

        /** The span [spelled] describes, or null where the text is not one. */
        fun read(spelled: String): Span? {
            val least = spelled.substringBefore(SPAN_MARK).toDoubleOrNull() ?: return null
            val most = spelled.substringAfter(SPAN_MARK, missingDelimiterValue = "").toDoubleOrNull() ?: return null
            if (least > most) return null
            return Span(least, most)
        }

        /** Whether [option] is a well-formed span, which is what a ranged parameter accepts. */
        fun describes(option: String): Boolean = read(option) != null
    }
}
