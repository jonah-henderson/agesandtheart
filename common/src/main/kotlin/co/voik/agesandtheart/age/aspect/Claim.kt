package co.voik.agesandtheart.age.aspect

/**
 * What a writer asked to happen to a value — the `only`/`except` axis (design §4.3.1).
 *
 * With the world model rather than the grammar, though `only` and `except` are grammatical words, because
 * the world model is what has to *store* it: a recipe outlives the sentence (§4.6).
 */
enum class Polarity {
    /** Said plainly. Adds; removes nothing. */
    ASSERTED,

    /** This and nothing else — the pin that naming alone deliberately never does (Jonah). */
    ONLY,

    /** Anything but this. */
    EXCEPT,
}

/**
 * One value of a populative parameter, and everything the writer asked of it: add, emphasise, exclude.
 * [polarity] carries the first and third, [density] the second — a separate axis, since "villages, and
 * lots of them" is two independent things said about one value.
 *
 * Spelled with marks, because a population lives in [Options], which holds plain strings so a recipe stays
 * a recipe. Marks rather than parallel fields is what keeps this from moving the codec shape, and they are
 * stripped before the value is validated. Only ever a recipe spelling; a writer never sees one.
 */
data class Claim(
    val value: String,
    val polarity: Polarity = Polarity.ASSERTED,
    val density: Density = Density.ORDINARY,
) {
    /** How this is written into a recipe — bare where nothing was asked, so the common case is unmarked. */
    fun spelled(): String {
        val marked = when (polarity) {
            Polarity.ASSERTED -> value
            Polarity.ONLY -> "$ONLY_MARK$value"
            Polarity.EXCEPT -> "$EXCEPT_MARK$value"
        }
        return if (density.isOrdinary) marked else "$marked$DENSITY_MARK${density.key}"
    }

    companion object {
        /** `-minecraft:pillager_outposts` — struck out. A `Identifier` never starts with a hyphen. */
        const val EXCEPT_MARK = '-'

        /** `!minecraft:villages` — this, and nothing the sentence did not also single out. */
        const val ONLY_MARK = '!'

        /** `minecraft:villages@teeming` — how many. Neither an id nor a rung contains an `@`. */
        const val DENSITY_MARK = '@'

        /** The claim [spelled] describes: asserted, ordinary, and bare unless it says otherwise. */
        fun read(spelled: String): Claim {
            val polarity = when (spelled.firstOrNull()) {
                EXCEPT_MARK -> Polarity.EXCEPT
                ONLY_MARK -> Polarity.ONLY
                else -> Polarity.ASSERTED
            }
            val unmarked = if (polarity == Polarity.ASSERTED) spelled else spelled.drop(1)
            val rung = unmarked.substringAfter(DENSITY_MARK, missingDelimiterValue = "")
            // An unreadable rung leaves the value alone rather than swallowing the text after the mark, so
            // a typo shows up as an id nobody knows.
            val density = Density.named(rung) ?: return Claim(unmarked, polarity)
            return Claim(unmarked.substringBefore(DENSITY_MARK), polarity, density)
        }
    }
}

/**
 * A populative parameter's claims, sorted into the three things a consumer has to do about them.
 *
 * Reading the sentence is the only part that generalises: a structures claim names a set where the base is
 * resolved holders, and a biomes claim names a biome where the base is a weighted climate table — so each
 * consumer applies this to whatever it actually holds.
 *
 * Removals are kept apart from additions so a consumer applies them **last**, which is what makes `except`
 * beat a mention of the same thing whatever order the pages were laid out in (§3.5).
 */
data class Population(
    /** Whether anything was singled out, in which case whatever the preset would have supplied is dropped. */
    val exclusive: Boolean,
    /** What to introduce, each with the density it was asked for — said plainly or singled out, alike. */
    val wanted: List<Claim>,
    /** What to strike out. */
    val struck: List<String>,
) {
    /** Whether the sentence said anything at all about this population. */
    val isSilent: Boolean get() = !exclusive && wanted.isEmpty() && struck.isEmpty()

    companion object {
        fun of(claims: List<Claim>): Population {
            fun claimsAt(polarity: Polarity) = claims.filter { it.polarity == polarity }.distinctBy { it.value }
            val singledOut = claimsAt(Polarity.ONLY)
            return Population(
                exclusive = singledOut.isNotEmpty(),
                wanted = (singledOut + claimsAt(Polarity.ASSERTED)).distinctBy { it.value },
                struck = claimsAt(Polarity.EXCEPT).map { it.value },
            )
        }
    }
}
