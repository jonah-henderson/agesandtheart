package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.reading.WordsInTheWorld
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.Concept
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueFilter
import co.voik.agesandtheart.age.words.ValueType
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier

/**
 * What the Art cannot yet say, read off the words there are: a concept nothing offers or nothing takes, a setting no
 * acceptance or meaning turns, a value of one nothing says, and a tag something carries and nothing speaks of.
 */
class Gaps(private val corpus: WordCorpus) {

    enum class Kind(val title: String) {
        UNOFFERED("no offer"),
        UNTAKEN("no taker"),
        PARAMETER("setting"),
        VALUE("value"),
        TAG("tag"),
    }

    data class Gap(val kind: Kind, val what: String, val where: List<Aspect>, val said: String, val parameter: String = "") {
        val key: String get() = "${kind.name}/$parameter/$what"
    }

    val all: List<Gap> by lazy { unoffered() + untaken() + parameters() + values() + tags() }

    private val words get() = corpus.words

    private val vocabulary get() = corpus.vocabulary

    private val world: WordsInTheWorld by lazy { WordsInTheWorld(words, vocabulary, corpus.membership, corpus.tagsOnlyAServerGrants) }

    private val senses: List<Sense> by lazy { words.all.flatMap(Word::senses) }

    private val offers: List<Offer> by lazy { senses.flatMap(Sense::offers) }

    /** Every acceptance there is, with the part it is said of: each sense's own and inherited, and each kind's. */
    private val acceptances: List<Pair<Acceptance, Aspect?>> by lazy {
        senses.flatMap { sense -> sense.acceptances.map { it to sense.speaksFor } } +
            words.kinds.values.flatMap { kind -> kind.accepts.map { it to kind.part } }
    }

    /** The concepts words offer and things take, leaving out those the reader makes itself. */
    private val concepts: List<Concept>
        get() = words.concepts.values.filter { it.id !in MADE_BY_THE_READER }.sortedBy { it.id.path }

    private fun unoffered(): List<Gap> {
        val renamedTo = acceptances.mapNotNull { (it.first.outcome as? Outcome.Renames)?.concept }
        val offered = offers.filterNot(Offer::isAReading).map(Offer::concept).toSet() + renamedTo
        return concepts.filter { it.id !in offered }.map { concept ->
            val takenBy = acceptances.filter { it.first.concept == concept.id }
            Gap(Kind.UNOFFERED, concept.id.path, takenBy.mapNotNull { it.second }.distinct(), "taken by ${takenBy.size} acceptance(s), offered by no word")
        }
    }

    private fun untaken(): List<Gap> {
        val taken = acceptances.map { it.first.concept }.toSet()
        return concepts.filter { it.id !in taken }.map { concept ->
            val offering = words.all.count { word -> word.senses.any { sense -> sense.offers.any { it.concept == concept.id } } }
            Gap(Kind.UNTAKEN, concept.id.path, emptyList(), "offered by $offering word(s), taken by nothing")
        }
    }

    /** Every effect a word could land: what senses mean, readings, and every offer as each thing taking it turns it. */
    private val effectsSaid: List<Effect> by lazy {
        val meant = senses.flatMap { sense -> sense.means.map { EffectTemplate.fencedTo(it, sense.speaksFor) } }
        val readings = offers.filter(Offer::isAReading).flatMap { offer ->
            offer.reading.map { EffectTemplate.fencedTo(it, (offer.audience as? Audience.Part)?.aspect) }
        }
        val taken = offers.filterNot(Offer::isAReading).flatMap { offer -> world.takenAs(offer).flatMap(WordsInTheWorld.Taken::effects) }
        val fixed = acceptances.flatMap { (acceptance, part) -> templatesOf(acceptance).filterIsInstance<EffectTemplate.Fixed>().map { EffectTemplate.fencedTo(it.effect, part) } }
        (meant + readings + taken + fixed).distinct()
    }

