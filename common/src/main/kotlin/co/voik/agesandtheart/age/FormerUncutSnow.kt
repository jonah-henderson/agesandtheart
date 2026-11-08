package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Parameter

/**
 * Ages written before generator 25, when the wind left the snow smooth, held to that: the air is marked
 * [Atmosphere.FORMER_UNCUT_SNOW], so the snow it generates next still meets the snow it has. Only where a wind is written.
 */
object FormerUncutSnow {
    const val LAST_GENERATOR_OF_UNCUT_SNOW = 24

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val windWritten = Atmosphere.WIND.name in composition.optionsFor(Aspect.AIR).chosen
        if (!windWritten) return composition
        return composition.withOptions(Aspect.AIR, Atmosphere.FORMER_UNCUT_SNOW.name, listOf(Parameter.TRUE))
    }
}
