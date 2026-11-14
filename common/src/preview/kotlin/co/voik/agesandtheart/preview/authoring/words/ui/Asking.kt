package co.voik.agesandtheart.preview.authoring.words.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.Concept
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueType
import co.voik.agesandtheart.preview.authoring.ui.Band
import co.voik.agesandtheart.preview.authoring.ui.Picker
import co.voik.agesandtheart.preview.authoring.ui.Prompt
import co.voik.agesandtheart.preview.authoring.ui.Questions
import co.voik.agesandtheart.preview.authoring.words.WordCorpus
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * The questions writing a word asks — a concept, a value of it, who an offer is for, a chance, a part, a kind — each put
 * to [questions] and answered through a callback, so the editor says only what to do with the answer.
 */
class Asking(private val corpus: WordCorpus, private val questions: Questions) {

    private val words get() = corpus.words

    /** Every acceptance in the parts' pages and the kinds, with the part it speaks for: where a concept turns into settings. */
    private val takings: List<Pair<Aspect?, Acceptance>> by lazy {
        val pages = Aspect.entries.mapNotNull { part -> words.partPage(part)?.defaultSense?.let { part to it } }
            .flatMap { (part, page) -> page.accepts.map { part to it } }
        pages + words.kinds.values.flatMap { kind -> kind.accepts.map { kind.part to it } }
    }

    fun aConcept(title: String, then: (Concept) -> Unit) {
        val offered = corpus.written.flatMap { word -> word.senses.flatMap { it.offers } }.groupingBy(Offer::concept).eachCount()
        val options = words.concepts.values.sortedBy { it.id.path }.map { concept ->
            val said = offered[concept.id]?.let { "offered $it times" } ?: "offered by none of ours"
            Picker.Option(concept.id.toString(), concept.id.path, "${concept.values.serializedName}, $said")
        }
        questions.ask(Picker(title, options) { picked -> words.concepts[Identifier.parse(picked.value)]?.let(then) })
    }

    /** A value of [concept], asked as its kind of value is: on an axis, from its options, a block, a member, typed. */
    fun aValue(concept: Concept, standing: Value?, then: (Value) -> Unit) {
        val standingText = (standing as? Value.Text)?.text
        when (concept.values) {
            ValueType.SPAN -> questions.ask(Band(concept.id.path, axisOf(concept), howOursSay(concept), standingText.orEmpty()) { then(Value.Text(it)) })
            ValueType.OPTION -> anOption(concept.id.path, optionsOf(concept), standingText) { then(Value.Text(it)) }
            ValueType.BLOCK -> aBlock(concept.id.path, standingText) { then(Value.Text(it)) }
            ValueType.MEMBER -> aMember(concept.id.path, standingText) { then(Value.Text(it)) }
            ValueType.WEIGHTS -> typedPairs(concept, (standing as? Value.Weights)?.weights, ::weightOf) { then(Value.Weights(it)) }
            ValueType.BARS -> typedPairs(concept, (standing as? Value.Bars)?.bars, ::barOf) { then(Value.Bars(it)) }
            ValueType.AMOUNT -> anAmount(concept.id.path, (standing as? Value.Amount)?.multiple) { then(Value.Amount(it)) }
            ValueType.FLAG -> then(Value.None)
            ValueType.PARTS -> theParts(concept, (standing as? Value.Parts)?.parts.orEmpty(), then)
        }
    }

    fun anAudience(standing: Audience, then: (Audience) -> Unit) {
        val options = listOf(
            Picker.Option(ANYONE, "anyone", "the first word after it that takes it", marked = standing == Audience.Anyone),
            Picker.Option(THE_AGE, "the Age", "passing the things it meets: a template, a ban, no sun", marked = standing == Audience.TheAge),
        ) + Aspect.entries.mapIndexed { at, part ->
            Picker.Option(part.page, part.page, "only the ${part.page}'s page and its things", marked = standing == Audience.Part(part), startsGroup = at == 0)
        }
        questions.ask(Picker("For whom?", options) { picked ->
            val audience = when (picked.value) {
                ANYONE -> Audience.Anyone
                THE_AGE -> Audience.TheAge
                else -> Aspect.byPage(picked.value)?.let(Audience::Part)
            }
            audience?.let(then)
        })
    }

    /** A share from 0 to 1, or nothing where [mayBeNothing] and what nothing means is said in [hint]. */
    fun aChance(title: String, standing: Double?, hint: String, mayBeNothing: Boolean, then: (Double?) -> Unit) {
        questions.ask(
            Prompt(
                title = title,
                hint = hint,
                typed = standing?.toString().orEmpty(),
                complaint = { typed ->
                    val chance = typed.toDoubleOrNull()
                    val isAShare = chance != null && chance in Offer.NEVER..Offer.CERTAIN
                    if (isAShare || (typed.isBlank() && mayBeNothing)) null else "a share from 0 to 1"
                },
                onDone = { typed -> then(typed.toDoubleOrNull()) },
            ),
        )
    }

