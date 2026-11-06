package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.aspect.Colour
import co.voik.ephemeris.Rgba

/**
 * The lights a meteor storm burns in: [sky], each body's light coming in ([MeteorSky]); [rock], the bloom and streak
 * on one falling ([MoltenLumpRenderer]); and [cast] and [floor], what the ground under the storm is lit in
 * ([MeteorGlow]).
 */
data class MeteorLights(val sky: Rgba, val rock: Rgba, val cast: Rgba, val floor: Rgba) {
    companion object {
        /** Their own violet, set apart from the pack's red and orange fire. */
        val VIOLET = MeteorLights(
            sky = Rgba(0.62f, 0.45f, 1.0f, 0.95f),
            rock = Rgba(0.72f, 0.56f, 1.0f, 0.9f),
            cast = Rgba(0.55f, 0.16f, 1.0f),
            floor = Rgba(0.30f, 0.09f, 0.58f),
        )

        /** The lights for the colour [named], or [VIOLET] where nothing or nothing of ours is named. */
        fun of(named: String?): MeteorLights {
            if (named == null) return VIOLET
            val colour = Colour.named(named) ?: return VIOLET
            if (named == BLACK) return DARK
            return litIn(deepened(colour))
        }

        /** Laid out as the violet's are: the lights paler toward white, the ground's floor a dimmer cast. */
        private fun litIn(cast: Rgba) = MeteorLights(
            sky = cast.lerp(Rgba.WHITE, SKY_PALER).copy(alpha = VIOLET.sky.alpha),
            rock = cast.lerp(Rgba.WHITE, ROCK_PALER).copy(alpha = VIOLET.rock.alpha),
            cast = cast,
            floor = cast.dimmed(FLOOR_DIMMER),
        )

        /** Deep, as the violet's cast is: pushed from its grey and burning full in its brightest channel. */
        private fun deepened(colour: Rgba): Rgba {
            val pushed = colour.saturated(DEEPENED_BY)
            val brightest = maxOf(pushed.red, pushed.green, pushed.blue)
            return pushed.dimmed(FULL / brightest)
        }

        /**
         * Black lights the sky grey, since a light cannot be added darker than the sky, and turns the ground under
         * it dark instead of lighting it.
         */
        private val DARK = MeteorLights(
            sky = Rgba(0.55f, 0.55f, 0.6f, VIOLET.sky.alpha),
            rock = Rgba(0.6f, 0.6f, 0.65f, VIOLET.rock.alpha),
            cast = Rgba(0.08f, 0.08f, 0.1f),
            floor = Rgba(0.0f, 0.0f, 0.0f),
        )

        private const val BLACK = "black"
        private const val FULL = 1.0f
        private const val DEEPENED_BY = 1.5f
        private const val SKY_PALER = 0.35f
        private const val ROCK_PALER = 0.5f
        private const val FLOOR_DIMMER = 0.58f
    }
}
