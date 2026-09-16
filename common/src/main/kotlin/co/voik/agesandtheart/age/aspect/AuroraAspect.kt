package co.voik.agesandtheart.age.aspect

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Aurora
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * The curtain an Age wears: what a writer may say about one, and what is drawn where they said
 * nothing.
 *
 * Its own object rather than a stretch of [Sky] because an aurora is its own aspect ([Aspect.AURORA])
 * and shares nothing with a rainbow but the arithmetic in [Sky.bandAt] and [Sky.drawn].
 */
object AuroraAspect {

    /**
     * The colours the curtain burns, crown first — **the one dial that holds several values in order**.
     *
     * A ramp rather than a colour, because that is what an aurora is: it changes from its crown to its
     * hem, and a writer naming two means both. `and` is what joins them, exactly as it joins two rocks
     * in a wall; [Parameter.keepsWrittenOrder] is what keeps the crown at the crown.
     */
    val AURORACOLOUR = Sky.colour("colour", "The colours of the aurora, from its crown to its hem.")
        .copy(keepsWrittenOrder = true)

    /** How brightly the curtain burns, against an ordinary one. */
    val AURORAGLOW = Parameter.ranged("glow", help = "How brightly the aurora burns.")

    /**
     * How much of the sky the curtain takes up.
     *
     * **One axis for both directions**, because a curtain is not two independent measurements: a small
     * one is small, and a writer who wanted a wide low band and a narrow tall one is asking a question
     * §3.2 keeps away from them.
     */
    val AURORASIZE = Parameter.ranged(
        "size",
        help = "How much of the sky the aurora takes up.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a narrow band"),
            Parameter.Landmark(0.0, "a broad ribbon"),
            Parameter.Landmark(1.0, "most of the sky"),
        ),
    )

    /** What share of nights it comes at all. */
    val AURORAFREQUENCY = Parameter.ranged("frequency", help = "What share of nights the aurora comes at all.")

    /**
     * How warm the ground under it may be and still show one.
     *
     * **The cold was only ever a proxy for the poles**, and a loose one: nothing in an Age carries
     * anything electromagnetic for a curtain to key on, so `coldEnoughToSnow` stood in for a latitude
     * we do not have. That is a fair default and a bad requirement — an Age charged enough to hang
     * curtains has no reason to be a cold one (Jonah, 2026-09-08). So the coupling is loosened *here*,
     * where a writer can ask, rather than removed: an aurora nobody described still stands where the
     * snow does.
     *
     * **What it does not loosen is anything that reads the cold for its own sake.** Rime demands a
     * frozen climate outright and takes the curtain as incidental, so nothing about it moves.
     */
    val AURORAWARMTH = Parameter.ranged(
        "warmth",
        help = "How warm the ground under the aurora may be and still show one.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "only over deep ice"),
            Parameter.Landmark(0.0, "where the snow lies"),
            Parameter.Landmark(1.0, "over any ground at all"),
        ),
    )

    /**
     * The curtain this Age wears, or null where nothing asked for one.
     *
     * **Two ways in, and they are the same statement made twice** — which is the shape §7.7 says this aspect
     * has. A writer may *name* the phenomenon (`auroral`, or an evocative word the tag layer carried there)
     * or *describe* it (`red and green aurora`), and either is having said the Age has one. Nothing mints a
     * member here the way a clause mints a sun, the aspect holding nothing, so a dial with anything on it is
     * the description.
     *
     * A bare `aurora` page with nothing leading it says neither, and is inert rather than wrong.
     */
    fun auroraIn(parts: AgeParts, seed: Long): Aurora? {
        val own = parts.optionsFor(Aspect.AURORA)
        val claim = Phenomena.claimFor(parts.optionsFor(Aspect.PHENOMENA, 0), Phenomenon.AURORA)
        if (claim == null && own.chosen.isEmpty()) return null
        // **The rung is how hard it comes**, which is the populative machinery doing the job it already
        // does: `teeming auroral` is more nights and a brighter curtain, and no dial had to be invented for
        // it. Ordinary is one, so a word that named no quantity changes nothing.
        val insistence = (claim?.density ?: Rung.ORDINARY) / Rung.ORDINARY
        // **Drawn where nothing said, rather than defaulted.** `auroral` names the phenomenon and nothing
        // else, so without this every undescribed curtain in every Age would be the same curtain. The seed
        // is where the bearing already came from, and an aurora nobody described should still differ
        // between Ages without a writer having to buy the difference.
        val size = own.steer(AURORASIZE, seed) ?: Sky.drawn(seed, SIZE_SALT, DRAWN_BAND)
        return Aurora(
            colours = rampIn(own),
            glow = (glowAt(own.steer(AURORAGLOW, seed) ?: Sky.drawn(seed, GLOW_SALT, Sky.DRAWN_GLOW)) * insistence)
                .toFloat().coerceIn(FAINTEST_CURTAIN, BRIGHTEST_CURTAIN),
            breadth = Sky.bandAt(size, NARROWEST_BAND, WIDEST_BAND),
            height = Sky.bandAt(size, SHORTEST_BAND, TALLEST_BAND),
            frequency = (nightsAt(own.steer(AURORAFREQUENCY, seed) ?: Sky.drawn(seed, NIGHTS_SALT, DRAWN_NIGHTS))
                * insistence).toFloat().coerceIn(RAREST_NIGHTS, EVERY_NIGHT),
            // Drawn rather than written: which way a band lies is a fact about this Age's sky and not
            // something §3.2 would put in front of a writer.
            bearingDegrees = XoroshiroRandomSource(seed xor AURORA_SALT).nextInt(WHOLE_COMPASS).toFloat(),
            // **Where the snow lies unless a writer says otherwise**, and vanilla's own line between snow
            // and rain is a boundary every player has already learned by walking over it. It is a ceiling
            // rather than a rule now, which is what lets a word ask for curtains over temperate ground —
            // see [AURORAWARMTH] for why that coupling was worth loosening.
            warmestGround = warmthAt(own.steer(AURORAWARMTH, seed) ?: WHERE_THE_SNOW_LIES),
            seed = seed,
        )
    }

    /**
     * [AURORAWARMTH] read as a temperature ceiling — nought is vanilla's snow line, and either end runs to
     * where no biome is left to exclude or include.
     */
    private fun warmthAt(steered: Double): Float = when {
        steered <= 0.0 -> (Aurora.SNOW_LINE + (Aurora.SNOW_LINE - COLDEST_GROUND) * steered).toFloat()
        else -> (Aurora.SNOW_LINE + (WARMEST_GROUND - Aurora.SNOW_LINE) * steered).toFloat()
    }

    /**
     * The colours the curtain burns, **crown first and in the order they were written**, or the ordinary
     * ramp where nobody named one.
     *
     * [Options.allOf] rather than [Options.of] is the whole of the difference from a sun's colour: a sun is
     * one tint and this is a ramp, and the mingling that puts several values on one dial is the same
     * mechanism that puts two rocks in one wall. What keeps them in the writer's order is
     * [Parameter.keepsWrittenOrder] on [AURORACOLOUR].
     *
     * **Nothing sorts them into what nature does.** Violet crowning red is a sky nobody has seen and a
     * writer is welcome to it; the realism lives in the default, which is where a default belongs.
     */
    private fun rampIn(own: Options): List<Rgba> {
        val named = own.allOf(AURORACOLOUR)
            .filter { it != Parameter.DEFAULT }
            .mapNotNull { Colour.named(it)?.saturated(A_CURTAIN_IS_LOOKED_AT) }
        return named.ifEmpty { Aurora.ORDINARY_RAMP }
    }

    /**
     * How far a named colour is pushed from its own grey before a curtain burns it.
     *
     * The same argument as a sun's own colour and a little harder: an aurora is drawn additively against
     * a night sky, where a washed-out tint reads as fog rather than as light.
     */
    private const val A_CURTAIN_IS_LOOKED_AT = 1.8f

    /** The band [AURORAGLOW] runs over, against an ordinary curtain. */
    private const val FAINTEST_CURTAIN = 0.35f
    private const val BRIGHTEST_CURTAIN = 2.0f

    /** The band [AURORAFREQUENCY] runs over. Never every night by default, and never truly never. */
    private const val RAREST_NIGHTS = 0.08f
    private const val EVERY_NIGHT = 1.0f

    /**
     * Where [AURORAWARMTH] sits when nothing said — vanilla's snow line, so an undescribed curtain
     * stands exactly where it always did.
     */
    private const val WHERE_THE_SNOW_LIES = 0.0

    /** The band [AURORAWARMTH] runs over: deep ice at one end, and past any biome vanilla has at the other. */
    private const val COLDEST_GROUND = -0.5f
    private const val WARMEST_GROUND = 2.5f

    /** How much of the sky [AURORASIZE] reaches, either way. */
    private const val NARROWEST_BAND = 0.3f
    private const val WIDEST_BAND = 1.0f
    // The renderer will not draw a stubby curtain — a short one reads as a ribbon rather than as a
    // modest aurora — so the axis runs above that floor rather than half into it.
    private const val SHORTEST_BAND = 0.5f
    private const val TALLEST_BAND = 1.0f

    private const val AURORA_SALT = 0x0A17_0BA5L

    /** Every whole degree of it, a band being wide enough that a finer bearing says nothing. */
    private const val WHOLE_COMPASS = 360

    private fun glowAt(brilliance: Double): Double =
        FAINTEST_CURTAIN + Span.NATURAL.fractionOf(brilliance) * (BRIGHTEST_CURTAIN - FAINTEST_CURTAIN)

    private fun nightsAt(often: Double): Double =
        RAREST_NIGHTS + Span.NATURAL.fractionOf(often) * (EVERY_NIGHT - RAREST_NIGHTS)

    /**
     * The bands an unsaid axis is drawn from, in [Span.NATURAL]'s own terms — see [Sky.DRAWN_GLOW], which
     * is the third of them and is shared with a bow.
     *
     * Narrower than what a word can ask for, and deliberately: the far ends of each axis are what a
     * writer *buys*, so an Age that said nothing should never land somewhere `brilliant` could have
     * taken it.
     */
    private val DRAWN_BAND = Span(-0.3, 0.5)
    private val DRAWN_NIGHTS = Span(-0.6, 0.1)

    private const val GLOW_SALT = 0x0A17_60L
    private const val SIZE_SALT = 0x0A17_512EL
    private const val NIGHTS_SALT = 0x0A17_1416L
}
