package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.aspect.Aspect
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey

/** A page of the Art: what a writer lays, and the meanings it carries. */
data class Word(
    /** Where the word was defined. Its path is what a writer says: `agesandtheart:frozen` is "frozen". */
    val id: Identifier,
    val meanings: List<Meaning>,
    /** How a book's prose speaks it, where its file says. */
    val speech: Speech? = null,
) {
    val name: String get() = id.path

    /** How a book's prose speaks it: as its file or its derivation says, and otherwise as said of something. */
    val partOfSpeech: Speech get() = speech ?: Speech.ADJECTIVE

    /** Everything its meanings say, together. */
    val effects: List<Effect> get() = meanings.flatMap { it.effects }

    /** The registries this word is itself an entry of — the block a block's word is, the creature a creature's. */
    val referentRegistries: Set<ResourceKey<out Registry<*>>>
        get() = effects.filterIsInstance<Effect.Chooses>()
            .filter { it.member == id.toString() }
            .mapNotNull { it.aspect.presetsAreEntriesOf }
            .toSet()

    /** Whether all this word says is how much there is — `teeming`, `scarce`. */
    val isAQuantifier: Boolean get() = effects.isNotEmpty() && effects.all { it is Effect.Abundance }

    /** The block this word is, where it is one. */
    val block: String? get() = id.toString().takeIf { Registries.BLOCK in referentRegistries }

    /** The pattern this word mints where it is aimed, where it is a minting. */
    val mints: String?
        get() = meanings.filter { it.mustAlwaysApply || it.chanceToApply > Meaning.NEVER }
            .flatMap { it.effects }.filterIsInstance<Effect.Mints>().firstOrNull()?.pattern

    /**
     * What it costs to write: [INK_PER_NARROWED_PART] for each part of the world narrowed by a meaning certain to
     * apply where aimed, a template one more, and [FLOOR_PRICE] where it narrows nothing. Leans, bends, abundance and
     * meanings only likely to apply are free, so the evocative words stay cheapest (world model §9).
     */
    val price: Int get() {
        fun isCertainWhereAimed(meaning: Meaning) = meaning.mustAlwaysApply || meaning.chanceToApply == Meaning.CERTAIN
        val narrowing = meanings.filter(::isCertainWhereAimed).flatMap { it.effects }.filter(Effect::narrows)
        val parts = narrowing.flatMap(::partsNarrowedBy).toSet()
        val templates = narrowing.count { it is Effect.Template }
        val narrowed = parts.size + templates
        return if (narrowed == 0) FLOOR_PRICE else INK_PER_NARROWED_PART * narrowed
    }

    companion object {
        const val INK_PER_NARROWED_PART = 4
        const val FLOOR_PRICE = 1

        /** The parts of the world [effect] narrows; a template narrows none of them, being the world itself. */
        fun partsNarrowedBy(effect: Effect): Set<Aspect> = when (effect) {
            is Effect.Sets -> Aspect.entries.filter(effect.parameter::landsOn).toSet()
            is Effect.Chooses -> setOf(effect.aspect)
            is Effect.Excludes -> setOf(effect.aspect)
            is Effect.Restricts -> setOf(effect.aspect)
            is Effect.Mints -> setOf(Aspect.FEATURES)
            is Effect.Bans -> setOf(Aspect.TERRAIN)
            is Effect.Template, is Effect.Bends, is Effect.Admits, is Effect.Leans, is Effect.LeansEverywhere,
            is Effect.Abundance, is Effect.MadeOf,
            -> emptySet()
        }
    }
}
