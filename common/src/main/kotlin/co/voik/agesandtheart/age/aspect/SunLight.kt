package co.voik.agesandtheart.age.aspect

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.sky.Appearance
import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.SkySpec

/**
 * What an Age's **deciding sun** — the first, which daylight follows — does to the light under it, where
 * the book said nothing of the light itself.
 *
 * A coloured sun washes the daylight with its colour. A black one gives an eclipse: a dusky violet sky by day
 * and by night, the stars out at noon, and a dim cold light over everything. Only what the eye sees moves:
 * the light level the game spawns and grows by is the sun's ordinary one.
 */
object SunLight {

    /** The look the deciding sun of [sky] lays under the sentence's own, or [Look.NOTHING]. */
    fun lookUnder(sky: SkySpec): Look {
        val tint = decidingSunTint(sky) ?: return Look.NOTHING
        if (isBlack(tint)) return ECLIPSE
        if (tint == Rgba.WHITE) return Look.NOTHING
        return Look(tint = Rgba.WHITE.lerp(tint, SUN_TINTS_THE_LIGHT))
    }

    /** Whether the deciding sun of [sky] burns black — `black`, and nothing else a writer can name. */
    fun burnsBlack(sky: SkySpec): Boolean = decidingSunTint(sky)?.let(::isBlack) == true

    private fun decidingSunTint(sky: SkySpec): Rgba? {
        val sun = sky.bodies.firstOrNull { it.phase == null } ?: return null
        return (sun.appearance as? Appearance.Sprite)?.tint
    }

    private fun isBlack(tint: Rgba): Boolean {
        val brightness = tint.red * SEEN_AS_RED + tint.green * SEEN_AS_GREEN + tint.blue * SEEN_AS_BLUE
        return brightness <= DARKEST_A_SUN_MAY_BE_AND_STILL_SHINE
    }

    /** `black` is #1A1A1F, about a tenth as bright as white; nothing else a writer can name is near it. */
    private const val DARKEST_A_SUN_MAY_BE_AND_STILL_SHINE = 0.15f

    private const val SEEN_AS_RED = 0.2126f
    private const val SEEN_AS_GREEN = 0.7152f
    private const val SEEN_AS_BLUE = 0.0722f

    /** How far a coloured sun carries daylight from white towards its own colour. */
    private const val SUN_TINTS_THE_LIGHT = 0.35f

    /**
     * Under a black sun. The sky is dusky, so the sun's pale corona shows against it; the light is cold and
     * dim; the stars are out as at midnight, day and night alike.
     */
    private val ECLIPSE = Look(
        sky = Rgba(0.20f, 0.15f, 0.31f),
        fog = Rgba(0.24f, 0.19f, 0.33f),
        tint = Rgba(0.42f, 0.38f, 0.62f),
        starBrightness = 1.0f,
    )
}
