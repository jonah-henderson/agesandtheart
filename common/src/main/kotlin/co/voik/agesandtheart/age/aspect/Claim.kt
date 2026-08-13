package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.Identifier

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
 * One value of a populative parameter, and everything the writer asked of it: add, emphasise, exclude, and
 * where. Four independent things said about one value — "a great many villages, and only in the plains" is
 * one claim carrying all of them.
 *
 * **Spelled in brackets with the parts named** (Jonah, 2026-08-04):
 * `minecraft:slime[only,amount=4,in=minecraft:mushroom_fields]`. A recipe is read by people, and the marks
 * this used to carry — `!` for `only`, `-` for `except`, `@` for the amount — were three symbols to learn
 * before a line could be read at all, with no room for a fourth. The bare value stays bare, so the common
 * case is unchanged and nothing pays for a part it did not use.
 *
 * The brackets are the idiom a territory's own steering already uses (`terrain=spires[stone=copper]`) — and
 * **Minecraft's own**, which settles the shape: `oak_stairs[facing=north,half=top]` hangs data on a named
 * thing exactly like this, so a pack author brings the punctuation with them.
 */
data class Claim(
    val value: String,
    val polarity: Polarity = Polarity.ASSERTED,
    val density: Double = Rung.ORDINARY,
    /** The biome this is confined to, or null where it is about the whole Age — §4.3.1's `in`. */
    val confinedTo: Identifier? = null,
    /**
     * What a **minted** member is made of, or null for the overwhelming majority that name something the
     * game already has (world model §8.1.2).
     *
     * `minecraft:spring_water[of=agesandtheart:ink]` is a spring shaped exactly like vanilla's and running
     * with ink: the value names the pattern and this names the substance. A writer says `ink springs`, and
     * the two halves of that are these two fields.
     */
    val madeOf: String? = null,
) {
    /** Whether this claim has anything to say where [biome] is what the ground holds. */
    fun appliesIn(biome: Identifier?): Boolean = confinedTo == null || confinedTo == biome

    /** How this is written into a recipe — bare where nothing was asked, so the common case is unadorned. */
    fun spelled(): String {
        val parts = buildList {
            when (polarity) {
                Polarity.ASSERTED -> Unit
                Polarity.ONLY -> add(ONLY)
                Polarity.EXCEPT -> add(EXCEPT)
            }
            if (!Rung.isOrdinary(density)) add("$AMOUNT$SETS${Rung.spelled(density)}")
            madeOf?.let { add("$OF$SETS$it") }
            confinedTo?.let { add("$IN$SETS$it") }
        }
        if (parts.isEmpty()) return value
        return "$value$OPEN${parts.joinToString(BETWEEN.toString())}$CLOSE"
    }

    companion object {
        /** `minecraft:slime[only,amount=4,in=minecraft:mushroom_fields]` — the parts, named. */
        const val OPEN = '['
        const val CLOSE = ']'
        private const val BETWEEN = ','
        private const val SETS = '='

        const val ONLY = "only"
        const val EXCEPT = "except"
        const val AMOUNT = "amount"
        const val IN = "in"
        const val OF = "of"

        /** The claim [spelled] describes: asserted, ordinary, everywhere, unless it says otherwise. */
        fun read(spelled: String): Claim {
            val value = spelled.substringBefore(OPEN)
            if (OPEN !in spelled) return Claim(value)
            val parts = spelled.substringAfter(OPEN).substringBeforeLast(CLOSE)
                .split(BETWEEN)
                .map(String::trim)
                .filter(String::isNotEmpty)
            // An unreadable part leaves its own axis alone rather than failing the claim: a typo in one of
            // four should cost that one, and `/age list` shows what the Age actually holds.
            val polarity = when {
                parts.any { it == ONLY } -> Polarity.ONLY
                parts.any { it == EXCEPT } -> Polarity.EXCEPT
                else -> Polarity.ASSERTED
            }
            val amount = valueOf(parts, AMOUNT)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: Rung.ORDINARY
            val confinedTo = valueOf(parts, IN)?.let(Identifier::tryParse)
            return Claim(value, polarity, amount, confinedTo, valueOf(parts, OF))
        }

        private fun valueOf(parts: List<String>, named: String): String? = parts
            .firstOrNull { it.startsWith("$named$SETS") }
            ?.substringAfter(SETS)
    }
}

/**
 * **How a sentence skews a distribution** ([Holds.WEIGHTED_SET]) — what to introduce, what to strike out,
 * and whether anything was singled out.
 *
 * Named for what it does rather than what it is about, because `Population` is what [Holds.POPULATION]
 * means now: a cast of individuals, which is the other thing entirely.
 *
 * Reading the sentence is the only part that generalises: a structures claim names a set where the base is
 * resolved holders, and a biomes claim names a biome where the base is a weighted climate table — so each
 * consumer applies this to whatever it actually holds.
 *
 * Removals are kept apart from additions so a consumer applies them **last**, which is what makes `except`
 * beat a mention of the same thing whatever order the pages were laid out in (§3.5).
 */
data class Skew(
    /** Whether anything was singled out, in which case whatever the preset would have supplied is dropped. */
    val exclusive: Boolean,
    /** What to introduce, each with the density it was asked for — said plainly or singled out, alike. */
    val wanted: List<Claim>,
    /** What to strike out. */
    val struck: List<String>,
) {
    /** Whether the sentence said anything at all about this distribution. */
    val isSilent: Boolean get() = !exclusive && wanted.isEmpty() && struck.isEmpty()

    companion object {
        /**
         * What these claims ask **where [biome] is the ground**, or of the whole Age where it is null.
         *
         * A claim confined somewhere else is not merely ignored here, it is *absent*: "in the mushroom
         * fields, only slimes" leaves every other biome exactly as it was, which is what makes `only`
         * bearable inside a scope at all (§4.3.1).
         */
        fun of(claims: List<Claim>, biome: Identifier? = null): Skew {
            val here = claims.filter { it.appliesIn(biome) }
            fun claimsAt(polarity: Polarity) = here.filter { it.polarity == polarity }.distinctBy { it.value }
            val singledOut = claimsAt(Polarity.ONLY)
            return Skew(
                exclusive = singledOut.isNotEmpty(),
                wanted = (singledOut + claimsAt(Polarity.ASSERTED)).distinctBy { it.value },
                struck = claimsAt(Polarity.EXCEPT).map { it.value },
            )
        }
    }
}
