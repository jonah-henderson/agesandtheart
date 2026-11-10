package co.voik.agesandtheart.age.aspect

import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.CelestialBody
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.PhaseCycle
import net.minecraft.util.Mth
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Moons a book asked to eclipse their sun: `eclipsing moon` (`notes/the-art-design.md` §5.2.2, "Eclipses").
 *
 * An eclipsing moon goes round on the sun's own path, the same plane, pacing and direction, one turn behind it a
 * month, so it rises a little later each day and every time it catches the sun it covers it. Its month is a whole
 * number of the sun's days and its first crossing is put at the sun's highest, so every eclipse comes at noon.
 * It is the sun's apparent size and a quarter over, which gives about eighty seconds of totality at the ordinary
 * month; a size word moves it against the sun ([sizeCovering]).
 *
 * **`constant eclipsing moon` never leaves the sun.** At the very top of [FREQUENCY], which only `constant` says,
 * the moon takes the sun's own path, period and phase and all, so it stands in front of it at every hour and the
 * Age is in an eclipse that does not end ([lockedToTheSun]).
 *
 * Laid after every body has been described, because it is built from the sun's finished path: risen, paced and
 * shaped. A sky with no sun, or whose first sun stands still or is not on a circle, has nothing to eclipse and its
 * moons keep the paths they were drawn.
 */
object EclipsingMoons {

    /** The value of a moon's `path` that asks for this. */
    const val ECLIPSING = "eclipsing"