    /** A part of the world, or none where [noneMeans] says what none is. */
    fun aPart(title: String, standing: Aspect?, noneMeans: String?, then: (Aspect?) -> Unit) {
        val none = noneMeans?.let { listOf(Picker.Option(NONE, "none", it, marked = standing == null)) }.orEmpty()
        val parts = Aspect.entries.mapIndexed { at, part ->
            Picker.Option(part.page, part.page, "", marked = part == standing, startsGroup = at == 0 && none.isNotEmpty())
        }
        questions.ask(Picker(title, none + parts) { picked -> then(Aspect.byPage(picked.value)) })
    }

    /** A kind, whose acceptances a sense inherits, or none. */
    fun aKind(standing: Identifier?, then: (Identifier?) -> Unit) {
        val none = Picker.Option(NONE, "none", "it takes only what it says itself", marked = standing == null)
        val kinds = words.kinds.values.sortedBy { it.id.toString() }.mapIndexed { at, kind ->
            val about = listOfNotNull(kind.part?.page, if (kind.terminal == true) "a thing" else null, "${kind.accepts.size} acceptances")
            Picker.Option(kind.id.toString(), kind.id.path, about.joinToString(", "), marked = kind.id == standing, startsGroup = at == 0)
        }
        questions.ask(Picker("Of which kind?", listOf(none) + kinds) { picked -> then(Identifier.tryParse(picked.value).takeIf { picked.value != NONE }) })
    }

    // -- a value ---------------------------------------------------------------------------------------

    /**
     * The axis a span of [concept] is set against: the first setting a part turns it into, whose landmarks say what the
     * numbers mean there, or a bare one.
     */
    private fun axisOf(concept: Concept): Parameter =
        settingsTurnedBy(concept).firstNotNullOfOrNull { (key, _) -> parameterFor(key)?.takeIf { it.holds == Holds.RANGE } }
            ?: Parameter(concept.id.path, emptyList(), holds = Holds.RANGE)

    /** What [concept]'s acceptances take: the keys of a table they read it through, or the options of the setting it turns. */
    private fun optionsOf(concept: Concept): List<Picker.Option> = settingsTurnedBy(concept).flatMap { (key, table) ->
        val parameter = parameterFor(key)
        val options = table.keys.ifEmpty { parameter?.options.orEmpty() }
        options.map { option -> Picker.Option(option, option, parameter?.optionHelp?.get(option).orEmpty()) }
    }.distinctBy { it.value } + howOursSay(concept).mapIndexed { at, option -> option.copy(startsGroup = at == 0) }

