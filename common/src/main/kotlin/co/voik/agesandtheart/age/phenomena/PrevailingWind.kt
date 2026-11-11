package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.AgeParts
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.generation.Ages
import co.voik.ephemeris.sky.Wind
import net.minecraft.server.level.ServerLevel

/**
 * The one wind an Age blows along, in degrees from east turning to south: written by a book (`north wind`, `north
 * gale`) or drawn from its seed. A gale's spells and a blizzard's days each swing about a written one rather than
 * blowing anywhere, and a tornado tracks along it.
 */
object PrevailingWind {

    /** The way [level]'s wind blows, whether or not a book wrote one; off the level's own seed elsewhere. */
    fun bearingIn(level: ServerLevel): Double {
        val recipe = Ages.recipeOf(level)
        val parts = recipe?.composition ?: AgeParts.NONE
        return Atmosphere.prevailingBearingDegrees(parts, recipe?.seed ?: level.seed).toDouble()
    }

    /** The way a book said [level]'s wind blows, or null where none said where it comes from. */
    fun writtenIn(level: ServerLevel): Double? =
        Ages.recipeOf(level)?.composition?.let(Atmosphere::writtenBearingDegrees)?.toDouble()

    /** [drawn], from nought to one, as a bearing: anywhere at all, or within [SWING_DEGREES] of [written]. */
    fun drawnAbout(written: Double?, drawn: Double): Double =
        written?.let { it + (drawn * 2.0 - 1.0) * SWING_DEGREES } ?: (drawn * FULL_TURN_DEGREES)

    /** As far either way as a written wind swings from one day to the next. */
    private const val SWING_DEGREES = Wind.DEFAULT_WOBBLE_DEGREES.toDouble()

    private const val FULL_TURN_DEGREES = 360.0
}
