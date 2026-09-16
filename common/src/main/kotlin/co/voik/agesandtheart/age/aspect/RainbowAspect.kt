package co.voik.agesandtheart.age.aspect

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Rainbow

/**
 * The bow an Age wears: what a writer may say about one, and what is drawn where they said nothing.
 *
 * Its own object rather than a stretch of [Sky] because a rainbow is its own aspect
 * ([Aspect.RAINBOW]) and shares nothing with a curtain but the arithmetic in [Sky.bandAt] and
 * [Sky.drawn].
 */
object RainbowAspect {

    /**
     * The colours the bow burns, **outermost first** — red at the outside, as a real one is.
     *
     * The same mechanism as [AuroraAspect.AURORACOLOUR] and for the same reason: a bow is a band rather
     * than a tint, and which end is the outside is the one thing about it a writer states outright. The
     * second bow reverses this, so naming one band names both.
     */
    val RAINBOWCOLOUR = Sky.colour("colour", "The colours of the arc.").copy(keepsWrittenOrder = true)

    /** How brightly the bow burns, against an ordinary one. */
    val RAINBOWGLOW = Parameter.ranged("glow", help = "How brightly the rainbow burns.")

    /**
     * How wide the arc stands from the point opposite its light.
     *
     * **A fact about what the light is bending through, not about the sky** — ours is forty-two degrees
     * because that is what water does, and an Age whose rain is not water has every right to another.
     *
     * It is also how much of the day a bow survives, with nothing else said: the crown stands at this
     * less the light's own height, so a wide arc is still up under a sun that has already sunk a narrow
     * one below the ground.
     */
    val RAINBOWSIZE = Parameter.ranged(
        "size",
        help = "How wide the arc stands.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a tight arc"),
            Parameter.Landmark(0.0, "an ordinary bow"),
            Parameter.Landmark(1.0, "a wide sweep"),
        ),
    )

    /** What share of days it comes at all. */
    val RAINBOWFREQUENCY = Parameter.ranged("frequency", help = "What share of days a rainbow comes at all.")

    /**
     * How much falling water it needs — the top of the axis will not come without rain, the bottom
     * never wanted any.
     *
     * The dial that lets an Age hang arcs in a dry clear sky, and a thing to say on purpose rather than
     * a default to fall into: left alone, a bow is sunlight bent through rain and waits for some.
     */
    val RAINBOWRAIN = Parameter.ranged("rain", help = "How much rain a rainbow needs before it will appear.")

    /**
     * The bow this Age wears, or null where nothing asked for one.
     *
     * **The aurora's two ways in, for the aurora's reason.** A writer may *name* the phenomenon
     * (`rainbows`) or *describe* it (`red and yellow rainbow`), and either is having said the Age has them.
     * A bare `rainbow` page with nothing leading it says neither, and is inert rather than wrong.
     *
     * **Where it stands is not decided here and could not be.** A bow is a circle about the point opposite
     * whatever lights it, so it is placed by the Age's own suns at the instant it is drawn — which is why
     * there is no bearing to draw from the seed as an aurora needs.
     */
    fun rainbowIn(parts: AgeParts, seed: Long): Rainbow? {
        val own = parts.optionsFor(Aspect.RAINBOW)
        val claim = Phenomena.claimFor(parts.optionsFor(Aspect.PHENOMENA, 0), Phenomenon.RAINBOW)
        if (claim == null && own.chosen.isEmpty()) return null
        // The rung is how hard it comes, as it is for a curtain: `teeming rainbows` is more days and a
        // brighter bow, off the populative machinery rather than a dial invented for it.
        val insistence = (claim?.density ?: Rung.ORDINARY) / Rung.ORDINARY
        return Rainbow(
            colours = bandIn(own),
            glow = (Sky.bandAt(own.steer(RAINBOWGLOW, seed) ?: Sky.drawn(seed, BOW_GLOW_SALT, Sky.DRAWN_GLOW),
                FAINTEST_BOW, BRIGHTEST_BOW) * insistence)
                .toFloat().coerceIn(FAINTEST_BOW, BRIGHTEST_BOW),
            // **Not drawn from the seed, where a curtain's size is** — and the difference is the point. How
            // large a curtain hangs is a fact about the display, so an Age nobody described should still
            // differ. How wide a bow stands is a fact about what its light is bending *through*, and an
            // Age's rain is water until a writer says otherwise.
            radiusDegrees = own.steer(RAINBOWSIZE, seed)
                ?.let { Sky.bandAt(it, NARROWEST_BOW, WIDEST_BOW) } ?: Rainbow.WATERS_OWN,
            needsRain = own.steer(RAINBOWRAIN, seed)
                ?.let { Sky.bandAt(it, NO_RAIN_AT_ALL, ALL_THE_RAIN) } ?: Rainbow.ORDINARY_RAIN_NEEDED,
            frequency = (Sky.bandAt(
                own.steer(RAINBOWFREQUENCY, seed) ?: Sky.drawn(seed, BOW_DAYS_SALT, DRAWN_BOW_DAYS),
                RAREST_DAYS, EVERY_DAY,
            ) * insistence)
                .toFloat().coerceIn(RAREST_DAYS, EVERY_DAY),
            seed = seed,
        )
    }

    /**
     * The colours the bow burns, **outermost first and in the order they were written**, or the ordinary
     * spectrum where nobody named one.
     *
     * [Options.allOf] rather than [Options.of] for the ramp's reason, and [Parameter.keepsWrittenOrder] on
     * [RAINBOWCOLOUR] is what keeps red on the outside. The second bow reverses whatever this answers, so a
     * writer names one band and gets both.
     */
    private fun bandIn(own: Options): List<Rgba> {
        val named = own.allOf(RAINBOWCOLOUR)
            .filter { it != Parameter.DEFAULT }
            .mapNotNull { Colour.named(it)?.saturated(A_BOW_IS_ADDED) }
        return named.ifEmpty { Rainbow.ORDINARY_SPECTRUM }
    }

    /**
     * How far a named colour is pushed off grey for a bow — **much less than a curtain's**.
     *
     * A bow is added to the sky rather than laid over it, so a saturated band against a bright day
     * drives its strongest channel to one and comes out white. Pale is both what the blending can
     * render and what a real bow looks like.
     */
    private const val A_BOW_IS_ADDED = 0.4f

    private const val FAINTEST_BOW = 0.4f
    private const val BRIGHTEST_BOW = 1.8f

    /** Rare for a bow is still often: the geometry and the weather are already most of the rarity. */
    private const val RAREST_DAYS = 0.2f
    private const val EVERY_DAY = 1.0f

    /** How far either way a writer may bend an Age's rain, about [Rainbow.WATERS_OWN] in the middle. */
    private const val NARROWEST_BOW = 24.0f
    private const val WIDEST_BOW = 66.0f

    private const val NO_RAIN_AT_ALL = 0.0f
    private const val ALL_THE_RAIN = 1.0f

    /** Leans frequent, the geometry having already ruled out the middle of every day. */
    private val DRAWN_BOW_DAYS = Span(-0.1, 0.5)

    private const val BOW_GLOW_SALT = 0xB0_0060L
    private const val BOW_DAYS_SALT = 0xB0_0DA75L
}
