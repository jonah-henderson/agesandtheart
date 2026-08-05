package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.math.Rgba

/**
 * The colours a writer may paint the air with — nine, and deliberately few.
 *
 * **Named rather than numeric, and this is the one place §3.2's rule is unarguable**: a hex triple is the
 * arcane internal that section exists to keep away from a writer, where "green" is a thing a person says
 * about a sky. Nine covers the spectrum a book would name plus the three that are not on it, and a tenth
 * can be added the day a word wants one.
 *
 * The values lean **light** on purpose. These are painted onto sky, fog and cloud, which are lit rather
 * than surfaced, so a saturated primary reads as a colour filter over the world and a lighter one reads as
 * weather.
 */
object Colour {
    private val NAMED = linkedMapOf(
        "red" to hue(0xD9, 0x53, 0x4F),
        "orange" to hue(0xE0, 0x8B, 0x3E),
        "yellow" to hue(0xE3, 0xCC, 0x5B),
        "green" to hue(0x6D, 0xB5, 0x63),
        "blue" to hue(0x5B, 0x8F, 0xE3),
        "purple" to hue(0x9B, 0x6C, 0xC6),
        "black" to hue(0x1A, 0x1A, 0x1F),
        "white" to hue(0xE8, 0xE8, 0xEC),
        "grey" to hue(0x8A, 0x8C, 0x92),
    )

    /** Written as bytes because that is how a colour is read off a swatch, and held as [Rgba]'s floats. */
    private fun hue(red: Int, green: Int, blue: Int) =
        Rgba(red / FULL, green / FULL, blue / FULL)

    private const val FULL = 255f

    /** Every colour a parameter offers, in the order they read. */
    val ALL: List<String> = NAMED.keys.toList()

    /** The colour [named], or null where the word is not one of ours. */
    fun named(name: String): Rgba? = NAMED[name]
}
