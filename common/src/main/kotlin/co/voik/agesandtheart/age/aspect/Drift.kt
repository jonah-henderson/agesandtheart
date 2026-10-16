package co.voik.agesandtheart.age.aspect

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.SkyWander
import kotlin.random.Random

/**
 * A sky wandering slowly through colours — what the sky-drift step inflicts and what `shifting sky` writes, so
 * both are drawn the same way.
 */
object Drift {

    /** Whether the sky wanders through colours: between the ones written for it, or through an uncanny few. */
    val SHIFTING = Parameter.flag("shifting", help = "Whether the sky wanders slowly from colour to colour.")

    /** How fast it wanders — `pace`, the name the rain and the creatures share, so `slow` reaches all three. */
    val PACE = Parameter.ranged(
        "pace",
        help = "How fast a shifting sky moves from one colour to the next.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a day for each colour"),
            Parameter.Landmark(0.0, "ordinary"),
            Parameter.Landmark(1.0, "four times as fast"),
        ),
    )

    /**
     * The wander [colours] make at [strength] and [pace], each from nothing to all of what the step can buy:
     * how far the sky leans from its own colour, and how fast it moves.
     */
    fun wander(colours: List<Rgba>, strength: Double, pace: Double): SkyWander = SkyWander(
        colours = colours,
        strength = (FIRST_STRENGTH + strength * (FULL_STRENGTH - FIRST_STRENGTH)).toFloat(),
        ticksPerColour = (SLOWEST_TICKS_PER_COLOUR / (1.0 + pace * FASTER_IN_FULL)).toInt(),
    )

    /**
     * The wander [vault] writes, or null where it is not shifting: between its own colours where it was given
     * several — `red and blue shifting sky` — and through this Age's uncanny few otherwise.
     */
    fun writtenIn(vault: Options, seed: Long): SkyWander? {
        if (!vault.isTrue(SHIFTING)) return null
        val named = vault.allOf(Atmosphere.SKY).mapNotNull(Colour::named)
        val colours = if (named.size >= SEVERAL) named else paletteFor(seed)
        val pace = vault.steer(PACE, seed)?.let(Span.NATURAL::fractionOf) ?: ORDINARY_PACE
        return wander(colours, strength = ALL_OF_IT, pace = pace)
    }

    /** A few of [UNCANNY] in an order of this Age's own — the wander's colours, and a tinted mote's first. */
    fun paletteFor(seed: Long): List<Rgba> = UNCANNY.shuffled(Random(seed xor WANDER_SALT)).take(COLOURS_WANDERED)

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

    /** Two colours are the least a wander can move between. */
    private const val SEVERAL = 2

    /** How far the sky leans towards the wander at its least, and at its most. */
    private const val FIRST_STRENGTH = 0.35
    private const val FULL_STRENGTH = 0.85

    /** A Minecraft day from one colour to the next, and four times as fast at the most. */
    private const val SLOWEST_TICKS_PER_COLOUR = 24_000.0
    private const val FASTER_IN_FULL = 3.0

    /** Where a written wander's pace sits when nothing said how fast: half way. */
    private const val ORDINARY_PACE = 0.5
    private const val ALL_OF_IT = 1.0

    private const val WANDER_SALT = 0x5A7E_D21F_7C4B_93E1L
}
