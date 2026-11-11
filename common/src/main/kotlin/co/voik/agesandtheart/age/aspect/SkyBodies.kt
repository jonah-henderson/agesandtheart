package co.voik.agesandtheart.age.aspect

import net.minecraft.world.level.MoonPhase
import co.voik.agesandtheart.worldgen.SizeScale
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.CelestialPath
import co.voik.ephemeris.sky.Corona
import co.voik.ephemeris.sky.Ellipse
import co.voik.ephemeris.sky.Held
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.Palette
import co.voik.ephemeris.sky.PhaseCycle
import co.voik.ephemeris.sky.SkySpec
import co.voik.ephemeris.sky.StarField
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

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

    /**
     * Where [multiple] times vanilla's stars stands on [STARS], whose landmarks are linear in it: none at -1,
     * vanilla's at -1/3, twice at 1/3, three times at 1 — and no further, so `teeming` is the top.
     */
    fun starsAt(multiple: Double): Double = ((2.0 * multiple - 3.0) / 3.0).coerceIn(-1.0, 1.0)

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
     * How brightly the stars burn, against vanilla's own — [FAINTEST_STARS] of it at the bottom, vanilla's in the
     * middle, [BRIGHTEST_STARS] times it at the top. Ages written before generator 23 are carried onto this axis from
     * one that began at vanilla's ([co.voik.agesandtheart.age.FormerStarGlow]).
     *
     * A second axis on one field because brilliance and number are different facts about it: `starlit`
     * says there are more of them and this says the ones there are blaze, and a sky may be either
     * without being the other.
     */
    val STARGLOW = Parameter.ranged(
        "glow",
        help = "How brightly the stars burn.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a quarter as bright"),
            Parameter.Landmark(0.0, "vanilla's", isVanilla = true),
            Parameter.Landmark(1.0, "twice as bright"),
        ),
    )

    /** How fast the stars wheel against vanilla's, which turn with the day — `slow stars`, `swift stars`. */
    val STARPACE = Parameter.ranged(
        "pace",
        help = "How fast the stars wheel across the sky.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a quarter as fast"),
            Parameter.Landmark(0.0, "vanilla's, with the day", isVanilla = true),
            Parameter.Landmark(1.0, "four times as fast"),
        ),
    )

    /** Whether the stars hang where they are while the sun crosses them — `motionless stars`. */
    val STARS_STILL = Parameter.flag("still", help = "Whether the stars stand still.").onlyWhenAimed()

    /** How large each star is: half its own size at the bottom, twice it at the top. */
    val STARSIZE = Parameter.ranged(
        "size",
        help = "How large each star is.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "half"),
            Parameter.Landmark(0.0, "vanilla's", isVanilla = true),
            Parameter.Landmark(1.0, "twice"),
        ),
    )

    /** [stars] wheeling and sized as [said] asks, or as they were where it says nothing. */
    fun starsMovedAndSized(stars: StarField, said: Options, seed: Long): StarField {
        val pace = when {
            said.isTrue(STARS_STILL) -> STARS_HELD
            else -> said.steer(STARPACE, seed)?.let { STAR_PACE_RANGE.pow(it).toFloat() } ?: StarField.VANILLAS_PACE
        }
        val size = said.steer(STARSIZE, seed)?.let { STAR_SIZE_RANGE.pow(it).toFloat() } ?: StarField.VANILLAS_SIZE
        return stars.copy(pace = pace, size = size)
    }

    private const val STARS_HELD = 0.0f
    private const val STAR_PACE_RANGE = 4.0
    private const val STAR_SIZE_RANGE = 2.0

    /** What colour the stars burn — `red stars` — or [Parameter.DEFAULT] for their own whites. */
    val STARCOLOUR = Sky.colour("colour", "The colour the stars burn.")

    /**
     * How large a sun or a moon is, against vanilla's own of its kind.
     *
     * **The renderer could always draw this and nothing could say it.** `Appearance.Sprite` has carried
     * an `angularSize` since the sky was built, and [SkySpec.drawn] already varied it for the *extra*
     * suns; what was missing was a writer's way to ask, and a way for the ask to reach the first one.
     */
    val BODYSIZE = Parameter.ranged(
        "size",
        help = "How large this body is.",
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
     * How strongly a body shines — `weak sun`, `strong sun`, `bright moon`. Below vanilla's a sun holds the day toward
     * night while it is up, the stars coming out and monsters walking at the far end (Ephemeris'
     * `CelestialBody.dimming`). Vanilla's light is already the most the game gives, so above it a sun glares instead: a
     * halo, a glare over the view facing it, and light lingering after it sets (`CelestialBody.glare`). A moon takes
     * a wider halo and, full and high, lifts the night toward where monsters keep out of the open
     * (`LevelDaylight.moonlit`); its disc dims below vanilla's.
     */
    val BODYLIGHT = Parameter.ranged(
        "light",
        help = "How strongly this body shines.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "as dark as night at noon"),
            Parameter.Landmark(-0.3, "a dusky noon"),
            Parameter.Landmark(0.0, "vanilla's", isVanilla = true),
            Parameter.Landmark(1.0, "glaring, its light lingering long after it sets"),
        ),
    )

    /** How far toward night a sun of [light] holds the day: none at or above vanilla's. */
    fun dimmingAt(light: Double): Float = (-light).coerceIn(0.0, 1.0).toFloat()

    /** How much a sun of [light] glares: none at or below vanilla's. */
    fun glareAt(light: Double): Float = light.coerceIn(0.0, 1.0).toFloat()

    /** What colour the moons shine — `red moon` — or [Parameter.DEFAULT] for vanilla's white. */
    val MOONCOLOUR = Sky.colour("colour", "The colour the moons shine.")

    /** The bearing across the sky from [bearing]: where a body setting there rose. */
    private fun oppositeOf(bearing: Float): Float = (bearing + HALF_TURN_DEGREES) % FULL_TURN_DEGREES

    /** The bearing [named] rises at, or null where nobody said — which leaves the path as it was. */
    fun bearingOf(named: String): Float? = Compass.bearingOf(named)

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
        listOf(Parameter.DEFAULT) + Compass.POINTS.keys,
        help = "Which horizon this body rises over.",
    )

    /** Which horizon this body sets under, said as [RISING] is: `west setting sun`, `north rising east setting moon`. */
    val SETTING = Parameter(
        "setting",
        listOf(Parameter.DEFAULT) + Compass.POINTS.keys,
        help = "Which horizon this body sets under.",
    )

    /**
     * **How hard a moon pulls the sea** — nothing where unsaid, so a moon raises no tide unless a word says
     * it does (design §7.1.2, `tidal`). Every pulling moon's pull adds to how far the tide reaches, and
     * where the moons stand sets how high it is (`Tide`).
     */
    val PULL = Parameter.ranged(
        "pull",
        help = "How hard this moon pulls the sea: a tide wherever there is one.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "none", isVanilla = true),
            Parameter.Landmark(0.0, "a tide"),
            Parameter.Landmark(1.0, "the strongest"),
        ),
    )

    /**
     * The shape of a body's path, or [Parameter.DEFAULT] for the one it was drawn with.
     *
     * **`polar`**: a circle held at one height just over the horizon, never setting and never climbing —
     * perpetual twilight under a sun, a tide held at one level under a moon, and the paper tree's window
     * (design §7.1.2). Its height is [POLAR_LIFT]; the design leaves where the circle sits open, and a second
     * value is how a writer would ask for it lower or higher.
     *
     * **`eclipsing`**: a moon on its sun's own path, crossing it once a month at noon ([EclipsingMoons]). Left
     * alone here and laid in a second pass, since it is built from the sun's finished path.
     */
    /**
     * Whether this body has stopped where it stood when the Age's day began — `motionless sun`, a world it keeps one
     * face to. `still`, the name a rooted creature shares, so `motionless` says both. The Age's clock runs on
     * regardless (Ephemeris' `ClockPace`): it keeps time by the largest body still moving, at the overworld's pace
     * where none is, and the light is lit as the hour a still sun's place says.
     */
    val STILL = Parameter.flag("still", help = "Whether this body stands still in the sky.").onlyWhenAimed()

    /** Whether a moon is held at new, showing no light all month — `lightless moon`. */
    val UNLIT = Parameter.flag("unlit", help = "Whether this moon stays new, showing no light.").onlyWhenAimed()

    /**
     * How long this body takes over its path against vanilla's — `slow sun` is a longer day, `swift moon` a quicker
     * month. `pace`, the name the rain and the creatures share. The body the clock keeps time by sets the Age's day
     * ([STILL]'s note).
     */
    val BODYPACE = Parameter.ranged(
        "pace",
        help = "How fast this body crosses the sky.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "twice as long"),
            Parameter.Landmark(0.0, "its own pace", isVanilla = true),
            Parameter.Landmark(1.0, "half as long"),
        ),
    )

    /**
     * The tick of its day an Age's clock starts at, drawn from its [seed] — so linking in can land at dusk where it
     * is noon at home — and where a still body stands.
     */
    fun dayBeginsAt(seed: Long): Long = Random(seed xor DAY_BEGINS_SALT).nextLong(Orbit.TICKS_PER_VANILLA_DAY.toLong())

    /** How many times as long a body of [pace] takes over its path: twice at the slowest, half at the quickest. */
    fun lengthAt(pace: Double): Double = if (pace < 0.0) 1.0 - pace else 1.0 / (1.0 + pace)

    private const val DAY_BEGINS_SALT = 0x434C_4F43_4B53_4146L

    val PATH = Parameter(
        "path",
        listOf(Parameter.DEFAULT, POLAR, OVERHEAD, EclipsingMoons.ECLIPSING),
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
        val isAMoon = body.phase != null
        val sized = own.steer(BODYSIZE, seed)?.let { sizeAt(it, isAMoon) }
        val tinted = Colour.named(own.of(if (isAMoon) MOONCOLOUR else SUNCOLOUR))?.saturated(SUN_IS_LOOKED_AT)
        // Vanilla's moons are not its sun's sprite, so a sun's palette means nothing on one.
        val palette = SUN_PALETTES[own.of(SUNCOLOUR)]?.takeUnless { isAMoon }
        val corona = SUN_CORONAS[own.of(SUNCOLOUR)]?.takeUnless { isAMoon }
        val writtenRising = bearingOf(own.of(RISING))
        val setting = bearingOf(own.of(SETTING))
        val rising = writtenRising ?: setting?.let(::oppositeOf)
        val sprite = body.appearance as? Appearance.Sprite
        val appearance = when {
            sprite == null || (sized == null && tinted == null) -> body.appearance
            else -> sprite.copy(
                tint = tinted ?: sprite.tint,
                angularSize = sized ?: sprite.angularSize,
                palette = palette ?: sprite.palette,
                corona = corona ?: sprite.corona,
            )
        }
        // Aimed from the path it already has, so a spare body keeps the wander the draw gave it and only
        // the horizon it comes up over moves.
        val drawn = body.path as? Orbit ?: Orbit.VANILLA_SUN
        // Rising and setting written apart, not opposite: the body comes up over one and goes down under the other.
        val throughBoth = if (writtenRising != null && setting != null && setting != oppositeOf(writtenRising)) {
            Ellipse(writtenRising, setting, drawn.periodTicks, drawn.phaseDegrees, body.path.distance, drawn.pacing)
        } else {
            null
        }
        val risen = throughBoth ?: rising?.let { Orbit.risingAt(it, drawn) } ?: body.path
        // A polar body circles the horizon, so which horizon it rises over has nothing left to say.
        val shaped = when (own.of(PATH)) {
            POLAR -> polarPathFrom(body.path)
            OVERHEAD -> if (risen is Ellipse) risen else overheadPathFrom(risen)
            else -> risen
        }
        val length = own.steer(BODYPACE, seed)?.let(::lengthAt)
        val paced = length?.let(shaped::paced) ?: shaped
        val path = if (own.isTrue(STILL)) Held(paced, dayBeginsAt(seed)) else paced
        // A moon's month goes at its pace too, so a swift moon waxes and wanes as quickly as it crosses.
        fun pacedMonth(cycle: PhaseCycle) = length?.let { cycle.copy(periodTicks = (cycle.periodTicks * it).roundToInt().coerceAtLeast(1)) } ?: cycle
        val unlit = isAMoon && own.isTrue(UNLIT)
        val phase = body.phase?.let(::pacedMonth)?.let { if (unlit) it.copy(steps = 1) else it }
        val shown = if (unlit) heldNew(appearance) else appearance
        val light = own.steer(BODYLIGHT, seed)
        val dimming = light?.let(::dimmingAt) ?: body.dimming
        val glare = light?.let(::glareAt) ?: body.glare
        return body.copy(appearance = shown, path = path, phase = phase, dimming = dimming, glare = glare)
    }

    /** [appearance] showing only a new moon's shape, so a phase of one step holds it there. */
    private fun heldNew(appearance: Appearance): Appearance {
        val sprite = appearance as? Appearance.Sprite ?: return appearance
        return sprite.copy(shapes = listOf(Appearance.MOON_SHAPES[MoonPhase.NEW_MOON.index()]))
    }

    /**
     * A circle round the pole at [POLAR_LIFT], keeping the body's own period, phase and distance — so a
     * polar sun still goes round once a day and a polar moon keeps its month.
     */
    private fun polarPathFrom(path: CelestialPath): Orbit {
        val drawn = path as? Orbit ?: Orbit.VANILLA_SUN.copy(distance = path.distance)
        return drawn.copy(inclinationDegrees = POLE, liftDegrees = POLAR_LIFT)
    }

    /**
     * A great circle through the zenith, as vanilla's sun sweeps — `high sun` — keeping the body's own rising, period,
     * phase and distance, so a spare body the draw tilted climbs overhead after all.
     */
    private fun overheadPathFrom(path: CelestialPath): Orbit {
        val drawn = path as? Orbit ?: Orbit.VANILLA_SUN.copy(distance = path.distance)
        return drawn.copy(inclinationDegrees = THROUGH_THE_ZENITH, liftDegrees = ON_THE_GREAT_CIRCLE)
    }

    private const val HALF_TURN_DEGREES = 180.0f
    private const val FULL_TURN_DEGREES = 360.0f
    private const val POLAR = "polar"
    private const val OVERHEAD = "overhead"

    /** An inclination that keeps the circle upright through the zenith, vanilla's own. */
    private const val THROUGH_THE_ZENITH = 0.0f

    /** No lift: a great circle, half above the horizon and half below. */
    private const val ON_THE_GREAT_CIRCLE = 0.0f

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

    /** How much of vanilla's own the bottom of [STARGLOW] burns at. */
    private const val FAINTEST_STARS = 0.25

    fun starsAt(density: Double?): Int {
        val fraction = density?.let(Span.NATURAL::fractionOf) ?: ORDINARY_STARS
        return (fraction * DENSEST_STARS * SkySpec.VANILLA_STAR_COUNT).roundToInt()
    }

    /**
     * Vanilla's own brilliance where the axis is unsaid, so an ordinary sky's stars are vanilla's
     * exactly — which is the whole reason this became a word rather than staying the default.
     */
    fun starGlowAt(brilliance: Double?): Float {
        val held = brilliance?.coerceIn(-1.0, 1.0) ?: return SkySpec.ORDINARY_STAR_GLOW
        val multiple = if (held >= 0.0) 1.0 + held * (BRIGHTEST_STARS - 1.0) else 1.0 + held * (1.0 - FAINTEST_STARS)
        return SkySpec.ORDINARY_STAR_GLOW * multiple.toFloat()
    }

    /**
     * The colour every star's light is multiplied by where [said] names one, or null for their own whites. A colour
     * on the spectrum is pushed as a sun's is and burns at full in its brightest channel, since a multiplied star
     * can only lose light; white, grey and black are as bright as they are, black held at [DIMMEST_STARS] because
     * a star added to the sky cannot be darker than it.
     */
    fun starTintOf(said: Options): Rgba? {
        val named = said.of(STARCOLOUR)
        // Stars whose colours turn and were given none start from the vividest there is, as `red stars` would be, so
        // the turn carries them round the whole wheel rather than tinging white.
        val turning = said.isTrue(Prism.COLOURS_CYCLING)
        val colour = Colour.named(named) ?: (if (turning) Colour.named(TURNING_STARS_START) else null) ?: return null
        if (named in WITHOUT_A_HUE) return colour.brightenedTo(DIMMEST_STARS)
        val pushed = colour.saturated(SUN_IS_LOOKED_AT)
        return pushed.dimmed(FULL_CHANNEL / brightestOf(pushed))
    }

    private fun brightestOf(colour: Rgba): Float = maxOf(colour.red, colour.green, colour.blue)

    private fun Rgba.brightenedTo(least: Float): Rgba {
        val brightest = brightestOf(this)
        return if (brightest >= least) this else dimmed(least / brightest)
    }

    private val WITHOUT_A_HUE = setOf("white", "grey", "black")

    /** Where turning stars given no colour start the wheel. */
    private const val TURNING_STARS_START = "red"
    private const val FULL_CHANNEL = 1.0f

    /** How bright black stars burn in their brightest channel: faint, but still there. */
    private const val DIMMEST_STARS = 0.3f

    /**
     * A body [largeness] large, against vanilla's own of its kind — the sun's or the moon's.
     *
     * On [SizeScale]'s steps like every other size (Jonah, 2026-09-29): vanilla's at the middle of the axis,
     * four times it at the top, a quarter at the bottom. The bottom used to be vanilla's own, so no sun could
     * be smaller than the overworld's.
     */
    private fun sizeAt(largeness: Double, isAMoon: Boolean): Float {
        val vanilla = if (isAMoon) SkySpec.VANILLA_MOON_SIZE else SkySpec.VANILLA_SUN_SIZE
        return (vanilla * SizeScale.factorAt(largeness)).toFloat()
    }

    /** How hard a moon described [own] pulls the sea, nought to one — nought where nothing said so. */
    fun pullOf(own: Options, seed: Long): Double =
        own.steer(PULL, seed)?.let(Span.NATURAL::fractionOf) ?: NO_PULL

    private const val NO_PULL = 0.0

    /**
     * How far a named colour is pushed from its own grey before a sun wears it (Jonah, 2026-08-08).
     *
     * `Colour`'s palette was chosen for things the eye looks *past* — a sky, a fog, a tint on light —
     * so its red is a soft one. On a sun it read as a tint laid over white rather than as the colour
     * the star burns, which is the one thing a sun's colour has to say.
     */
    private const val SUN_IS_LOOKED_AT = 1.5f

    /**
     * The colours a sun is drawn in where a tint cannot say it — **black**, since a sun is added to the sky
     * and adding a dark colour adds next to nothing, which drew a black sun as a faint yellow one.
     *
     * An eclipse: a black disc that hides the sky, a thin bright ring at its rim, and in vanilla's glow a
     * pale violet corona of light, against the dusky sky [SunLight] gives it.
     */
    private val SUN_PALETTES: Map<String, Palette> = mapOf(
        "black" to Palette.ofVanillaSun(
            centre = Rgba(0.0f, 0.0f, 0.0f, HIDES_THE_SKY),
            ring = Rgba(0.0f, 0.0f, 0.0f, HIDES_THE_SKY),
            rim = Rgba(0.95f, 0.88f, 0.75f, HIDES_THE_SKY),
            glow = Rgba(0.40f, 0.33f, 0.58f, HIDES_A_LITTLE_OF_THE_SKY),
        ),
    )

    /** A shimmer in a black sun's glow, breathing slowly. No streamers. */
    private val SUN_CORONAS: Map<String, Corona> = mapOf(
        "black" to Corona(
            rays = 0,
            reach = 0.0f,
            colour = Rgba.CLEAR,
            turnsPerDay = 0.0f,
            shimmer = 0.35f,
        ),
    )

    private const val HIDES_THE_SKY = 1.0f
    private const val HIDES_A_LITTLE_OF_THE_SKY = 0.2f
}
