package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * Ages written before generator 23, when an island kept its size and nearly its height behind its barrier reef and
 * the reef was a flat even band, held to that: every landform is marked [Terrain.FORMER_ISLANDS], so the islands it
 * generates next still meet the ones it has. Only where islands are among its landforms.
 */
object FormerIslands {
    const val LAST_GENERATOR_OF_THE_FORMER_ISLANDS = 22

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val hasIslands = composition.terrains.any { it == Terrain.ISLANDS || it == Terrain.ISLE }
        if (!hasIslands) return composition
        val members = composition.membersIn(Aspect.TERRAIN)
        val marked = composition.options.expanded(Aspect.TERRAIN, members).map { options ->
            Options(options.chosen + (Terrain.FORMER_ISLANDS.name to listOf(Parameter.TRUE)))
        }
        return composition.copy(options = composition.options.with(Aspect.TERRAIN, marked))
    }
}
