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
     * game already has (world model §2).
     *
     * `minecraft:spring_water[of=agesandtheart:ink]` is a spring shaped exactly like vanilla's and running
     * with ink: the value names the pattern and this names the substance. A writer says `ink springs`, and
     * the two halves of that are these two fields. Materials the clause joined with `and` are all named,
     * separated by [MINGLED] — see [substances].
     */
    val madeOf: String? = null,
    /**
     * Whether this asks for **more or less of the thing where it already is**, rather than for the thing
     * itself — the difference between describing and naming (world model §3).
     *
     * `teeming trees` reaches seventy features by a tag and means "more of whatever trees grow here". Read
     * as a naming word it meant "put all seventy in every biome", which is how an Age came out carrying
     * acacia, bamboo and cherry everywhere at once (Jonah, 2026-09-03). Naming one outright — `acacia` —
     * still puts it where it was not, because that is what naming a thing is for.
     *
     * Set where a member was reached by a query and never mentioned by name. `only` and `except` are
     * exempt: those are instructions about what the Age holds, not preferences about how much of it.
     */
    val onlyWhereItGrows: Boolean = false,
    /**
     * How big this one is, or null to take the Age's own — **a size that belongs to its clause**.
     *
     * `colossal gold_block obelisks, tiny rings` asks for two sizes in one book, and a `Parameter` holds
     * one: read off the aspect the two contended, colossal won, and the rings came out colossal too. Size
     * sits here beside [madeOf] for the same reason [madeOf] does — both are things a clause says about
     * *this* member rather than about the part of the world it belongs to.
     */
    val size: Double? = null,
    /** How deep in the column this one sits, or null to take the Age's own — [size]'s twin. */
    val height: Double? = null,
) {
    /**
     * Whether this could not bring its member about — a **description** ([onlyWhereItGrows]) asking for no
     * more of the thing than ordinary.
     *
     * Where a claim may introduce at all, this is what decides it: less of something that is not here is
     * nothing, and the floor an evocative word's weight is held at (§3.3) turned every one of its faintest
     * reaches into an introduction — an inferno at a fifth strength in a beautiful Age, a ghast in every
     * biome of one.
     */
    val bringsNothingAbout: Boolean get() = onlyWhereItGrows && density <= Rung.ORDINARY

    /** The value read as a registry id, or null where it is not one. */
    val id: Identifier? get() = Identifier.tryParse(value)

    /**
     * What this brings into the Age, for telling two claims apart: `mud pits` and `sand pits` are two
     * things, where two mentions of `slime` are one.
     */
    val member: Pair<String, String?> get() = value to madeOf

    /** Every substance [madeOf] names: several where a clause mingled them, `mud and sand pits`. */
    val substances: List<String> get() = madeOf?.split(MINGLED).orEmpty()

    /** Whether this claim has anything to say where [biome] is what the ground holds. */
    fun appliesIn(biome: Identifier?): Boolean = confinedTo == null || confinedTo == biome

    /** How this is written into a recipe — bare where nothing was asked, so the common case is unadorned. */
    fun spelled(): String {
        // Held first: inside `buildList` the list's own `size` shadows this claim's.
        val ownSize = size
        val ownHeight = height
        val parts = buildList {
            when (polarity) {
                Polarity.ASSERTED -> Unit
                Polarity.ONLY -> add(ONLY)
                Polarity.EXCEPT -> add(EXCEPT)
            }
            if (onlyWhereItGrows) add(WHERE_IT_GROWS)
            if (!Rung.isOrdinary(density)) add("$AMOUNT$SETS${Rung.spelled(density)}")
            madeOf?.let { add("$OF$SETS$it") }
            ownSize?.let { add("$SIZE$SETS${Rung.spelled(it)}") }
            ownHeight?.let { add("$HEIGHT$SETS${Rung.spelled(it)}") }
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

        /** How a claim says it only bends what is already there — see [Claim.onlyWhereItGrows]. */
        const val WHERE_IT_GROWS = "where_it_grows"
        const val EXCEPT = "except"
        const val AMOUNT = "amount"
        const val IN = "in"
        const val OF = "of"
        const val SIZE = "size"
        const val HEIGHT = "height"

        /** Between the substances of one mingled minting: `of=minecraft:mud+minecraft:sand`. */
        const val MINGLED = '+'

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
            return Claim(
                value,
                polarity,
                amount,
                confinedTo,
                valueOf(parts, OF),
                onlyWhereItGrows = parts.any { it == WHERE_IT_GROWS },
                size = valueOf(parts, SIZE)?.toDoubleOrNull(),
                height = valueOf(parts, HEIGHT)?.toDoubleOrNull(),
            )
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
    /**
     * What to introduce, each with the density it was asked for — said plainly or singled out, alike. Never
     * the pool's emptier, which is a statement about the whole population rather than a member of it.
     */
    val wanted: List<Claim>,
    /** What to strike out. */
    val struck: List<String>,
    /** Whether the pool's emptier was named — `spawns nothing` — which drops the base as `only` does. */
    val emptied: Boolean = false,
) {
    /** Whether whatever the preset would have supplied is dropped: something was singled out, or emptied. */
    val startsFromNothing: Boolean get() = exclusive || emptied

    /** Whether the sentence said anything at all about this distribution. */
    val isSilent: Boolean get() = !startsFromNothing && wanted.isEmpty() && struck.isEmpty()

    companion object {
        /**
         * What these claims ask **where [biome] is the ground**, or of the whole Age where it is null, in a
         * pool emptied by [emptiedBy] — see [Pool.skewOf], which is how a reader should ask.
         *
         * A claim confined somewhere else is not merely ignored here, it is *absent*: "in the mushroom
         * fields, only slimes" leaves every other biome exactly as it was, which is what makes `only`
         * bearable inside a scope at all (§4.3.1).
         */
        fun of(claims: List<Claim>, biome: Identifier? = null, emptiedBy: String? = null): Skew {
            val here = claims.filter { it.appliesIn(biome) }
            fun claimsAt(polarity: Polarity) = here.filter { it.polarity == polarity }.distinctBy { it.member }
            val singledOut = claimsAt(Polarity.ONLY)
            val removed = claimsAt(Polarity.EXCEPT).map { it.value }
            // **Removals apply last** (§3.5), so a member one word named and another struck out is struck
            // out. Said here rather than in each reader: `Spawns.narrowed` dropped it and `Spawns.added` put
            // it straight back, which is the shape a rule kept in two places takes.
            val named = (singledOut + claimsAt(Polarity.ASSERTED))
                .distinctBy { it.member }
                .filterNot { it.value in removed }
            fun isTheEmptier(claim: Claim) = emptiedBy != null && claim.value == emptiedBy
            return Skew(
                exclusive = singledOut.isNotEmpty(),
                wanted = named.filterNot(::isTheEmptier),
                struck = removed,
                emptied = named.any(::isTheEmptier),
            )
        }
    }
}
