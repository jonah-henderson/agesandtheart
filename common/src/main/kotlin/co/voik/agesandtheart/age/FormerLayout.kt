package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * Ages written before generator 21 settled its territories, held to the layout they had: every landform is marked
 * [Terrain.FORMER_LAYOUT], so the chunks it generates next still meet the ones it has.
 */
object FormerLayout {
    const val LAST_GENERATOR_OF_THE_FORMER_LAYOUT = 20

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val members = composition.membersIn(Aspect.TERRAIN)
        val marked = composition.options.expanded(Aspect.TERRAIN, members).map { options ->
            Options(options.chosen + (Terrain.FORMER_LAYOUT.name to listOf(Parameter.TRUE)))
        }
        return composition.copy(options = composition.options.with(Aspect.TERRAIN, marked))
    }
}
