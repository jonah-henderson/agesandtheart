package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.RANGED_PARAMETERS
import co.voik.agesandtheart.age.aspect.namesARegistryEntry
import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.word.MaterialTable
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Kind
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueFilter
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words

/**
 * What in [words] the world cannot honour, the second half of loading after [Words.of] has checked the words against each
 * other. Every effect a word could land is checked against the parts of the world, their members, the tag layer and the
 * blocks: what a sense means itself, its readings, its own acceptances, and each value it offers as every sense that
 * could take it would turn it. A setting turned through a sense's acceptance is the sense's to answer for; a value that
 * setting cannot take is the offering word's. [tagsOnlyAServerGrants] are the tags offline cannot know, which a word may
 * speak of unchallenged.
 */
class WordsInTheWorld(
    private val words: Words,
    private val vocabulary: Vocabulary,
    membership: Membership,
    private val tagsOnlyAServerGrants: Set<String> = vocabulary.tagsOnlyAServerGrants,
) {
    private val matching = Matching(membership)

    private val knownTags: Set<String> by lazy { vocabulary.carriedTags + tagsOnlyAServerGrants }

    /** Every sense that takes anything, once per part and set of acceptances, with whether it is the Age's. */
    private val takers: List<Pair<Sense, Boolean>> by lazy {
        words.all.flatMap(Word::senses).filter { it.acceptances.isNotEmpty() }
            .distinctBy { it.speaksFor to it.acceptances }
            .map { it to (it.behaviour == StructureWord.Age.id) }
    }

    /** Every problem, kinds' and words' alike. */
    val problems: List<Words.Problem> by lazy {
        (words.kinds.values.flatMap(::problemsOf) + words.all.flatMap(::problemsOf)).distinct()
    }

    /** What the world cannot honour in [kind]'s acceptances. */
    fun problemsOf(kind: Kind): List<Words.Problem> {
        val found = kind.accepts.flatMap { acceptanceProblems(it, kind.part) } + kind.accepts.flatMap(::filterProblems)
        return found.distinct().map { Words.Problem(kind.id, "the kind ${kind.id}", it) }
    }

    /** What the world cannot honour in [word]: everything it could land, and every tag it speaks of. */
    fun problemsOf(word: Word): List<Words.Problem> {
        val landed = word.senses.flatMap(::landable)
        val context = landed.map(Landable::effect)
        val found = word.senses.flatMap { sense ->
            sense.accepts.flatMap { acceptanceProblems(it, sense.speaksFor) } + sense.accepts.flatMap(::filterProblems) +
                sense.offers.flatMap(::offeredTagProblems)
        } + landed.flatMap { landable -> effectProblems(landable.effect, context).map { "${landable.how}: $it" } }
        return found.distinct().map { Words.Problem(word.id, "the word ${word.id}", it) }
    }

    /** An effect a word could land, and how it comes to: "it means", "as the clouds take its size". */
    private data class Landable(val effect: Effect, val how: String)

    private fun landable(sense: Sense): List<Landable> {
        val part = sense.speaksFor
        val meant = sense.means.map { Landable(EffectTemplate.fencedTo(it, part), "what it means") }
        val readings = sense.offers.filter(Offer::isAReading).flatMap { offer ->
            val readingPart = (offer.audience as? Audience.Part)?.aspect
            offer.reading.map { Landable(EffectTemplate.fencedTo(it, readingPart), "its reading of the ${readingPart?.page}") }
        }
        val taken = sense.offers.filterNot(Offer::isAReading).flatMap(::takenAsEffects)
        return meant + readings + taken
    }

    /** Every sense that could take [offer], with how it would: the Age's own, the parts' pages and every thing's. */
    fun sensesTaking(offer: Offer): List<Pair<Sense, Acceptance>> = takers.mapNotNull { (taker, isTheAge) ->
        val acceptance = matching.acceptanceFor(taker, offer, isTheAge)
        val fits = acceptance != null && matching.valueFits(taker, acceptance, offer)
        if (acceptance != null && fits) taker to acceptance else null
    }

    /** What [offer]'s value becomes in every sense that could take it, through the acceptance's own taking effects. */
    private fun takenAsEffects(offer: Offer): List<Landable> = sensesTaking(offer).flatMap { (taker, acceptance) ->
        val value = filled(offer)
        val takenBy = taker.speaksFor?.page ?: "Age"
        takingTemplates(acceptance).mapNotNull { it.applied(value, taker.speaksFor) }
            .map { Landable(it, "its ${offer.concept.path} '${spelled(offer.value)}', as the $takenBy takes it") }
    }

    private fun filled(offer: Offer): Value {
        val parts = offer.value as? Value.Parts ?: return offer.value
        return words.concepts[offer.concept]?.filled(parts) ?: parts
    }

    /** The effects of [acceptance] that take the offer's value, a part's choice among them included. */
    private fun takingTemplates(acceptance: Acceptance): List<EffectTemplate> {
        fun takesTheValue(template: EffectTemplate): Boolean = when (template) {
            is EffectTemplate.Fixed -> false
            is EffectTemplate.Taking -> true
            is EffectTemplate.When -> takesTheValue(template.template)
        }
        return (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty().filter(::takesTheValue)
    }

    /** What an acceptance turns, whatever value it is given: its fixed effects, and the settings its taking effects name. */
    private fun acceptanceProblems(acceptance: Acceptance, part: Aspect?): List<String> {
        val templates = (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty()
        val concept = acceptance.concept.path
        fun problemsOf(template: EffectTemplate): List<String> = when (template) {
            is EffectTemplate.Fixed -> effectProblems(EffectTemplate.fencedTo(template.effect, part), emptyList())
            is EffectTemplate.Taking -> targetProblems(template, part)
            is EffectTemplate.When -> problemsOf(template.template)
        }
        return templates.flatMap(::problemsOf).map { "taking $concept: $it" }
    }

    /** A setting a taking effect turns that is nowhere, or that nothing acts on. */
    private fun targetProblems(template: EffectTemplate.Taking, part: Aspect?): List<String> {
        val turnsASetting = template.verb == EffectTemplate.Verb.SETS || template.verb == EffectTemplate.Verb.BENDS
        if (!turnsASetting) return emptyList()
        val written = ParameterKey.read(template.target)
        val key = if (written.fencedTo == null && part != null) ParameterKey(written.name, part) else written
        return listOfNotNull(placeProblem(key))
    }

    private fun filterProblems(acceptance: Acceptance): List<String> {
        val tags = when (val filter = acceptance.only) {
            ValueFilter.Anything -> emptySet()
            is ValueFilter.OneOf -> tagsAmong(filter.keys)
            is ValueFilter.Clearing -> filter.bars.keys
        }
        return unknownTagProblems(tags).map { "taking ${acceptance.concept.path} only $it" }
    }

    private fun offeredTagProblems(offer: Offer): List<String> {
        val tags = when (val value = offer.value) {
            is Value.Text -> tagsAmong(setOf(value.text))
            is Value.Weights -> tagsAmong(value.weights.keys)
            is Value.Bars -> value.bars.keys
            else -> emptySet()
        }
        return unknownTagProblems(tags).map { "offering ${offer.concept.path} $it" }
    }

    private fun effectProblems(effect: Effect, context: List<Effect>): List<String> = when (effect) {
        is Effect.Sets -> setProblems(effect.parameter, effect.value)
        is Effect.Bends -> listOfNotNull(placeProblem(effect.parameter) ?: rangeProblem(effect.parameter))
        is Effect.Chooses -> memberProblems(effect.aspect, setOf(effect.member))
        is Effect.Admits -> memberProblems(effect.aspect, effect.members)
        is Effect.Excludes -> unknownTagProblems(tagsAmong(effect.members))
        is Effect.Restricts -> unknownTagProblems(effect.bars.keys) + restrictionProblems(effect, context)
        is Effect.Leans -> unknownTagProblems(tagsAmong(effect.weights.keys))
        is Effect.LeansEverywhere -> unknownTagProblems(tagsAmong(effect.weights.keys))
        else -> emptyList()
    }

    /** A setting no part of the world has, or that nothing in the parts that have it acts on. */
    private fun placeProblem(key: ParameterKey): String? {
        val landsOn = Aspect.entries.filter { key.landsOn(it) && it.ownsParameterNamed(key.name) }
        return when {
            landsOn.isEmpty() -> "'${key.spelled}' is no setting of ${key.fencedTo?.let { "the ${it.page}" } ?: "any part of the world"}"
            landsOn.none { vocabulary.turnsAParameter(it, key.name) } -> "'${key.spelled}' is declared and acted on by nothing"
            else -> null
        }
    }

    private fun rangeProblem(key: ParameterKey): String? =
        if (key.name in RANGED_PARAMETERS) null else "'${key.spelled}' has no range to bend"

    private fun setProblems(key: ParameterKey, value: String): List<String> {
        placeProblem(key)?.let { return listOf(it) }
        val turnedOn = Aspect.entries.filter { key.landsOn(it) && it.ownsParameterNamed(key.name) && vocabulary.turnsAParameter(it, key.name) }
        val alternatives = value.split(ALTERNATIVES).map(String::trim).filter(String::isNotEmpty)
        fun isTaken(one: String) = turnedOn.any { aspect -> parametersCalled(key.name, aspect).any { it.acceptsFromAWord(one) } }
        val untaken = alternatives.filterNot(::isTaken).map { "'${key.spelled}' does not take '$it'" }
        val unanswered = alternatives.filter { MaterialTable.asksByTag(key.spelled, it) }
            .filter { vocabulary.materials.carriersOf(it.removePrefix(Materials.QUERY_MARK)).isEmpty() }
            .map { "no block a world can be made of answers $it" }
        return untaken + unanswered
    }

    private fun parametersCalled(name: String, aspect: Aspect) =
        (vocabulary.candidatesFor(aspect).flatMap { it.ownParameters } + aspect.parameters).filter { it.name == name }

    /** A member of ours is a bare key, a registry entry a namespaced id. */
    private fun memberProblems(aspect: Aspect, members: Set<String>): List<String> = members
        .filterNot { aspect.ownsPresetNamed(it) || (aspect.presetsAreEntriesOf != null && namesARegistryEntry(it)) }
        .map { "the ${aspect.page} has no '$it'" }

    /**
     * A narrowing nothing in its part meets. Two do another job and are exempt: one beside a setting the word turns there
     * steers rather than chooses, and one wanting a tag of an opposed pair says what the word means in tag space.
     */
    private fun restrictionProblems(restricts: Effect.Restricts, context: List<Effect>): List<String> {
        val aspect = restricts.aspect
        val wanted = restricts.bars.filterValues(Bars::isAtLeast).keys
        fun isOpposable(tag: String) = vocabulary.opposedTags.any { it.first == tag || it.second == tag }
        val onlyAServerCouldAnswer = wanted.isNotEmpty() && wanted.all(tagsOnlyAServerGrants::contains)
        val turned = context.mapNotNull { (it as? Effect.Sets)?.parameter ?: (it as? Effect.Bends)?.parameter }
        val steersHere = turned.any { it.landsOn(aspect) && vocabulary.turnsAParameter(aspect, it.name) }
        if (onlyAServerCouldAnswer || steersHere || wanted.any(::isOpposable)) return emptyList()
        val admittedHere = context.filterIsInstance<Effect.Admits>().filter { it.aspect == aspect }.flatMap { it.members }.mapNotNull(aspect::presetFor)
        val pool = vocabulary.availableToBroadWordsIn(aspect) + admittedHere
        if (pool.any { Bars.cleared(vocabulary.tagsOf(it), restricts.bars) }) return emptyList()
        val said = restricts.bars.entries.joinToString { (tag, bar) -> "#$tag ${Bars.spell(bar)}" }
        return listOf("nothing in the ${aspect.page} meets $said")
    }

    /** The tag layer's tags among [keys]: `#frozen`, never a block tag such as `#minecraft:ice`. */
    private fun tagsAmong(keys: Collection<String>): Set<String> =
        keys.filter(Materials::isQuery).map { it.removePrefix(Materials.QUERY_MARK) }.toSet()

    /** A tag no member carries, no block answers and only a server might grant. */
    private fun unknownTagProblems(tags: Collection<String>): List<String> = tags
        .filterNot { it in knownTags || vocabulary.materials.carriersOf(it).isNotEmpty() }
        .sorted().map { "speaks of #$it, which nothing carries" }

    private fun spelled(value: Value): String = when (value) {
        is Value.Text -> value.text
        is Value.Amount -> value.multiple.toString()
        is Value.Parts -> value.parts.values.joinToString(" ")
        else -> value.toString()
    }

    private companion object {
        const val ALTERNATIVES = '|'
    }
}
