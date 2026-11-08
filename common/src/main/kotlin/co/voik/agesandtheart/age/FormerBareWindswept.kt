package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * Ages written before generator 25, when windswept land stood bare of pedestal rocks, held to that: every landform is
 * marked [Terrain.FORMER_BARE_WINDSWEPT], so the land it generates next still meets the land it has. Only where windswept
 * land is among its landforms.
 */
object FormerBareWindswept {
    const val LAST_GENERATOR_OF_BARE_WINDSWEPT_LAND = 24

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        if (Terrain.YARDANGS !in composition.terrains) return composition
        val members = composition.membersIn(Aspect.TERRAIN)
        val marked = composition.options.expanded(Aspect.TERRAIN, members).map { options ->
            Options(options.chosen + (Terrain.FORMER_BARE_WINDSWEPT.name to listOf(Parameter.TRUE)))
        }
        return composition.copy(options = composition.options.with(Aspect.TERRAIN, marked))
    }
}
