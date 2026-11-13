package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.MapCodec
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Every concept, kind and word the packs define, each checked against the others: a word offering a concept nobody
 * defined, or a value its concept cannot carry, is reported rather than loaded half-understood.
 */
data class Words(
    val concepts: Map<Identifier, Concept>,
    val kinds: Map<Identifier, Kind>,
    /** By what a writer says: `frozen`, whichever pack defined it. */
    val byName: Map<String, Word>,
    /** What could not be read or did not fit, in the words a content author needs to hear. Empty in a healthy pack. */
    val problems: List<String>,
) {
    fun word(name: String): Word? = byName[name]

    companion object {
        const val CONCEPT_DIRECTORY = "art/concept"
        const val KIND_DIRECTORY = "art/kind"
        const val WORD_DIRECTORY = "art/word"

        /** What the packs in [resources] define, checked; [behaviours] are the structure words' code there is. */
        fun load(resources: ResourceManager, behaviours: Set<Identifier>): Words {
            val problems = mutableListOf<String>()
            val concepts = read(resources, CONCEPT_DIRECTORY, Concept::mapCodec, problems)
            val kinds = read(resources, KIND_DIRECTORY, Kind::mapCodec, problems)
            val words = read(resources, WORD_DIRECTORY, Word::mapCodec, problems)
            return of(concepts, kinds, words, behaviours, problems)
        }

        /** [concepts], [kinds] and [words] checked against each other, each word's senses given their kinds. */
        fun of(
            concepts: List<Concept>,
            kinds: List<Kind>,
            words: List<Word>,
            behaviours: Set<Identifier>,
            alreadyFound: List<String> = emptyList(),
        ): Words {
            val problems = alreadyFound.toMutableList()
            val conceptsById = concepts.associateBy(Concept::id)
            val kindsById = kinds.associateBy(Kind::id)
            val check = Checking(conceptsById, kindsById, behaviours, problems)
            kinds.forEach(check::kind)
            val byName = mutableMapOf<String, Word>()
            for (word in words) {
                val existing = byName[word.name]
                if (existing != null && existing.id != word.id) {
                    problems += "two words are both called '${word.name}': ${existing.id} and ${word.id}"
                }
                byName[word.name] = check.word(word)
            }
            return Words(conceptsById, kindsById, byName, problems)
        }

        private fun <T> read(
            resources: ResourceManager,
            directory: String,
            codecFor: (Identifier) -> MapCodec<T>,
            problems: MutableList<String>,
        ): List<T> = resources.listResources(directory, ResourceParsing::isJson).mapNotNull { (file, resource) ->
            val id = Identifier.fromNamespaceAndPath(file.namespace, ResourceParsing.nameUnder(file, directory))
            ResourceParsing.parse(resource, file, codecFor(id).codec(), problems)
        }
    }
}

/** The checks one load makes, gathering what it finds into [problems]. */
private class Checking(
    private val concepts: Map<Identifier, Concept>,
    private val kinds: Map<Identifier, Kind>,
    private val behaviours: Set<Identifier>,
    private val problems: MutableList<String>,
) {
    fun kind(kind: Kind) {
        kind.accepts.forEach { acceptance(it, "the kind ${kind.id}") }
    }

    /** [word] with its senses given their kinds, having reported whatever in it does not fit. */
    fun word(word: Word): Word {
        val where = "the word ${word.id}"
        if (word.senses.isEmpty()) problems += "$where has no sense"
        if (word.senses.count(Sense::isDefault) > 1) problems += "$where marks more than one sense as its default"
        return word.copy(senses = word.senses.map { sense(it, where) })
    }

    private fun sense(sense: Sense, where: String): Sense {
        sense.offers.forEach { offer(it, where) }
        sense.accepts.forEach { acceptance(it, where) }
        sense.behaviour?.takeIf { it !in behaviours }?.let { problems += "$where runs as '${PackNames.spell(it)}', which no structure word is" }
        val kindId = sense.kind ?: return sense
        val kind = kinds[kindId]
        if (kind == null) {
            problems += "$where is of the kind '${PackNames.spell(kindId)}', which no pack defines"
            return sense
        }
        return sense.withKind(kind)
    }

    private fun offer(offer: Offer, where: String) {
        val concept = known(offer.concept, where) ?: return
        if (!concept.values.admits(offer.value)) {
            problems += "$where offers ${PackNames.spell(concept.id)} a value of the wrong kind: ${offer.value} is no ${concept.values.serializedName}"
        }
    }

    private fun acceptance(acceptance: Acceptance, where: String) {
        known(acceptance.concept, where)
        (acceptance.outcome as? Outcome.Renames)?.let { known(it.concept, where) }
    }

    private fun known(id: Identifier, where: String): Concept? =
        concepts[id] ?: null.also { problems += "$where names the concept '${PackNames.spell(id)}', which no pack defines" }
}