    /** Each setting [concept] is turned into somewhere, with the table it is read through there. */
    private fun settingsTurnedBy(concept: Concept): List<Pair<ParameterKey, Map<String, String>>> = takings
        .filter { (_, acceptance) -> acceptance.concept == concept.id }
        .flatMap { (part, acceptance) ->
            (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty().mapNotNull(::takingOf).mapNotNull { taking ->
                val turnsASetting = taking.verb == EffectTemplate.Verb.SETS || taking.verb == EffectTemplate.Verb.BENDS
                val written = ParameterKey.read(taking.target)
                val key = if (written.fencedTo == null && part != null) ParameterKey(written.name, part) else written
                (key to taking.values).takeIf { turnsASetting }
            }
        }

    private fun takingOf(template: EffectTemplate): EffectTemplate.Taking? = when (template) {
        is EffectTemplate.Taking -> template
        is EffectTemplate.When -> takingOf(template.template)
        is EffectTemplate.Fixed -> null
    }

    private fun parameterFor(key: ParameterKey): Parameter? =
        Aspect.entries.filter(key::landsOn).firstNotNullOfOrNull { part -> part.understood.firstOrNull { it.name == key.name } }

    /** How our words already offer [concept], so "the same as `arid`" is a thing to pick. */
    private fun howOursSay(concept: Concept): List<Picker.Option> = corpus.written
        .flatMap { word -> word.senses.flatMap { it.offers }.filter { it.concept == concept.id }.map { word.name to it.value } }
        .mapNotNull { (name, value) -> (value as? Value.Text)?.let { name to it.text } }
        .distinctBy { it.second }
        .map { (name, value) -> Picker.Option(value, name, value) }

    private fun anOption(title: String, options: List<Picker.Option>, standing: String?, then: (String) -> Unit) {
        val marked = options.map { if (it.value == standing) it.copy(marked = true) else it }
        questions.ask(Picker("$title as what?", marked.ifEmpty { listOf(Picker.Option(NONE, "nothing takes this concept as an option")) }) { picked ->
            if (picked.value != NONE) then(picked.value)
        })
    }

    /** A block, or a tag of the tag layer it is drawn from: `#frozen`. */
    private fun aBlock(title: String, standing: String?, then: (String) -> Unit) {
        val vocabulary = corpus.vocabulary
        val tags = vocabulary.carriedTags.sorted().filter { vocabulary.materials.carriersOf(it).isNotEmpty() }.map { tag ->
            val spelled = "${Materials.QUERY_MARK}$tag"
            Picker.Option(spelled, spelled, "${vocabulary.materials.carriersOf(tag).size} blocks", marked = spelled == standing)
        }
        val blocks = BuiltInRegistries.BLOCK.keySet().map { it.toString() }.sorted().mapIndexed { at, block ->
            Picker.Option(block, block, marked = block == standing, startsGroup = at == 0)
        }
        questions.ask(Picker("$title: a block, or a tag it is drawn from", tags + blocks) { picked -> then(picked.value) })
    }

    /** A member of any part, or a tag the members carry. */
    private fun aMember(title: String, standing: String?, then: (String) -> Unit) {
        val vocabulary = corpus.vocabulary
        val members = Aspect.entries.flatMap { part -> vocabulary.candidatesFor(part).map { it.key to part.page } }.distinctBy { it.first }
        val tags = vocabulary.carriedTags.sorted().mapIndexed { at, tag ->
            val spelled = "${Value.TAG_MARK}$tag"
            Picker.Option(spelled, spelled, "every member carrying it", marked = spelled == standing, startsGroup = at == 0)
        }
        val options = members.map { (key, part) -> Picker.Option(key, key, "of the $part", marked = key == standing) } + tags
        questions.ask(Picker("$title: which member?", options) { picked -> then(picked.value) })
    }

    private fun anAmount(title: String, standing: Double?, then: (Double) -> Unit) {
        questions.ask(
            Prompt(
                title = "$title: a multiple of what there would be",
                hint = "4 for four times as many, 0.25 for a quarter",
                typed = standing?.toString().orEmpty(),
                complaint = { typed -> if ((typed.toDoubleOrNull() ?: 0.0) > 0.0) null else "a number above nought" },
                onDone = { typed -> then(typed.toDouble()) },
            ),
        )
    }

    /** Pairs typed on one line — `#frozen -1, minecraft:oak 0.5` — each read by [read]. */
    private fun typedPairs(concept: Concept, standing: Map<String, Double>?, read: (String) -> Double?, then: (Map<String, Double>) -> Unit) {
        fun pairsIn(typed: String): Map<String, Double>? = typed.split(',').map(String::trim).filter(String::isNotEmpty).associate { pair ->
            val key = pair.substringBefore(' ').trim()
            val said = read(pair.substringAfter(' ', "").trim()) ?: return null
            key to said
        }.takeIf { it.isNotEmpty() }
        val isBars = concept.values == ValueType.BARS
        val spelled = standing.orEmpty().entries.joinToString(", ") { (key, value) -> "$key ${if (isBars) Bars.spell(value) else value.toString()}" }
        questions.ask(
            Prompt(
                title = "${concept.id.path}: ${if (isBars) "tags, and the bar each must clear" else "what to lean toward, and how strongly"}",
                hint = if (isBars) "#woody >0.3, #wet <0.2" else "#frozen -1, minecraft:oak 0.5",
                typed = spelled,
                complaint = { typed -> if (pairsIn(typed) == null) "pairs of a key and ${if (isBars) "a bar" else "a weight"}, by commas" else null },
                onDone = { typed -> pairsIn(typed)?.let(then) },
            ),
        )
    }

    private fun weightOf(said: String): Double? = said.toDoubleOrNull()

    private fun barOf(said: String): Double? = Bars.read(said).result().orElse(null)

    /** A concept made of parts, a part at a time: each pick asks that part, and the list comes back until it is taken. */
    private fun theParts(concept: Concept, given: Map<String, String>, then: (Value) -> Unit) {
        val take = Picker.Option(TAKE, "take it", given.entries.joinToString(" ") { (part, value) -> "$part $value" }.ifEmpty { "every part at its default" })
        val parts = concept.parts.entries.mapIndexed { at, (name, part) ->
            Picker.Option(name, name, given[name]?.let { "given $it" } ?: "its default, ${part.default}", marked = name in given, startsGroup = at == 0)
        }
        questions.ask(Picker("${concept.id.path}: which part does it give?", listOf(take) + parts) { picked ->
            val part = concept.parts[picked.value]
            if (part == null) {
                then(Value.Parts(given))
                return@Picker
            }
            fun withThePart(value: String?) = theParts(concept, if (value == null) given - picked.value else given + (picked.value to value), then)
            if (part.options.isNotEmpty()) {
                val options = listOf(Picker.Option(NONE, "not given", "its default, ${part.default}")) +
                    part.options.mapIndexed { at, option -> Picker.Option(option, option, marked = option == given[picked.value], startsGroup = at == 0) }
                questions.ask(Picker("its ${picked.value}?", options) { option -> withThePart(option.value.takeIf { it != NONE }) })
            } else {
                val axis = Parameter(picked.value, emptyList(), holds = Holds.RANGE)
                questions.ask(Band("its ${picked.value}", axis, emptyList(), given[picked.value] ?: part.default) { withThePart(it) })
            }
        })
    }

    private companion object {
        const val ANYONE = "\u0000anyone"
        const val THE_AGE = "\u0000age"
        const val NONE = "\u0000none"
        const val TAKE = "\u0000take"
    }
}
