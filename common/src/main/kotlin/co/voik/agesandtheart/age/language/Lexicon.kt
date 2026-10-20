package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import net.minecraft.core.HolderLookup
import net.minecraft.resources.Identifier
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Every word there is, the structure pages, and what each word takes — what the reading reads a book against. Which
 * word a page means is the vocabulary's to say, retired names and shared ones included, so it is asked rather than
 * kept here.
 */
class Lexicon(
    private val wordNamed: (String) -> Word?,
    private val structureNamed: (String) -> Structure?,
    private val tags: MemberTags,
    private val registries: HolderLookup.Provider?,
) {
    private val acceptances = ConcurrentHashMap<Identifier, Optional<Acceptance>>()

    /** The word [page] means, or null where it is a structure page or nothing at all. */
    fun word(page: String): Word? = if (structureNamed(page) != null) null else wordNamed(page)

    fun structure(page: String): Structure? = structureNamed(page)

    /** What [word] takes from the pages waiting before it, or null where it takes nothing. */
    fun acceptance(word: Word): Acceptance? =
        acceptances.computeIfAbsent(word.id) { Optional.ofNullable(WordAcceptance.of(word, tags, registries)) }.orElse(null)

    /** Every part of the world [word] can be laid on, aimed or bare. */
    fun placesFor(word: Word): Set<Aspect> =
        word.meanings.flatMap { it.effects }.flatMap(::barePlacesOf).toSet() + setOfNotNull(acceptance(word)?.place?.aspect)

    /**
     * The parts of the world [effect] can land on laid bare: every part a parameter is understood in but those only
     * an aimed word turns, the part a choice or lean names, every part an all-over lean likes something in, and the
     * features for a minting.
     */
    fun barePlacesOf(effect: Effect): List<Aspect> = when (effect) {
        is Effect.Sets -> barePlacesOfParameter(effect.parameter)
        is Effect.Bends -> barePlacesOfParameter(effect.parameter)
        is Effect.Chooses -> listOf(effect.aspect)
        is Effect.Admits -> listOf(effect.aspect)
        is Effect.Excludes -> listOf(effect.aspect)
        is Effect.Restricts -> listOf(effect.aspect)
        is Effect.Leans -> listOf(effect.aspect)
        is Effect.LeansEverywhere -> Aspect.entries.filter { aspect -> likesSomethingIn(aspect, effect.weights) }
        is Effect.Mints -> listOf(Aspect.FEATURES)
        is Effect.Template, is Effect.Abundance, is Effect.MadeOf -> emptyList()
    }

    private fun barePlacesOfParameter(key: ParameterKey): List<Aspect> {
        fun turnedOnlyWhenAimed(aspect: Aspect) =
            key.fencedTo == null && aspect.understood.any { it.name == key.name && it.turnsOnlyWhenAimed }
        return Aspect.entries.filter { key.landsOn(it) && !turnedOnlyWhenAimed(it) }
    }

    private fun likesSomethingIn(aspect: Aspect, weights: Map<String, Double>): Boolean {
        fun liked(member: Taggable): Boolean {
            val carried = tags.tagsOf(member)
            fun names(key: String) = key == member.key || carried.containsKey(key.removePrefix(Materials.QUERY_MARK))
            return weights.any { (key, weight) -> weight > 0.0 && names(key) }
        }
        return tags.availableToBroadWordsIn(aspect).any(::liked)
    }
}
