package co.voik.agesandtheart.age.aspect

/**
 * What a word asks of one ranged axis — **a demand, a limit, or a nudge**, and which of the three decides
 * whether two words can share the axis at all (design §3.3, §4.4).
 *
 * A word could only ever *replace* a band, so every word about temperature was a rival for it and any two
 * of them either overlapped or fractured the world. That is right for words that mean a particular heat and
 * wrong for words that merely lean warm, and with only one form available every word had to be the first
 * kind (Jonah, 2026-08-06).
 *
 * The three forms and what they cost:
 *
 * - **[Fixed]** `-0.25..0.45` — *this band*. Two of them must overlap or the Age is at odds with itself,
 *   which is what instability is for: `temperate` and `scorching` genuinely cannot both be true.
 * - **[Bound]** `>0.4`, `<0.2` — *at least this*, *at most this*. A limit yields to anything already inside
 *   it, so `warm scorching` is not a quarrel — scorching's band already clears warm's floor.
 * - **[Shift]** `+0.3`, `-0.2` — *more than it would have been*. Nudges compose rather than compete: two of
 *   them sum, so a word that leans warm and one that leans wet are simply both applied.
 * - **[Spread]** `~0.3`, `~-0.2` — *broader*, *narrower*, about the band's own middle. Says how sure the
 *   word is rather than what it wants.
 *
 * **Order is the whole of how they combine.** Demands and limits settle first, because they are the claims
 * that can fail; nudges and spreads apply to whatever survived, because they cannot. A nudge is therefore
 * never the reason two words disagree — it moves the answer, not the argument.
 */
sealed interface Setting {

    /** How a word writes this — the other half of [read], so a tool that builds one can put it back. */
    fun spelled(): String

    /** This band and no other. Two of these must overlap. */
    data class Fixed(val span: Span) : Setting {
        override fun spelled(): String = span.spelled()
    }

    /** A floor, a ceiling, or both — whatever else happens, stay inside this. */
    data class Bound(val least: Double? = null, val most: Double? = null) : Setting {
        // A floor and a ceiling at once is a band, and is spelled as one; this is each on its own.
        override fun spelled(): String = when {
            least != null && most != null -> Span(least, most).spelled()
            least != null -> "$AT_LEAST${Span.trimmed(least)}"
            most != null -> "$AT_MOST${Span.trimmed(most)}"
            else -> ""
        }
    }

    /** Move whatever the band turned out to be. Sums with every other shift on the axis. */
    data class Shift(val by: Double) : Setting {
        override fun spelled(): String = if (by < 0) Span.trimmed(by) else "$RAISE${Span.trimmed(by)}"
    }

    /** Widen (positive) or narrow (negative) about the band's middle. */
    data class Spread(val by: Double) : Setting {
        override fun spelled(): String = "$SPREAD${Span.trimmed(by)}"
    }

    companion object {
        internal const val AT_LEAST = '>'
        internal const val AT_MOST = '<'
        internal const val SPREAD = '~'
        internal const val RAISE = '+'

        /** The [Setting] this text describes, or null where it is not one. */
        fun read(spelled: String): Setting? {
            val text = spelled.trim()
            if (text.isEmpty()) return null
            val rest = text.drop(1)
            return when {
                text.startsWith(AT_LEAST) -> rest.toDoubleOrNull()?.let { Bound(least = it) }
                text.startsWith(AT_MOST) -> rest.toDoubleOrNull()?.let { Bound(most = it) }
                text.startsWith(SPREAD) -> rest.toDoubleOrNull()?.let(::Spread)
                // A leading sign is a nudge; a bare negative number is the start of a band and is not.
                text.startsWith(RAISE) -> rest.toDoubleOrNull()?.let(::Shift)
                text.startsWith('-') && Span.read(text) == null -> rest.toDoubleOrNull()?.let { Shift(-it) }
                else -> Span.read(text)?.let(::Fixed)
            }
        }

        /** Whether [option] is a well-formed setting, which is what a ranged parameter accepts. */
        fun describes(option: String): Boolean = read(option) != null

        /**
         * The band [asked] leaves, starting from [natural] — or null where the demands cannot all be met,
         * which is the caller's cue to charge for a world at odds with itself.
         *
         * Demands intersect, limits clip, and only then do nudges and spreads move what is left. A shift
         * that pushes a band off the end of the axis is slid back rather than clipped: `+1.0` on a hot
         * band means "as hot as this world goes", not "a band of one point at the top".
         */
        fun settle(asked: List<Setting>, natural: Span = Span.NATURAL): Span? {
            val demanded = asked.filterIsInstance<Fixed>().map { it.span }
            var band = demanded.fold(natural) { standing, next ->
                if (!standing.overlaps(next)) return null
                Span(maxOf(standing.least, next.least), minOf(standing.most, next.most), next.bend)
            }
            for (limit in asked.filterIsInstance<Bound>()) {
                val least = maxOf(band.least, limit.least ?: band.least)
                val most = minOf(band.most, limit.most ?: band.most)
                if (least > most) return null
                band = Span(least, most, band.bend)
            }
            val spread = asked.filterIsInstance<Spread>().sumOf { it.by }
            if (spread != 0.0) {
                val middle = (band.least + band.most) / 2.0
                val half = ((band.width / 2.0) + spread).coerceAtLeast(0.0)
                band = Span(
                    (middle - half).coerceAtLeast(natural.least),
                    (middle + half).coerceAtMost(natural.most),
                    band.bend,
                )
            }
            val shift = asked.filterIsInstance<Shift>().sumOf { it.by }
            if (shift != 0.0) band = band.slid(shift, natural)
            return band
        }
    }
}

/**
 * This band moved by [by], kept whole inside [within].
 *
 * Slid rather than clipped: a band pushed past the end of the axis keeps its width and comes to rest
 * against the end, so a hard nudge means "as far that way as this world goes" rather than collapsing the
 * band to a point. A band already wider than the axis simply becomes the axis.
 */
fun Span.slid(by: Double, within: Span): Span {
    if (width >= within.width) return Span(within.least, within.most, bend)
    val room = within.width - width
    val from = (least - within.least + by).coerceIn(0.0, room)
    return Span(within.least + from, within.least + from + width, bend)
}
