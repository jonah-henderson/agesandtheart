package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * Ages written before a landform changed as it was crossed, held to the land they had: every landform's
 * [Terrain.VARIETY] is pinned at none and it is marked [Terrain.FORMER_LAND] — unworn, with no terrain table, its
 * territories where chance put them and its biomes on vanilla's climate — so the chunks it generates next still meet
 * the ones it has. Its [Terrain.WEAR] is left alone, the craterlands having read it already.
 */
object FormerUniformity {
    const val LAST_GENERATOR_BEFORE_VARIETY = 19

    private val NONE = Span(-1.0, -1.0).spelled()

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val members = composition.membersIn(Aspect.TERRAIN)
        val pinned = composition.options.expanded(Aspect.TERRAIN, members).map { options ->
            Options(
                options.chosen + (Terrain.VARIETY.name to listOf(NONE)) + (Terrain.FORMER_LAND.name to listOf(Parameter.TRUE)),
            )
        }
        return composition.copy(options = composition.options.with(Aspect.TERRAIN, pinned))
    }
}
