package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Underground

/**
 * Ages written before generator 23, when noise caves took no size or density, held to the caves they had: the
 * underground is marked [Underground.FORMER_NOISE_CAVES], so a size a book asked of it still changes nothing.
 */
object FormerNoiseCaves {
    const val LAST_GENERATOR_OF_THE_FIXED_NOISE_CAVES = 22

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        if (composition.underground != Underground.NOISE_CAVES) return composition
        val members = maxOf(composition.membersIn(Aspect.UNDERGROUND), 1)
        val marked = composition.options.expanded(Aspect.UNDERGROUND, members).map { options ->
            Options(options.chosen + (Underground.FORMER_NOISE_CAVES.name to listOf(Parameter.TRUE)))
        }
        return composition.copy(options = composition.options.with(Aspect.UNDERGROUND, marked))
    }
}
