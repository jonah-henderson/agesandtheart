package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.aspect.Span

/**
 * Ages written before generator 23, when the stars' glow ran from vanilla's at -1 to twice it at 1, carried onto the
 * axis that runs from a quarter at -1 through vanilla's at 0: each band of it moved to where the same brightness now
 * stands, so the stars burn as they did.
 */
object FormerStarGlow {
    const val LAST_GENERATOR_OF_THE_FORMER_GLOW = 22

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val glow = SkyBodies.STARGLOW.name
        val every = composition.options.allOf(Aspect.STARS)
        if (every.none { glow in it.chosen }) return composition
        val carried = every.map { options ->
            val former = options.chosen[glow] ?: return@map options
            Options(options.chosen + (glow to former.map(::carriedOver)))
        }
        return composition.copy(options = composition.options.with(Aspect.STARS, carried))
    }

    /** A band spelled on the former axis, spelled on the present one; anything else as it was. */
    fun carriedOver(spelled: String): String {
        val span = Span.read(spelled) ?: return spelled
        return span.copy(least = onThePresentAxis(span.least), most = onThePresentAxis(span.most)).spelled()
    }

    private fun onThePresentAxis(former: Double): Double = (former + 1.0) / 2.0
}
