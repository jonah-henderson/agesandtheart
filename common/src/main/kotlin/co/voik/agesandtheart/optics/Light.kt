package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.RimeColour
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB

/**
 * Light carried by a beam or a conduit network: an [intensity] in percent, where [FULL] is what a receiver needs, and
 * a [colour] that is always one of the rime colours and says nothing about how strong the light is. A laser focused
 * beside arc crystal carries an [arc] ([ArmedBeams]).
 */
data class Light(val intensity: Int, val colour: RimeColour, val arc: Arc? = null) {

    val isStrongEnough: Boolean get() = intensity >= FULL

    /** Whether it hurts what it passes through: armed, and strong enough. */
    val harms: Boolean get() = arc != null && isStrongEnough

    /** Whether it burns through what it ends against: armed by charged crystal, and strong enough. */
    val burns: Boolean get() = arc?.burns == true && isStrongEnough

    /** One of [ways] equal parts, rounded down: a full light shared two ways is too weak for anything. */
    fun sharedBetween(ways: Int): Light = copy(intensity = intensity / ways.coerceAtLeast(1))

    /** Intensities add flat; colours mix as light does, toward white. */
    operator fun plus(other: Light): Light =
        Light(intensity + other.intensity, mixed(colour, other.colour), Arc.stronger(arc, other.arc))

    companion object {
        const val FULL = 100

        val SUNLIGHT = Light(FULL, RimeColour.WHITE)

        /** Every light in [lights] added together, or null where there are none. */
        fun together(lights: Iterable<Light>): Light? = lights.reduceOrNull(Light::plus)

        fun mixed(first: RimeColour, second: RimeColour): RimeColour = mixed(listOf(first, second))

        /** Colours of light mixed: the largest of each channel, snapped to the nearest rime colour; white where none. */
        fun mixed(colours: Collection<RimeColour>): RimeColour {
            if (colours.isEmpty()) return RimeColour.WHITE
            val brightest = ARGB.color(
                colours.maxOf { ARGB.red(it.tint) },
                colours.maxOf { ARGB.green(it.tint) },
                colours.maxOf { ARGB.blue(it.tint) },
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

/**
 * What the arc crystal joined to the emitter that focused a laser made of it: hurting by [hurts] a hit, and burning
 * through blocks where the crystal was charged ([burns]). [armedBy] is that emitter, which pays the charge for what
 * its light burns.
 */
data class Arc(val burns: Boolean, val hurts: Float, val armedBy: BlockPos) {
    companion object {
        /** The arc light mixed from two carries: a burning one over one that only harms, then the one hurting more. */
        fun stronger(first: Arc?, second: Arc?): Arc? = when {
            first == null -> second
            second == null -> first
            first.burns != second.burns -> if (first.burns) first else second
            else -> if (second.hurts > first.hurts) second else first
        }
    }
}
