package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.SkySpec
import co.voik.ephemeris.sky.SkyWander
import kotlin.random.Random

/**
 * What the strange tier does to an Age's sky: it wanders through wrong colours, keeps its stars by day, or
 * brings its clouds down to the ground. Seen only, and settled when the Age opens, since none of it changes
 * unless the book does.
 */
object StrangeSky {

    /** The look these lay over everything the Age's own words painted — null fields wherever nothing was bought. */
    fun lookFor(spending: Spending, seed: Long): Look = Look(
        ceiling = cloudsFor(spending),
        wander = wanderFor(spending, seed),
    )

    /** [sky] with its stars kept through the day as far as was bought. */
    fun starsOn(sky: SkySpec, spending: Spending): SkySpec {
        val byDay = spending.reach(Manifestation.DAY_STARS, Manifestation.STARS_BY_DAY).toFloat()
        if (byDay <= NONE) return sky
        return sky.copy(stars = sky.stars.copy(byDay = byDay))
    }

    /**
     * A step of either dial brings the whole wander; [Manifestation.DRIFT_STRENGTH] pushes it further from the
     * sky's own colour and [Manifestation.DRIFT_PACE] moves it faster.
     */
    private fun wanderFor(spending: Spending, seed: Long): SkyWander? {
        if (spending.bought(Manifestation.SKY_DRIFT) == 0) return null
        val strength = spending.reach(Manifestation.SKY_DRIFT, Manifestation.DRIFT_STRENGTH)
        val pace = spending.reach(Manifestation.SKY_DRIFT, Manifestation.DRIFT_PACE)
        return SkyWander(
            colours = paletteFor(seed),
            strength = (FIRST_STRENGTH + strength * (FULL_STRENGTH - FIRST_STRENGTH)).toFloat(),
            ticksPerColour = (SLOWEST_TICKS_PER_COLOUR / (1.0 + pace * FASTER_BOUGHT_IN_FULL)).toInt(),
        )
    }

    /** A few of [UNCANNY] in an order of this Age's own — the wander's colours, and a tinted mote's first. */
    fun paletteFor(seed: Long): List<Rgba> = UNCANNY.shuffled(Random(seed xor WANDER_SALT)).take(COLOURS_WANDERED)

    /** Where the cloud layer is brought down to, as [Look.ceiling]'s fraction — null where nothing was bought. */
    private fun cloudsFor(spending: Spending): Float? {
        val lowness = spending.reach(Manifestation.LOW_CLOUDS, Manifestation.CLOUDS_LOWNESS)
        if (lowness <= NONE) return null
        val height = LOWEST_CLOUDS + (1.0 - lowness) * LOW_CLOUD_BAND
        return Atmosphere.ceilingAt(height.toInt())
    }

    /**
     * Colours wrong for a sky: sickly green, bruise violet, rust, deep teal, jaundice. Only their hue matters —
     * [SkyWander] keeps the sky's own brightness.
     */
    private val UNCANNY = listOf(
        Rgba.of(0xFFA8C850u.toInt()),
        Rgba.of(0xFF7A4FA0u.toInt()),
        Rgba.of(0xFFB0583Au.toInt()),
        Rgba.of(0xFF2E8A86u.toInt()),
        Rgba.of(0xFFC8B048u.toInt()),
    )

    private const val COLOURS_WANDERED = 3

    /** How far the sky leans towards the wander for its first step, and bought in full. */
    private const val FIRST_STRENGTH = 0.35
    private const val FULL_STRENGTH = 0.85

    /** A Minecraft day from one colour to the next, and four times as fast bought in full. */
    private const val SLOWEST_TICKS_PER_COLOUR = 24_000.0
    private const val FASTER_BOUGHT_IN_FULL = 3.0

    /** The cloud layer's floor bought in full — vanilla's four-block slab from here up, about where land begins. */
    private const val LOWEST_CLOUDS = 68.0

    /** How much higher than [LOWEST_CLOUDS] the layer sits for the smallest step. */
    private const val LOW_CLOUD_BAND = 16.0

    private const val NONE = 0.0
    private const val WANDER_SALT = 0x5A7E_D21F_7C4B_93E1L
}
