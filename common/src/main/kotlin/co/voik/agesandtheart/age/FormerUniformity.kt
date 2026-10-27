package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * Ages written before a landform changed as it was crossed, held to the same landform everywhere and unworn: every
 * landform's [Terrain.VARIETY] and [Terrain.EROSION] are pinned at none, so the chunks it generates next still meet
 * the ones it has.
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
            Options(options.chosen + (Terrain.VARIETY.name to listOf(NONE)) + (Terrain.EROSION.name to listOf(NONE)))
        }
        return composition.copy(options = composition.options.with(Aspect.TERRAIN, pinned))
    }
}
