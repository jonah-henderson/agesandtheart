package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
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
    /**
     * By what a writer says: `frozen`, whichever pack defined it, every sense under that name gathered into one word. A
     * derived word is also under its whole id, which writes it alone.
     */
    val byName: Map<String, Word>,
    /** What could not be read or did not fit, in the words a content author needs to hear. Empty in a healthy pack. */
    val problems: List<Problem>,
    /** The ids of the words every block, biome, feature, structure and creature was given, rather than a pack wrote. */
    val derived: Set<Identifier> = emptySet(),
) {
    fun word(name: String): Word? = byName[name]

    /** Every word once, whatever it is listed under. */
    val all: List<Word> by lazy { byName.values.distinctBy(Word::id).sortedBy { it.id.toString() } }

    /** The page that writes [word]: its name, or its whole id where its name is another's. */
    fun pageFor(word: Word): String = if (byName[word.name]?.id == word.id) word.name else word.id.toString()

    /** Every problem with [word] itself. */
    fun problemsOf(word: Word): List<Problem> = problems.filter { it.about == word.id }

    /** What [word] costs to write ([Price]). */
    fun priceOf(word: Word): Int = Price.of(word, concepts)

    /** The word that is [part]'s own page — `sky`, `landmass` — which `age` hands what it takes to. */
    fun partPage(part: Aspect): Word? = byName[part.page]?.takeIf { it.id.namespace == Constants.MOD_ID }

    companion object {
        /** No words at all, which is what a vocabulary is stood up with before its words are read. */
        val NONE = Words(emptyMap(), emptyMap(), emptyMap(), emptyList())

        const val CONCEPT_DIRECTORY = "art/concept"
        const val KIND_DIRECTORY = "art/kind"
        const val WORD_DIRECTORY = "art/words"

        /**
         * What the packs in [resources] define, checked; [behaviours] are the structure words' code there is, and
         * [derived] the words every block, biome, feature, structure and creature is given ([DerivedWords]).
         */
        fun load(resources: ResourceManager, behaviours: Set<Identifier>, derived: List<Word> = emptyList()): Words {
            val problems = mutableListOf<Problem>()
            val concepts = read(resources, CONCEPT_DIRECTORY, Concept::mapCodec, problems)
            val kinds = read(resources, KIND_DIRECTORY, Kind::mapCodec, problems)
            val words = read(resources, WORD_DIRECTORY, Word::mapCodec, problems)
            return of(concepts, kinds, words, behaviours, derived, problems)
        }

        /**
         * [concepts], [kinds] and [words] checked against each other, each word's senses given their kinds. Each [derived]
         * word joins the word of its name, adding the senses the neighbours could choose between and no other — a block
         * and a placed feature, never two blocks — the pack's own first, then vanilla's, ours, and other packs' by id.
         */
        fun of(
            concepts: List<Concept>,
            kinds: List<Kind>,
            words: List<Word>,
            behaviours: Set<Identifier>,
            derived: List<Word> = emptyList(),
            alreadyFound: List<Problem> = emptyList(),
        ): Words {
            val problems = alreadyFound.toMutableList()
            val conceptsById = concepts.associateBy(Concept::id)
            val kindsById = (words.mapNotNull(::asAKind) + kinds).associateBy(Kind::id)
            val check = Checking(conceptsById, kindsById, behaviours, problems)
            kinds.forEach(check::kind)
            val byName = mutableMapOf<String, Word>()
            for (word in words) {
                val existing = byName[word.name]
                if (existing != null && existing.id != word.id) {
                    problems += Problem(word.id, "the word ${word.id}", "has the name of ${existing.id}, and only one of them is read")
                }
                byName[word.name] = check.word(word)
            }
            for (word in derived.sortedWith(DERIVED_ORDER).map(check::word)) {
                val existing = byName[word.name]
                val joined = existing?.withSensesOf(word) ?: word
                byName[word.name] = joined
                byName[word.id.toString()] = if (existing?.id == word.id) joined else byName[word.id.toString()]?.withSensesOf(word) ?: word
            }
            return Words(conceptsById, kindsById, byName, problems, derived.map(Word::id).toSet())
        }

        /**
         * A part's own page as a kind of its own name — `"kind": "features"` — so a group, which is its part narrowed to
         * some of its members, takes whatever its part takes: `trees` everything the features do.
         */
        private fun asAKind(word: Word): Kind? {
            val part = Aspect.byPage(word.name)?.takeIf { word.id.namespace == Constants.MOD_ID } ?: return null
            val page = word.senses.singleOrNull()?.takeIf { it.part == part } ?: return null
            return Kind(word.id, page.accepts, terminal = true, part = part)
        }

        /** Vanilla's first, then ours, then every other pack's, each by id: whose sense a shared name is read in by default. */
        private val DERIVED_ORDER: Comparator<Word> = compareBy<Word> { word ->
            when (word.id.namespace) {
                Identifier.DEFAULT_NAMESPACE -> 0
                Constants.MOD_ID -> 1
                else -> 2
            }
        }.thenBy { it.id.toString() }

        private fun <T> read(
            resources: ResourceManager,
            directory: String,
            codecFor: (Identifier) -> MapCodec<T>,
            problems: MutableList<Problem>,
        ): List<T> = resources.listResources(directory, ResourceParsing::isJson).mapNotNull { (file, resource) ->
            val id = Identifier.fromNamespaceAndPath(file.namespace, ResourceParsing.nameUnder(file, directory))
            val unread = mutableListOf<String>()
            ResourceParsing.parse(resource, file, codecFor(id).codec(), unread).also {
                problems += unread.map { says -> Problem(id, null, says) }
            }
        }
    }

    /**
     * Something that does not fit, about the word, kind or concept [about], said [where] it is — "the word
     * agesandtheart:frozen" — or by itself where the sentence names it already.
     */
    data class Problem(val about: Identifier?, val where: String?, val says: String) {
        override fun toString(): String = listOfNotNull(where, says).joinToString(" ")
    }
}