    /**
     * How often an eclipsing moon covers its sun — `frequent`, `sporadic` — by how long its month is. The name the
     * curtain, the bow and the weather share; on a moon that does not eclipse it says nothing.
     */
    val FREQUENCY = Parameter.ranged(
        "frequency",
        help = "How often this moon eclipses its sun, where it does.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "once in 32 days"),
            Parameter.Landmark(0.0, "once in 8 days", isVanilla = true),
            Parameter.Landmark(1.0, "once in 2 days"),
        ),
    )

    /** [bodies] with every moon [saidOf] calls eclipsing laid on its sun's path. [saidOf] answers by index. */
    fun laidOn(bodies: List<CelestialBody>, saidOf: (Int) -> Options, seed: Long): List<CelestialBody> {
        val sun = bodies.firstOrNull { it.phase == null } ?: return bodies
        val sunOrbit = sun.path as? Orbit ?: return bodies
        val sunSprite = sun.appearance as? Appearance.Sprite ?: return bodies
        val firstMoon = bodies.indexOfFirst { it.phase != null }
        return bodies.mapIndexed { at, body ->
            val said = saidOf(at)
            val phase = body.phase
            if (phase == null || said.of(SkyBodies.PATH) != ECLIPSING) return@mapIndexed body
            val path = if (lockedToTheSun(said, seed)) {
                sunOrbit.copy(distance = body.path.distance)
            } else {
                val monthDays = monthDays(said, seed)
                val firstCrossingDay = firstCrossingDay(isTheFirstMoon = at == firstMoon, monthDays, seed, at)
                orbitCrossing(sunOrbit, body.path.distance, monthDays, firstCrossingDay)
            }
            eclipsing(body, phase, said, path, sunOrbit, sunSprite, seed)
        }
    }

    private fun eclipsing(
        moon: CelestialBody,
        phase: PhaseCycle,
        said: Options,
        path: Orbit,
        sunOrbit: Orbit,
        sunSprite: Appearance.Sprite,
        seed: Long,
    ): CelestialBody {
        val sprite = moon.appearance as? Appearance.Sprite
        val largeness = said.steer(SkyBodies.BODYSIZE, seed) ?: ORDINARY_LARGENESS
        val appearance = sprite?.copy(
            angularSize = sizeCovering(sunSprite, sunOrbit.distance, sprite, moon.path.distance, largeness),
        ) ?: moon.appearance
        return moon.copy(path = path, appearance = appearance, phase = phase.copy(litBySun = true))
    }

    /**
     * The sun's own circle at [distance], [monthDays] of the sun's days to lap it, standing with the sun at its
     * highest on [firstCrossingDay].
     *
     * Two bodies on one circle point the same way exactly when their sweeps have come equally far round, and a
     * circle's highest point is where its sweep reads nought, so the moon's phase is whatever puts its sweep at
     * nought when the sun's is.
     */
    internal fun orbitCrossing(sunOrbit: Orbit, distance: Float, monthDays: Int, firstCrossingDay: Int): Orbit {
        val sunDay = sunOrbit.periodTicks.toDouble()
        val direction = if (sunOrbit.retrograde) -1.0 else 1.0
        val moonPeriod = (sunDay * monthDays / (monthDays - 1)).roundToInt()
        val noonIntoTheDay = sunDay * Mth.frac(direction * (QUARTER_TURN - sunOrbit.phaseDegrees / FULL_TURN))
        val crossing = (noonIntoTheDay + firstCrossingDay * sunDay).roundToLong()
        val gainedOnTheMoon = direction * crossing * (1.0 / sunDay - 1.0 / moonPeriod)
        val phase = Mth.positiveModulo(sunOrbit.phaseDegrees + (gainedOnTheMoon * FULL_TURN).toFloat(), FULL_TURN.toFloat())
        return sunOrbit.copy(periodTicks = moonPeriod, phaseDegrees = phase, distance = distance)
    }

    /**
     * A sprite size whose disc, at [moonDistance], looks [OVER_THE_SUN] larger than the sun's disc at
     * [sunDistance], so it covers the whole of it for a while.
     *
     * **A size word measures against the sun here, not against vanilla's moon**, and half as steeply as on any
     * other body: each step of [largeness] doubles or halves the disc against the sun's. So `small` is a ring of
     * fire, about four-fifths covered, and `colossal` two and a half times the sun and a long dark. Against
     * vanilla's moon, `small` was a speck that crossed a seventh of the sun.
     */
    internal fun sizeCovering(
        sun: Appearance.Sprite,
        sunDistance: Float,
        moon: Appearance.Sprite,
        moonDistance: Float,
        largeness: Double,
    ): Float {
        val sunDisc = sun.angularSize * sun.discShare
        val againstTheSun = (1.0f + OVER_THE_SUN) * Math.pow(2.0, largeness).toFloat()
        return sunDisc / moon.discShare * (moonDistance / sunDistance) * againstTheSun
    }

    /**
     * How many of the sun's days a month is: vanilla's eight, at the moon's pace, shortened or lengthened by
     * [FREQUENCY] up to four times either way. Two at the least, or it never laps.
     */
    internal fun monthDays(said: Options, seed: Long): Int {
        val length = said.steer(SkyBodies.BODYPACE, seed)?.let(SkyBodies::lengthAt) ?: 1.0
        val oftener = said.steer(FREQUENCY, seed)?.let { Math.pow(FREQUENCY_RANGE, it) } ?: 1.0
        return (VANILLAS_MONTH_DAYS * length / oftener).roundToInt().coerceAtLeast(SHORTEST_MONTH_DAYS)
    }

    /**
     * Whether this moon never leaves its sun: [FREQUENCY] at its very top, which a drawn span reaches only where it is
     * that and nothing else, as `constant`'s is. `frequent`'s draw never reaches it.
     */
    internal fun lockedToTheSun(said: Options, seed: Long): Boolean = (said.steer(FREQUENCY, seed) ?: 0.0) >= UNBROKEN

    /** The top of [FREQUENCY]: an eclipse that does not end. */
    private const val UNBROKEN = 1.0

    /** How many times as often the most frequent eclipses come as vanilla's month, and the rarest as seldom. */
    private const val FREQUENCY_RANGE = 4.0

    /**
     * Which of the month's days its first eclipse falls on. The Age's first moon keeps the game's month, so its
     * eclipse comes on the game's new-moon day, halfway through; any other moon's day is drawn.
     */
    private fun firstCrossingDay(isTheFirstMoon: Boolean, monthDays: Int, seed: Long, at: Int): Int =
        if (isTheFirstMoon) monthDays / 2 else Random(seed xor CROSSING_SALT xor at.toLong()).nextInt(monthDays)

    /** Vanilla's month, in days: eight shapes, one a day. */
    private const val VANILLAS_MONTH_DAYS = 8
    private const val SHORTEST_MONTH_DAYS = 2

    /** How much larger than the sun an eclipsing moon looks: a quarter, eighty seconds of totality at vanilla's month. */
    private const val OVER_THE_SUN = 0.25f

    /** The middle of the size axis, where nothing sized the moon. */
    private const val ORDINARY_LARGENESS = 0.0

    private const val QUARTER_TURN = 0.25
    private const val FULL_TURN = 360.0
    private const val CROSSING_SALT = 0x45_434C_4950_5345L
}
