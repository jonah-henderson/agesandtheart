package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Drift
import co.voik.ephemeris.sky.LevelLook
import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.RainLook
import co.voik.ephemeris.sky.SkySpec
import co.voik.ephemeris.sky.SkyWander
import net.minecraft.resources.Identifier

/**
 * What the strange tier does to an Age's sky: it wanders through wrong colours, keeps its stars by day, brings
 * its clouds down to the ground, drains the whole Age of colour, or slows its rain. Seen only, and settled when the Age opens, since none of it changes
 * unless the book does.
 */
object StrangeSky {

    /** The look these lay over everything the Age's own words painted — null fields wherever nothing was bought. */
    fun lookFor(spending: Spending, seed: Long): Look = Look(
        ceiling = cloudsFor(spending),
        wander = wanderFor(spending, seed),
    )

    /** What the whole Age is seen through — drained of colour, where that was bought. */
    fun filtersFor(spending: Spending): List<Identifier> =
        if (spending.bought(Manifestation.MONOCHROME) > 0) listOf(LevelLook.MONOCHROME) else emptyList()

    /** How the Age's rain falls and sounds: slower and lower the further that was bought, vanilla's otherwise. */
    fun rainFor(spending: Spending): RainLook {
        val slowness = spending.reach(Manifestation.SLOW_RAIN, Manifestation.RAIN_SLOWNESS)
        if (slowness <= NONE) return RainLook()
        return RainLook(
            pace = (FIRST_PACE - slowness * PACE_LOST_IN_FULL).toFloat(),
            pitch = (FIRST_PITCH - slowness * PITCH_LOST_IN_FULL).toFloat(),
        )
    }

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
        return Drift.wander(
            Drift.paletteFor(seed),
            strength = spending.reach(Manifestation.SKY_DRIFT, Manifestation.DRIFT_STRENGTH),
            pace = spending.reach(Manifestation.SKY_DRIFT, Manifestation.DRIFT_PACE),
        )
    }

    /** Where the cloud layer is brought down to, as [Look.ceiling]'s fraction — null where nothing was bought. */
    private fun cloudsFor(spending: Spending): Float? {
        val lowness = spending.reach(Manifestation.LOW_CLOUDS, Manifestation.CLOUDS_LOWNESS)
        if (lowness <= NONE) return null
        val height = LOWEST_CLOUDS + (1.0 - lowness) * LOW_CLOUD_BAND
        return Atmosphere.ceilingAt(height.toInt() + OFF_THE_BLOCK_GRID)
    }

    /** The cloud layer's floor bought in full — vanilla's four-block slab from here up, about where land begins. */
    private const val LOWEST_CLOUDS = 68.0

    /** How much higher than [LOWEST_CLOUDS] the layer sits for the smallest step. */
    private const val LOW_CLOUD_BAND = 16.0

    /**
     * Slow rain's pace and pitch as shares of vanilla's, and what the rest of the dial takes off: two steps
     * fall at 0.4 and then 0.2 of the speed, pitched at 0.8 and then 0.65.
     */
    private const val FIRST_PACE = 0.6
    private const val PACE_LOST_IN_FULL = 0.4
    private const val FIRST_PITCH = 0.95
    private const val PITCH_LOST_IN_FULL = 0.3

    /** Half a block, so a cloud layer's faces never lie in a block's and flicker against the ground. */
    private const val OFF_THE_BLOCK_GRID = 0.5f

    private const val NONE = 0.0
}
