package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.SizeScale
import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.CelestialPath
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.SkySpec
import kotlin.math.roundToInt

/**
 * The bodies overhead — the suns, the moons and the star field — and what a clause may say about
 * one.
 *
 * Their own object rather than a stretch of [Sky] because each is its own aspect ([Aspect.SUN],
 * [Aspect.MOON], [Aspect.STARS]) where [Sky] is the vault they hang in.
 */
object SkyBodies {

    /**
     * Whether the body a clause is about is **not there** — one parameter on the sun and on the moon.
     *
     * It was two, `shining` and `orbiting`, kept apart by a rule that no longer exists and named for
     * what a body *does* rather than for what is being said about it. `sun.absent` and `moon.absent`
     * read as what a writer means (Jonah, 2026-08-31), and a word says which body it means by naming
     * the aspect in the parameter — without that the two would be one word, since `Word.reaching` only ever
     * widens.
     *
     * **Only the first body can be absent, which is a quirk of how a sky is written rather than of
     * this.** Every other sun is minted by a clause describing one, so there is no clause to say a
     * body that was never minted is missing; this is the default body's own switch.
     */
    val ABSENT = Parameter.flag("absent", help = "Whether this body is missing from the sky altogether.")

    /** How thick the stars lie: none at the bottom of the axis, [DENSEST_STARS] times vanilla's at the top. */
    val STARS = Parameter.ranged(
        "density",
        help = "How thickly the stars lie.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "none at all"),
            Parameter.Landmark(-0.3333, "vanilla's", isVanilla = true),
            Parameter.Landmark(0.3333, "twice vanilla's"),
            Parameter.Landmark(1.0, "three times"),
        ),
    )

    /**
     * How brightly the stars burn, against vanilla's own — [BRIGHTEST_STARS] times it at the top.
     *
     * A second axis on one field because brilliance and number are different facts about it: `starlit`
     * says there are more of them and this says the ones there are blaze, and a sky may be either
     * without being the other.
     *
     * **Never fainter than vanilla's**: a sky with less light in it overhead is a *sparser* one, and that
     * is [STARS] to say.
     */
    val STARGLOW = Parameter.ranged(
        "glow",
        help = "How brightly the stars burn.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "vanilla's", isVanilla = true),
            Parameter.Landmark(0.0, "half again"),
            Parameter.Landmark(1.0, "twice as bright"),
        ),
    )

    /**
     * How large the suns are, against vanilla's — [LARGEST_SUN] times it at the top of the axis.
     *
     * **The renderer could always draw this and nothing could say it.** `Appearance.Sprite` has carried
     * an `angularSize` since the sky was built, and [SkySpec.drawn] already varied it for the *extra*
     * suns; what was missing was a writer's way to ask, and a way for the ask to reach the first one.
     */
    val SUNSIZE = Parameter.ranged(
        "size",
        help = "How large the suns are.",
        // [SizeScale]'s steps, as every other size: vanilla's in the middle, a quarter to four times it.
        landmarks = listOf(
                Parameter.Landmark(-1.0, "a quarter"),
                Parameter.Landmark(-0.5, "half"),
                Parameter.Landmark(0.0, "vanilla's", isVanilla = true),
                Parameter.Landmark(0.5, "twice"),
                Parameter.Landmark(1.0, "four times"),
            ),
    )

    /**
     * What colour the suns burn, or [Parameter.DEFAULT] for vanilla's white.
     *
     * Reaches **every** sun including the first, which is what separates it from the tint
     * [SkySpec.drawn] already draws for the others: that one spreads a sky's extra bodies apart, and
     * this one is a statement about the star this world goes round.
     */
    val SUNCOLOUR = Sky.colour("colour", "The colour the suns burn.")

    /**
     * The compass, in the bearings `Orbit.risingAt` reads — due east is 90, which is where vanilla's
     * own sun comes up.
     *
     * Eight points and no more: the four cardinals and the four between them are what a person points
     * at, and a writer who wants a sun at 22.5° is asking a question §3.2 keeps away from them.
     */
    private val BEARINGS: Map<String, Float> = linkedMapOf(
        "north" to 0.0f,
        "northeast" to 45.0f,
        "east" to 90.0f,
        "southeast" to 135.0f,
        "south" to 180.0f,
        "southwest" to 225.0f,
        "west" to 270.0f,
        "northwest" to 315.0f,
    )

    /** The bearing [named] rises at, or null where nobody said — which leaves the path as it was. */
    fun bearingOf(named: String): Float? = BEARINGS[named]

    /**
     * Which horizon a body comes up over — the eight points of the compass, and the one thing about a
     * sun a writer is likeliest to want to say.
     *
     * Named rather than measured, which is §3.2 at its least arguable: "north-rising" is a thing a
     * person says about a sun where ninety degrees of ascending node is a fact about our arithmetic.
     * `Orbit.risingAt` does the conversion and owns the sign trap in it.
     *
     * **The sun's and the moon's alike**, which is one parameter owned by two aspects: the word means the
     * same thing about either body and the clause it was laid in picks which one it is about.
     */
    val RISING = Parameter(
        "rising",
        listOf(Parameter.DEFAULT) + BEARINGS.keys,
        help = "Which horizon this body rises over.",
    )

    /**
     * The shape of a body's path, or [Parameter.DEFAULT] for the one it was drawn with.
     *
     * **`polar`**: a circle held at one height just over the horizon, never setting and never climbing —
     * perpetual twilight under a sun, a tide held at one level under a moon, and the paper tree's window
     * (design §7.1.2). Its height is [POLAR_LIFT]; the design leaves where the circle sits open, and a second
     * value is how a writer would ask for it lower or higher.
     */
    val PATH = Parameter(
        "path",
        listOf(Parameter.DEFAULT, POLAR),
        help = "The shape of this body's path across the sky.",
    )

    /**
     * One body as **its own clause** described it, which is the whole point of minting (world model §2).
     *
     * `SkySpec.drawn` builds a sky out of what is true of all of them, which is everything a count could
     * ever say; this is the pass that lets the second sun be blue where the first is red. A body nobody
     * said anything about keeps exactly what was drawn, so an unremarkable sky stays vanilla's.
     */
    fun described(body: CelestialBody, own: Options, seed: Long): CelestialBody {
        val sized = own.steer(SUNSIZE, seed)?.let(::sunSizeAt)
        val tinted = Colour.named(own.of(SUNCOLOUR))?.saturated(SUN_IS_LOOKED_AT)
        val rising = bearingOf(own.of(RISING))
        val sprite = body.appearance as? Appearance.Sprite
        val appearance = when {
            sprite == null || (sized == null && tinted == null) -> body.appearance
            else -> sprite.copy(tint = tinted ?: sprite.tint, angularSize = sized ?: sprite.angularSize)
        }
        // Aimed from the path it already has, so a spare body keeps the wander the draw gave it and only
        // the horizon it comes up over moves.
        val risen = rising?.let { Orbit.risingAt(it, body.path as? Orbit ?: Orbit.VANILLA_SUN) } ?: body.path
        // A polar body circles the horizon, so which horizon it rises over has nothing left to say.
        val path = if (own.of(PATH) == POLAR) polarPathFrom(body.path) else risen
        return body.copy(appearance = appearance, path = path)
    }

    /**
     * A circle round the pole at [POLAR_LIFT], keeping the body's own period, phase and distance — so a
     * polar sun still goes round once a day and a polar moon keeps its month.
     */
    private fun polarPathFrom(path: CelestialPath): Orbit {
        val drawn = path as? Orbit ?: Orbit.VANILLA_SUN.copy(distance = path.distance)
        return drawn.copy(inclinationDegrees = POLE, liftDegrees = POLAR_LIFT)
    }

    private const val POLAR = "polar"

    /** An inclination that stands the circle on the horizon's own plane. */
    private const val POLE = 90.0f

    /**
     * Two degrees over the horizon: the light a sun there casts is about thirteen of fifteen
     * (`LevelDaylight`'s ramp), dim and steady, and never night.
     */
    private const val POLAR_LIFT = 2.0f

    private const val DENSEST_STARS = 3

    /** Vanilla's own star count, which is a third of the way up the axis. */
    private const val ORDINARY_STARS = 1.0 / DENSEST_STARS

    /**
     * How many times vanilla's own the top of [STARGLOW] burns at.
     *
     * Twice is *four* times the light — the sky pass adds what it draws scaled by its own alpha — so
     * this reaches a good deal further than the number suggests.
     */
    private const val BRIGHTEST_STARS = 2.0

    fun starsAt(density: Double?): Int {
        val fraction = density?.let(Span.NATURAL::fractionOf) ?: ORDINARY_STARS
        return (fraction * DENSEST_STARS * SkySpec.VANILLA_STAR_COUNT).roundToInt()
    }

    /**
     * Vanilla's own brilliance where the axis is unsaid, so an ordinary sky's stars are vanilla's
     * exactly — which is the whole reason this became a word rather than staying the default.
     */
    fun starGlowAt(brilliance: Double?): Float {
        val fraction = brilliance?.let(Span.NATURAL::fractionOf) ?: return SkySpec.ORDINARY_STAR_GLOW
        return SkySpec.ORDINARY_STAR_GLOW * (1.0 + fraction * (BRIGHTEST_STARS - 1.0)).toFloat()
    }

    /**
     * Vanilla's sun where the axis is unsaid, so an ordinary sky is untouched — which `SkyCheck` holds.
     *
     * On [SizeScale]'s steps like every other size (Jonah, 2026-09-29): vanilla's at the middle of the axis,
     * four times it at the top, a quarter at the bottom. The bottom used to be vanilla's own, so no sun could
     * be smaller than the overworld's.
     */
    private fun sunSizeAt(largeness: Double?): Float =
        (SkySpec.VANILLA_SUN_SIZE * SizeScale.factorAt(largeness)).toFloat()

    /**
     * How far a named colour is pushed from its own grey before a sun wears it (Jonah, 2026-08-08).
     *
     * `Colour`'s palette was chosen for things the eye looks *past* — a sky, a fog, a tint on light —
     * so its red is a soft one. On a sun it read as a tint laid over white rather than as the colour
     * the star burns, which is the one thing a sun's colour has to say.
     */
    private const val SUN_IS_LOOKED_AT = 1.5f
}
