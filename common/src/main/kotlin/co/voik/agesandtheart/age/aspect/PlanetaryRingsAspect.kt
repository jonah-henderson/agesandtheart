package co.voik.agesandtheart.age.aspect

import co.voik.ephemeris.sky.PlanetaryRings
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * The rings an Age wears overhead: what a writer may say about them, and what is drawn where they said nothing
 * (the-art-design.md "Planetary rings").
 *
 * **Naming them asserts them, and so does describing them**, as the aurora's do: the `planetary_rings` page sets
 * [PRESENT], and anything else set on the part is a writer saying the Age has rings. Nothing about them is a
 * phenomenon: they are always there, and the arch never moves.
 */
object PlanetaryRingsAspect {

    /** Set by the bare page, so naming the rings is enough to have them. */
    val PRESENT = Parameter.flag("present", help = "Whether the Age has planetary rings overhead.")

    /** What the lit rings are coloured. */
    val RINGSCOLOUR = Sky.colour("colour", "The colour of the planetary rings.")

    /** How bright they are, against ordinary rings. */
    val RINGSGLOW = Parameter.ranged("glow", help = "How bright the planetary rings are.")

    /** How broad the band is, from its inner edge to its outer. */
    val RINGSSIZE = Parameter.ranged(
        "size",
        help = "How broad the planetary rings are.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a thin ribbon"),
            Parameter.Landmark(0.0, "a broad band"),
            Parameter.Landmark(1.0, "a great sweep of the sky"),
        ),
    )

    /**
     * How high the arch stands — `high` across the zenith, `low` along the horizon. Said as the Age's distance from
     * the ring plane, which is what decides it.
     */
    val RINGSALTITUDE = Parameter.ranged(
        "altitude",
        help = "How high the planetary rings arch across the sky.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "low along the horizon"),
            Parameter.Landmark(0.0, "well up the sky"),
            Parameter.Landmark(1.0, "across the zenith"),
        ),
    )

    /** How solid they are: thin rings with wide gaps, or a near-solid sheet. */
    val RINGSDENSITY = Parameter.ranged("density", help = "How solid the planetary rings are.")

    /** The rings this Age wears, or null where nothing asked for them. */
    fun ringsIn(parts: AgeParts, seed: Long): PlanetaryRings? {
        val own = parts.optionsFor(Aspect.PLANETARY_RINGS)
        if (own.chosen.isEmpty()) return null
        // Drawn where nothing said, as an aurora's are, so two Ages with bare rings still differ.
        val size = own.steer(RINGSSIZE, seed) ?: Sky.drawn(seed, SIZE_SALT, DRAWN_SPREAD)
        val altitude = own.steer(RINGSALTITUDE, seed) ?: Sky.drawn(seed, ALTITUDE_SALT, DRAWN_ALTITUDE)
        val density = own.steer(RINGSDENSITY, seed) ?: Sky.drawn(seed, DENSITY_SALT, DRAWN_SPREAD)
        val glow = own.steer(RINGSGLOW, seed) ?: Sky.drawn(seed, GLOW_SALT, Sky.DRAWN_GLOW)
        val breadth = Sky.bandAt(size, THINNEST, BROADEST)
        return PlanetaryRings(
            colour = Colour.named(own.of(RINGSCOLOUR))?.saturated(RINGS_ARE_LOOKED_AT) ?: PlanetaryRings.ORDINARY_COLOUR,
            glow = Sky.bandAt(glow, FAINTEST, BRIGHTEST),
            innerRadius = INNER_EDGE,
            outerRadius = INNER_EDGE + breadth,
            latitudeDegrees = latitudeFor(altitude),
            density = Sky.bandAt(density, THINNEST_RINGS, DENSEST_RINGS),
            // Drawn rather than written, as an aurora's bearing is: which way the arch faces is a fact of this sky.
            bearingDegrees = XoroshiroRandomSource(seed xor BEARING_SALT).nextInt(WHOLE_COMPASS).toFloat(),
            seed = seed xor PROFILE_SALT,
        )
    }

    /**
     * How far from the ring plane an Age stands for rings [altitude] high: near it the arch climbs toward the zenith,
     * far from it the arch lies along the horizon. Two straight runs meeting at [ORDINARY_LATITUDE], so ordinary rings
     * stand well up the sky and each end keeps its own span.
     */
    private fun latitudeFor(altitude: Double): Float {
        val towardTheZenith = altitude.coerceIn(0.0, 1.0)
        val towardTheHorizon = (-altitude).coerceIn(0.0, 1.0)
        val nearer = (ORDINARY_LATITUDE - NEAREST_THE_PLANE) * towardTheZenith
        val further = (FURTHEST_FROM_THE_PLANE - ORDINARY_LATITUDE) * towardTheHorizon
        return (ORDINARY_LATITUDE - nearer + further).toFloat()
    }

    /** How far a named colour is pushed from its own grey, less than a sun's: rings are pale stuff lit by one. */
    private const val RINGS_ARE_LOOKED_AT = 0.6f

    /** Where the rings begin, in the planet's radii; [THINNEST] to [BROADEST] is how far they run past it. */
    private const val INNER_EDGE = 1.35f
    private const val THINNEST = 0.25f
    private const val BROADEST = 1.6f

    private const val FAINTEST = 0.45f
    private const val BRIGHTEST = 1.6f

    /**
     * The latitudes [RINGSALTITUDE] runs over: 4° stands the arch's crown near the zenith, about 83°, 18° well up the
     * sky, about 58°, and 58° lays it along the horizon. Overhead itself would be edge-on, a line.
     */
    private const val NEAREST_THE_PLANE = 4.0
    private const val ORDINARY_LATITUDE = 18.0
    private const val FURTHEST_FROM_THE_PLANE = 58.0

    private const val THINNEST_RINGS = 0.1f
    private const val DENSEST_RINGS = 1.0f

    /** Narrower than a word can ask for, for the aurora's reason: the far ends are what a writer buys. */
    private val DRAWN_SPREAD = Span(-0.4, 0.4)

    /** Where unsaid rings' crowns stand: about 50° to 64°, well up the sky and short of what `high` buys. */
    private val DRAWN_ALTITUDE = Span(-0.15, 0.25)

    private const val WHOLE_COMPASS = 360

    private const val SIZE_SALT = 0x2196_512EL
    private const val ALTITUDE_SALT = 0x2196_A171L
    private const val DENSITY_SALT = 0x2196_DE45L
    private const val GLOW_SALT = 0x2196_6100L
    private const val BEARING_SALT = 0x2196_BEA2L
    private const val PROFILE_SALT = 0x2196_9F0FL
}
