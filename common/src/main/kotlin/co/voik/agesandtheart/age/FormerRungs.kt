package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Span

/**
 * Ages written while a rung on a phenomenon still meant how often it came, read as they behaved.
 *
 * Up to [LAST_GENERATOR_BEFORE_THE_SPLIT], `teeming tempest` was a tempest four times as often. Now a rung
 * counts only what can be counted in space, so each such claim's rung is carried over to the frequency that
 * comes as often, and the claim goes back to an ordinary amount.
 */
object FormerRungs {
    const val LAST_GENERATOR_BEFORE_THE_SPLIT = 12

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val options = composition.optionsFor(Aspect.PHENOMENA, 0)
        val claims = options.chosen[Phenomena.HAPPENS.name].orEmpty().map(Claim::read)
        fun meantHowOften(claim: Claim): Boolean {
            val phenomenon = Phenomenon.named(claim.value) ?: return false
            return !phenomenon.countsInSpace && !Rung.isOrdinary(claim.density)
        }
        val timed = claims.filter(::meantHowOften)
        if (timed.isEmpty()) return composition
        val frequencies = timed.map { claim ->
            val steered = Recurrence.steeredFor(claim.density / Rung.ORDINARY)
            Claim(Span(steered, steered).spelled(), members = listOf(claim.value)).spelled()
        }
        val happens = claims.map { if (meantHowOften(it)) it.copy(density = Rung.ORDINARY) else it }
        val frequencyName = Phenomena.FREQUENCY.name
        val carried = Options(
            options.chosen +
                (Phenomena.HAPPENS.name to happens.map(Claim::spelled)) +
                (frequencyName to options.chosen[frequencyName].orEmpty() + frequencies),
        )
        return composition.copy(options = composition.options.with(Aspect.PHENOMENA, listOf(carried)))
    }
}