    private fun templatesOf(acceptance: Acceptance): List<EffectTemplate> {
        fun unwrapped(template: EffectTemplate): EffectTemplate = if (template is EffectTemplate.When) unwrapped(template.template) else template
        return (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty().map(::unwrapped)
    }

    /** What some acceptance's taking effect sets or bends, whatever value reaches it. */
    private val settingsTaken: List<Pair<Acceptance, EffectTemplate.Taking>> by lazy {
        acceptances.flatMap { (acceptance, _) -> templatesOf(acceptance).filterIsInstance<EffectTemplate.Taking>().map { acceptance to it } }
            .filter { (_, template) -> template.verb == EffectTemplate.Verb.SETS || template.verb == EffectTemplate.Verb.BENDS }
    }

    private val settingsSaid: List<Pair<String, String?>> by lazy {
        effectsSaid.mapNotNull { effect ->
            when (effect) {
                is Effect.Sets -> effect.parameter.name to effect.value
                is Effect.Bends -> effect.parameter.name to null
                else -> null
            }
        }
    }

    private fun parameters(): List<Gap> {
        val turned = settingsSaid.map { it.first }.toSet() + settingsTaken.map { ParameterKey.read(it.second.target).name }
        return everyParameter().filterNot { (name, _) -> name in turned }.map { (name, owners) ->
            Gap(Kind.PARAMETER, name, owners.map { it.on }.distinct(), owners.first().parameter.help.ifBlank { "nothing turns it" })
        }
    }

    private fun values(): List<Gap> {
        val said = settingsSaid.flatMap { (name, value) -> value?.split(ALTERNATIVES)?.map { name to it.trim() }.orEmpty() }.toSet()
        fun isSaid(name: String, option: String) = (name to option) in said || isOfferedToward(name, option)
        return everyParameter().flatMap { (name, owners) ->
            val parameter = owners.first().parameter
            if (parameter.open || parameter.holds != Holds.CATALOGUE) return@flatMap emptyList()
            parameter.options.drop(1).filterNot { option -> isSaid(name, option) }.map { option ->
                Gap(
                    Kind.VALUE, option, owners.map { it.on }.distinct(),
                    parameter.optionHelp[option].orEmpty().ifBlank { "no word sets $name to it" }, parameter = name,
                )
            }
        }
    }

    /**
     * Whether a word offers [option] under a concept an acceptance sets [parameter] from, or one passed on into such a
     * concept: `north` is a direction, which `rising` passes on as a rising, which the sun sets its rising from.
     */
    private fun isOfferedToward(parameter: String, option: String): Boolean {
        fun setsIt(template: EffectTemplate.Taking) = template.verb == EffectTemplate.Verb.SETS && ParameterKey.read(template.target).name == parameter
        val setting = settingsTaken.filter { setsIt(it.second) }.map { it.first.concept }.toSet()
        val reaching = generateSequence(setting) { reached -> (reached + passedOnInto(reached)).takeIf { it.size > reached.size } }.last()
        return offers.any { it.concept in reaching && (it.value as? Value.Text)?.text == option }
    }

    /** The concepts an acceptance passes on into one of [concepts]: renamed as one, or folded into a sense's offer of one. */
    private fun passedOnInto(concepts: Set<Identifier>): Set<Identifier> {
        val renamed = acceptances.map { it.first }.filter { (it.outcome as? Outcome.Renames)?.concept in concepts }.map(Acceptance::concept)
        val folded = senses.filter { sense -> sense.offers.any { it.concept in concepts } }
            .flatMap { sense -> sense.acceptances.filter { it.outcome == Outcome.Folds }.map(Acceptance::concept) }
        return (renamed + folded).toSet()
    }

    private fun tags(): List<Gap> {
        val spoken = tagsSpoken()
        return (vocabulary.carriedTags - spoken).sorted().map { tag ->
            fun carries(aspect: Aspect) = vocabulary.candidatesFor(aspect).count { tag in vocabulary.tagsOf(it) }
            val carrying = Aspect.entries.filter { carries(it) > 0 }
            Gap(Kind.TAG, tag, carrying, "carried by ${carrying.sumOf(::carries)} thing(s), spoken of by nothing")
        }
    }

    /** Every tag a filter, an offer's value or a landed effect speaks of. */
    private fun tagsSpoken(): Set<String> {
        val inFilters = acceptances.flatMap { (acceptance, _) ->
            when (val filter = acceptance.only) {
                ValueFilter.Anything -> emptyList()
                is ValueFilter.OneOf -> tagsAmong(filter.keys)
                is ValueFilter.Clearing -> filter.bars.keys
            }
        }
        val inValues = offers.flatMap { offer ->
            when (val value = offer.value) {
                is Value.Text -> tagsAmong(setOf(value.text))
                is Value.Weights -> tagsAmong(value.weights.keys)
                is Value.Bars -> value.bars.keys
                else -> emptySet()
            }
        }
        val inEffects = effectsSaid.flatMap { effect ->
            when (effect) {
                is Effect.Restricts -> effect.bars.keys
                is Effect.Leans -> tagsAmong(effect.weights.keys)
                is Effect.LeansEverywhere -> tagsAmong(effect.weights.keys)
                is Effect.Excludes -> tagsAmong(effect.members)
                is Effect.Admits -> tagsAmong(effect.members)
                else -> emptySet()
            }
        }
        return (inFilters + inValues + inEffects).toSet()
    }

    private fun tagsAmong(keys: Collection<String>): Set<String> =
        keys.filter(Materials::isQuery).map { it.removePrefix(Materials.QUERY_MARK) }.toSet()

    private data class Owned(val on: Aspect, val parameter: Parameter)

    private fun everyParameter(): List<Pair<String, List<Owned>>> = Aspect.entries
        .flatMap { aspect ->
            val ownedByMembers = vocabulary.candidatesFor(aspect).flatMap { member -> member.ownParameters.filter(member::honours) }
            (aspect.parameters + ownedByMembers).map { Owned(aspect, it) }
        }
        .filterNot { it.parameter.name == Parameter.CAST || it.parameter == Spawns.CAPS || it.parameter.name.startsWith(FORMER) }
        .groupBy { it.parameter.name }
        .map { (name, owned) -> name to owned }
        .sortedBy { it.first }

    /**
     * A word begun for [gap], offering what it lacks; null where the gap wants an acceptance on a part or a kind instead —
     * a concept nothing takes, a setting nothing turns, or a value no acceptance would set — or where every name it could
     * be given is a word's already.
     */
    fun wordFor(gap: Gap): Candidate? {
        val offer = when (gap.kind) {
            Kind.UNOFFERED -> words.concepts.values.firstOrNull { it.id.path == gap.what }?.let { Offer(it.id, startingValue(it)) }
            Kind.VALUE -> offerSetting(gap.parameter, gap.what)
            Kind.TAG -> Offer(CoreConcepts.BAR, Value.Bars(mapOf(gap.what to TAG_BAR)), audience = gap.where.singleOrNull()?.let(Audience::Part) ?: Audience.Anyone)
            Kind.UNTAKEN, Kind.PARAMETER -> null
        } ?: return null
        val name = listOf(gap.what, "${gap.parameter}_${gap.what}").firstOrNull { words.word(it) == null } ?: return null
        return Candidate(Word(name.location(), listOf(Sense(offers = listOf(offer)))))
    }

    /** An offer some acceptance would set [parameter] to [option] from: the option itself, or what its table reads as it. */
    private fun offerSetting(parameter: String, option: String): Offer? = settingsTaken.firstNotNullOfOrNull { (acceptance, template) ->
        val setsIt = template.verb == EffectTemplate.Verb.SETS && ParameterKey.read(template.target).name == parameter
        val given = when {
            !setsIt -> null
            template.values.isEmpty() -> option
            else -> template.values.entries.firstOrNull { it.value == option }?.key
        }
        given?.let { Offer(acceptance.concept, Value.Text(it)) }
    }

    private fun startingValue(concept: Concept): Value = when (concept.values) {
        ValueType.SPAN -> Value.Text(WHOLE_TOP)
        ValueType.AMOUNT -> Value.Amount(TWICE_AS_MANY)
        else -> Value.None
    }

    private companion object {
        const val ALTERNATIVES = '|'

        /** What begins a setting's name that only an Age written before a generator change holds, and no word says. */
        const val FORMER = "former_"

        /** The concepts the reader makes itself: a ban from `no` before a block, and the polarity `no` and `only` offer. */
        val MADE_BY_THE_READER = setOf(CoreConcepts.READING, CoreConcepts.BAN, "polarity".location())

        /** The bar a word begun for a tag asks its members to clear. */
        const val TAG_BAR = 0.3

        /** The top of an axis, which a word asking for more of something starts from. */
        const val WHOLE_TOP = "0.5..1.0"

        const val TWICE_AS_MANY = 2.0
    }
}
