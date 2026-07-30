package co.voik.agesandtheart.age.aspect

/**
 * A stretch of a continuous axis a word will allow — `beautiful` keeping temperature between 0.4 and 0.6.
 *
 * **The one place numbers reach the world model, and design §3.2 permits it** — read that section's opening
 * before assuming otherwise. Its rule is *no numbers exposed to the **player***, not no numbers anywhere in
 * resolution: a span is named by the word that carries it, priced by that word's tier, and gated by whether the
 * player has the page, so everything §3.2 was protecting survives. What it forbids is a knob with no name, no
 * cost and nothing to say. A writer never types one of these.
 *
 * **Why a span rather than a named band** (Jonah, 2026-07-29). Climate's axes could have been given ladders of
 * named steps, and that was tried three ways; every version needed a *preset* per combination of axes, which
 * explodes as the axes come back. Putting the numbers on the word instead keeps the vocabulary **linear** — six
 * axes cost six ranges per word — and, more importantly, **makes the word the correlation**: `beautiful` names
 * temperature *and* humidity in one breath, so nothing can draw the two independently and hand somebody a
 * desert.
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
     * The smallest span holding both — what several words that do not disagree compose to.
     *
     * **The convex hull rather than a set union**, deliberately: a set union of two disjoint spans is two
     * spans, and an axis that holds two disjoint stretches is a *fracture*, which is the resolver's business
     * rather than this type's. Where words genuinely disagree they are given ground of their own instead.
     */
    fun broadenedTo(other: Span): Span = Span(minOf(least, other.least), maxOf(most, other.most))

    /**
     * [value] remapped from the axis's natural full range into this span.
     *
     * Linear, so the *spatial* structure of the climate noise survives untouched — a warm world still has its
     * warmer and cooler places, they simply all lie inside the span. That is what makes a single span read as a
     * coherent climate rather than as a flat one.
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
