package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * Ages written before generator 24, when islands nothing said the wear of took the land's quarter of a wear and so
 * stayed young, held to that: every landform is marked [Terrain.FORMER_ISLAND_WEAR], so the islands it generates next
 * still meet the ones it has. Only where islands are among its landforms.
 */
object FormerIslandWear {
    const val LAST_GENERATOR_OF_THE_FORMER_ISLAND_WEAR = 23

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val hasIslands = composition.terrains.any { it == Terrain.ISLANDS || it == Terrain.ISLE }
        if (!hasIslands) return composition
        val members = composition.membersIn(Aspect.TERRAIN)
        val marked = composition.options.expanded(Aspect.TERRAIN, members).map { options ->
            Options(options.chosen + (Terrain.FORMER_ISLAND_WEAR.name to listOf(Parameter.TRUE)))
        }
        return composition.copy(options = composition.options.with(Aspect.TERRAIN, marked))
    }
}
