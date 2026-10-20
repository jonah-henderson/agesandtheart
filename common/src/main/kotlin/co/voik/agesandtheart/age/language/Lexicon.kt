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
 * Every word there is, the structure pages, and what each word takes — what the reading reads a book against.
 *
 * A word answers to its bare name and to its full id; where two share a name, the later in [words] keeps it, so a
 * word written by hand outranks one derived.
 */
class Lexicon(
    words: List<Word>,
    private val structural: Map<String, Structure>,
    private val tags: MemberTags,
    private val registries: HolderLookup.Provider?,
) {
    private val byName: Map<String, Word> = words.associateBy(Word::name)
    private val byId: Map<String, Word> = words.associateBy { it.id.toString() }
    private val acceptances = ConcurrentHashMap<Identifier, Optional<Acceptance>>()

    fun word(page: String): Word? = byId[page] ?: byName[page]

    fun structure(page: String): Structure? = structural[page]

    /** What [word] takes from the pages waiting before it, or null where it takes nothing. */
    fun acceptance(word: Word): Acceptance? =
        acceptances.computeIfAbsent(word.id) { Optional.ofNullable(WordAcceptance.of(word, tags, registries)) }.orElse(null)

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
