package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Span

/**
 * Ages written before generator 21, held to the dials they had: a word's lean on an axis other than the climate's was
 * ignored when the value was drawn, so every span outside the climate is straightened to draw as it did.
 */
object FormerLeans {
    const val LAST_GENERATOR_OF_THE_EVEN_DRAW = 20

    fun carriedOver(world: AgeWorld): AgeWorld = when (world) {
        is AgeWorld.Composed -> AgeWorld.Composed(carriedOver(world.composition))
        is AgeWorld.Bespoke -> world
    }

    fun carriedOver(composition: AgeComposition): AgeComposition {
        val straightened = Aspect.entries.filter { it != Aspect.CLIMATE }.fold(composition.options) { options, aspect ->
            val stored = options.allOf(aspect)
            if (stored.isEmpty()) options else options.with(aspect, stored.map(::straightened))
        }
        return composition.copy(options = straightened)
    }

    private fun straightened(options: Options): Options = Options(
        options.chosen.mapValues { (_, values) -> values.map(::straightened) },
    )

    private fun straightened(spelled: String): String {
        val claim = Claim.read(spelled)
        val span = Span.read(claim.value) ?: return spelled
        if (span.bend == span.unbent().bend) return spelled
        return claim.copy(value = span.unbent().spelled()).spelled()
    }
}