/** The checks one load makes, gathering what it finds into [problems]. */
private class Checking(
    private val concepts: Map<Identifier, Concept>,
    private val kinds: Map<Identifier, Kind>,
    private val behaviours: Set<Identifier>,
    private val problems: MutableList<Words.Problem>,
) {
    /** Where a problem was found: what it is about, and how a sentence names it. */
    private data class Where(val about: Identifier, val named: String)

    private fun report(where: Where, says: String) {
        problems += Words.Problem(where.about, where.named, says)
    }

    fun kind(kind: Kind) {
        kind.accepts.forEach { acceptance(it, Where(kind.id, "the kind ${kind.id}")) }
    }

    /** [word] with its senses given their kinds, having reported whatever in it does not fit. */
    fun word(word: Word): Word {
        val where = Where(word.id, "the word ${word.id}")
        if (word.senses.isEmpty()) report(where, "has no sense")
        if (word.senses.count(Sense::isDefault) > 1) report(where, "marks more than one sense as its default")
        return word.copy(senses = word.senses.map { sense(it, where) })
    }

    private fun sense(sense: Sense, where: Where): Sense {
        // A sense of a part takes that part's readings, whatever else it does.
        val saysNothing = sense.offers.isEmpty() && sense.accepts.isEmpty() && sense.means.isEmpty() &&
            sense.kind == null && sense.behaviour == null && sense.part == null
        if (saysNothing) report(where, "has a sense that offers, accepts and means nothing, and speaks for no part")
        sense.offers.forEach { offer(it, where) }
        sense.accepts.forEach { acceptance(it, where) }
        val acceptedTwice = sense.accepts.groupBy(Acceptance::concept).filterValues { it.size > 1 }.keys
        acceptedTwice.forEach { report(where, "accepts ${PackNames.spell(it)} twice, and only the first is ever read") }
        val resolved = sense.kind?.let(kinds::get)?.let(sense::withKind) ?: sense
        resolved.acceptances.forEach { outcomeFits(it, resolved.endsAPhrase, where) }
        sense.behaviour?.takeIf { it !in behaviours }?.let { report(where, "runs as '${PackNames.spell(it)}', which no structure word is") }
        val kindId = sense.kind ?: return sense
        val kind = kinds[kindId]
        if (kind == null) {
            report(where, "is of the kind '${PackNames.spell(kindId)}', which no pack defines")
            return sense
        }
        return sense.withKind(kind)
    }

    private fun offer(offer: Offer, where: Where) {
        if (offer.isAReading) return
        val concept = known(offer.concept, where) ?: return
        if (!concept.values.admits(offer.value)) {
            report(where, "offers ${PackNames.spell(concept.id)} a value of the wrong kind: ${offer.value} is no ${concept.values.serializedName}")
        }
        val parts = offer.value as? Value.Parts ?: return
        concept.problemWith(parts)?.let { report(where, "offers ${PackNames.spell(concept.id)} a value that does not fit: $it") }
    }

    /** A thing turns what it takes into effects or drops it; a describing word folds, renames, scales or drops it. */
    private fun outcomeFits(acceptance: Acceptance, endsAPhrase: Boolean, where: Where) {
        val outcome = acceptance.outcome
        val thingsOnly = outcome is Outcome.Becomes
        val describingOnly = outcome == Outcome.Folds || outcome is Outcome.Renames || outcome is Outcome.Scales
        val concept = PackNames.spell(acceptance.concept)
        if (thingsOnly && !endsAPhrase) report(where, "describes, and so cannot turn $concept into effects: only a thing does")
        if (describingOnly && endsAPhrase) report(where, "is a thing, and so cannot pass $concept on: a thing ends the phrase")
    }

    private fun acceptance(acceptance: Acceptance, where: Where) {
        val concept = known(acceptance.concept, where)
        val takesAValue = (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty().any { it is EffectTemplate.Taking }
        if (concept?.values == ValueType.FLAG && takesAValue) {
            report(where, "takes a value from ${PackNames.spell(concept.id)}, which carries none: its translation must be fixed")
        }
        (acceptance.outcome as? Outcome.Renames)?.let { known(it.concept, where) }
    }

    private fun known(id: Identifier, where: Where): Concept? =
        concepts[id] ?: null.also { report(where, "names the concept '${PackNames.spell(id)}', which no pack defines") }
}
