package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.RimeColour
import net.minecraft.util.ARGB

/**
 * Light carried by a beam or a conduit network: an [intensity] in percent, where [FULL] is what a receiver needs, and
 * a [colour] that is always one of the rime colours and says nothing about how strong the light is.
 */
data class Light(val intensity: Int, val colour: RimeColour) {

    val isStrongEnough: Boolean get() = intensity >= FULL

    /** One of [ways] equal parts, rounded down: a full light shared two ways is too weak for anything. */
    fun sharedBetween(ways: Int): Light = copy(intensity = intensity / ways.coerceAtLeast(1))

    /** Intensities add flat; colours mix as light does, toward white. */
    operator fun plus(other: Light): Light = Light(intensity + other.intensity, mixed(colour, other.colour))

    companion object {
        const val FULL = 100

        val SUNLIGHT = Light(FULL, RimeColour.WHITE)

        /** Every light in [lights] added together, or null where there are none. */
        fun together(lights: Iterable<Light>): Light? = lights.reduceOrNull(Light::plus)

        /** Two colours of light mixed: the larger of each channel, snapped to the nearest rime colour. */
        fun mixed(first: RimeColour, second: RimeColour): RimeColour {
            val brightest = ARGB.color(
                maxOf(ARGB.red(first.tint), ARGB.red(second.tint)),
                maxOf(ARGB.green(first.tint), ARGB.green(second.tint)),
                maxOf(ARGB.blue(first.tint), ARGB.blue(second.tint)),
            )
            return nearestRimeColour(brightest)
        }

        /** The rime colour nearest [colour], by distance in red, green and blue. */
        fun nearestRimeColour(colour: Int): RimeColour = RimeColour.entries.minBy { rime ->
            val red = ARGB.red(colour) - ARGB.red(rime.tint)
            val green = ARGB.green(colour) - ARGB.green(rime.tint)
            val blue = ARGB.blue(colour) - ARGB.blue(rime.tint)
            red * red + green * green + blue * blue
        }
    }
}
