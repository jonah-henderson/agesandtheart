package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * Ages written before generator 22 left unsaid land smooth, held to the wear they had: every landform is marked
 * [Terrain.FORMER_UNSAID_WEAR], so the chunks it generates next still meet the ones it has.
 */
object FormerUnsaidWear {
    const val LAST_GENERATOR_OF_THE_UNSAID_WEAR = 21

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val members = composition.membersIn(Aspect.TERRAIN)
        val marked = composition.options.expanded(Aspect.TERRAIN, members).map { options ->
            Options(options.chosen + (Terrain.FORMER_UNSAID_WEAR.name to listOf(Parameter.TRUE)))
        }
        return composition.copy(options = composition.options.with(Aspect.TERRAIN, marked))
    }
}
