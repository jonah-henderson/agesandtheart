package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * Ages written before generator 27, when the geometric landform's shapes stood the same distance apart whatever spacing
 * the book left on it, held to that: every landform is marked [Terrain.FORMER_UNSPACED_SHAPES], so the shapes it lays
 * next still meet the ones it has. Only where the geometric landform is among its landforms.
 */
object FormerUnspacedShapes {
    const val LAST_GENERATOR_OF_UNSPACED_SHAPES = 26

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        if (Terrain.GEOMETRIC !in composition.terrains) return composition
        val members = composition.membersIn(Aspect.TERRAIN)
        val marked = composition.options.expanded(Aspect.TERRAIN, members).map { options ->
            Options(options.chosen + (Terrain.FORMER_UNSPACED_SHAPES.name to listOf(Parameter.TRUE)))
        }
        return composition.copy(options = composition.options.with(Aspect.TERRAIN, marked))
    }
}
