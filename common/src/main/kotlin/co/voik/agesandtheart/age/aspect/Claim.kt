package co.voik.agesandtheart.age.aspect

/**
 * What a writer asked to happen to a value — the `only`/`except` axis (design §4.3.1).
 *
 * Separate from the word rather than a property of it, because the same word means different things under
 * each: `andesite` names a substance, `only andesite` says the ground wears nothing else.
 *
 * **It lives with the world model rather than with the grammar**, even though `only` and `except` are
 * grammatical words, because the world model is what has to *store* it: a recipe outlives the sentence
 * (§4.6), so a populative parameter records what was asked of every value it holds. The grammar imports
 * this; the dependency runs that way already, since a [co.voik.agesandtheart.age.word.grammar.Constraint]
 * names an [Aspect] too.
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
 * One value of a **populative** parameter, and everything the writer asked of it.
 *
 * The three verbs a population supports (Jonah, on structures): *add, emphasise, exclude*. [polarity] carries
 * the first and third; [density] carries the second, and it is deliberately a separate axis rather than a
 * fourth polarity — "villages, and lots of them" is two independent things said about one value.
 *
 * **Spelled with marks**, because a population lives in [Options], which holds plain strings so that a recipe
 * stays a recipe: `minecraft:villages` asserted, `-minecraft:pillager_outposts` excepted,
 * `!minecraft:villages` exclusive, and `minecraft:villages@teeming` for a density rung. Marks rather than
 * parallel fields is what keeps this from moving the codec shape, and they are stripped before the value is
 * validated, so an id still has to be a real id.
 *
 * Only ever what a recipe spells and `/age compose` reads. A *writer* says `except pillager outposts` and never
 * sees a mark; §7.5's scratch mode is what shows the sentence back.
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
        /**
         * `-minecraft:pillager_outposts` — struck out.
         *
         * A leading hyphen cannot be confused with anything else here: a `ResourceLocation` never starts with
         * one, and the mark is removed before the id is parsed.
         */
        const val EXCEPT_MARK = '-'

        /** `!minecraft:villages` — this, and nothing the sentence did not also single out. */
        const val ONLY_MARK = '!'

        /**
         * `minecraft:villages@teeming` — how many of them.
         *
         * The same mark [AgeComposition] writes a share with, in the same sense of "how much of this", and
         * unambiguous for the same reason: it separates a value from a rung, and neither an id nor a rung
         * contains one.
         */
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
            // An unreadable rung leaves the value alone rather than swallowing the text after the mark: a
            // recipe must never quietly lose part of itself, and a typo shows up as an id nobody knows.
            val density = Density.named(rung) ?: return Claim(unmarked, polarity)
            return Claim(unmarked.substringBefore(DENSITY_MARK), polarity, density)
        }
    }
}

/**
 * A populative parameter's claims, sorted into the three things a consumer has to do about them.
 *
 * The reason this exists rather than a single "apply the claims to a list" helper: **what a population starts
 * from is not the same kind of thing as what a claim names.** A structures claim names a *structure set* while
 * the base is a list of them already resolved to holders; a biomes claim names a biome while the base is a
 * climate table of weighted points. So the only part that generalises is reading the sentence — which is this —
 * and each consumer then applies it to whatever it actually holds.
 *
 * [exclusive] is the whole of what `only` means to a list: **the aspect's own base is dropped** and the
 * population is what the sentence named. So `only villages` is villages and nothing else, and
 * `only villages mansions` is both, which is how a sentence carrying `only` reads aloud.
 *
 * Removals are kept apart from additions so a consumer can apply them **last**, which is what makes `except`
 * beat a mention of the same thing regardless of the order the pages were laid out (§3.5).
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
