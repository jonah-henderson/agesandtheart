package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import net.minecraft.resources.Identifier

/**
 * What a word costs to write, the same in any sentence: what its file says, or its dearest sense's — the price of each
 * concept it offers for certain, and [INK_PER_NARROWED_PART] for each part its own meaning narrows — never below
 * [FLOOR_PRICE].
 */
object Price {
    const val INK_PER_NARROWED_PART = 4
    const val FLOOR_PRICE = 1

    fun of(word: Word, concepts: Map<Identifier, Concept>): Int =
        word.price ?: word.senses.maxOf { sense -> ofSense(sense, concepts) }.coerceAtLeast(FLOOR_PRICE)

    private fun ofSense(sense: Sense, concepts: Map<Identifier, Concept>): Int {
        val offered = sense.offers.filter { it.chance == Offer.CERTAIN }.sumOf { concepts[it.concept]?.price ?: 0 }
        val narrowing = sense.means.filter(Effect::narrows)
        val parts = narrowing.flatMap { partsNarrowedBy(it, sense.speaksFor) }.toSet()
        return offered + INK_PER_NARROWED_PART * (parts.size + narrowing.count { it is Effect.Template })
    }

    /** The parts of the world [effect] narrows, said by a sense of [part]; a template narrows none, being the world itself. */
    private fun partsNarrowedBy(effect: Effect, part: Aspect?): Set<Aspect> = when (effect) {
        is Effect.Sets -> setOfNotNull(effect.parameter.fencedTo ?: part)
        is Effect.Chooses -> setOf(effect.aspect)
        is Effect.Excludes -> setOf(effect.aspect)
        is Effect.Restricts -> setOf(effect.aspect)
        is Effect.Mints -> setOf(Aspect.FEATURES)
        is Effect.Bans -> setOf(Aspect.TERRAIN)
        is Effect.Template, is Effect.Bends, is Effect.Admits, is Effect.Leans, is Effect.LeansEverywhere,
        is Effect.Abundance, is Effect.MadeOf, is Effect.LinksIn,
        -> emptySet()
    }
}
