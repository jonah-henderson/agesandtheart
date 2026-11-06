package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Structures

/**
 * Incongruous Ages written before generator 23, when a jigsaw was started at its drawn height rather than built on the
 * surface and moved whole, held to that: the structures are marked [Structures.FORMER_INCONGRUOUS_HEIGHTS], so a
 * village beside one already standing is placed as it was.
 */
object FormerIncongruousHeights {
    const val LAST_GENERATOR_OF_THE_STARTED_HEIGHTS = 22

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        if (!composition.optionsFor(Aspect.STRUCTURES).isTrue(Structures.INCONGRUOUS)) return composition
        val members = maxOf(composition.membersIn(Aspect.STRUCTURES), 1)
        val marked = composition.options.expanded(Aspect.STRUCTURES, members).map { options ->
            Options(options.chosen + (Structures.FORMER_INCONGRUOUS_HEIGHTS.name to listOf(Parameter.TRUE)))
        }
        return composition.copy(options = composition.options.with(Aspect.STRUCTURES, marked))
    }
}
