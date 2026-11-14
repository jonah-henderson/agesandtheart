package co.voik.agesandtheart.preview.authoring.words.ui

import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.RANGED_PARAMETERS
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.words.Concept
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.ValueType
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.preview.authoring.ui.Band
import co.voik.agesandtheart.preview.authoring.ui.Picker
import co.voik.agesandtheart.preview.authoring.ui.Prompt
import co.voik.agesandtheart.preview.authoring.ui.Questions
import co.voik.agesandtheart.preview.authoring.words.WordCorpus
import net.minecraft.core.registries.BuiltInRegistries

/**
 * The questions an effect asks: a fixed one by what it does, then where, then what — a sense's own meaning, a reading, or
 * what an acceptance always makes — and one taking an offer's value, by its verb, its target and how the value is read.
 */
class EffectAsking(private val corpus: WordCorpus, private val questions: Questions) {

    private val vocabulary get() = corpus.vocabulary

    /** A new fixed effect, or several where one setting is given to several parts at once. */
    fun anEffect(title: String, then: (List<Effect>) -> Unit) {
        val kinds = listOf(
            Picker.Option(SETS, "set a property", "a parameter to a value: a temperature, a colour, a size"),
            Picker.Option(BENDS, "bend a range", "move a range without bounding it"),
            Picker.Option(CHOOSES, "choose a member", "this landform, this sea, this creature, and no other", startsGroup = true),
            Picker.Option(ADMITS, "add a member", "put a member in the pool that curation left out"),
            Picker.Option(KEEPS, "keep what carries a tag", "only what answers it survives"),
            Picker.Option(STRIKES, "strike a member or a tag", "take them out of the pool"),
            Picker.Option(LEANS, "lean toward or away", "tilt the draw, never removing anything"),
            Picker.Option(MINTS, "mint a pattern", "a feature made of what its clause lays", startsGroup = true),
            Picker.Option(TEMPLATE, "begin from a world", "the world the book starts from"),
            Picker.Option(ABUNDANCE, "ask for more or fewer", "how many of what it is laid before"),
        )
        fun one(effect: Effect) = then(listOf(effect))
        questions.ask(Picker("$title: what does it do?", kinds) { picked ->
            when (picked.value) {
                SETS -> aParameter(rangedOnly = false) { keys -> aSetting(keys, rangedOnly = false, standing = null, then) }
                BENDS -> aParameter(rangedOnly = true) { keys -> aSetting(keys, rangedOnly = true, standing = null, then) }
                CHOOSES -> aPart("Choose in which part?", ::holdsMembers) { part -> aMember(part, standing = null) { one(Effect.Chooses(part, it)) } }
                ADMITS -> aPart("Add to which pool?", ::isAPool) { part -> membersToAdmit(part) { one(Effect.Admits(part, it)) } }
                KEEPS -> aPart("Keep by a tag, in which part?", ::holdsMembers) { part -> aTagToKeep(part) { one(Effect.Restricts(part, mapOf(it to DEFAULT_BAR))) } }
                STRIKES -> aPart("Strike in which part?", ::holdsMembers) { part -> somethingToStrike(part) { one(Effect.Excludes(part, it)) } }
                LEANS -> aLean(::one)
                MINTS -> aPattern(standing = null, ::one)
                TEMPLATE -> aTemplateWorld(standing = null, ::one)
                ABUNDANCE -> aRung(standing = null, ::one)
            }
        })
    }

    /**
     * [effect] asked again, from the entry [key] where it holds several: what adding it asked, answered with the effect
     * to stand in its place. An entry that is only a member says so, since it is changed by removing it and adding another.
     */
    fun again(effect: Effect, key: String?, then: (Effect) -> Unit, says: (String) -> Unit) {
        when (effect) {
            is Effect.Sets -> aSetting(listOf(effect.parameter), rangedOnly = false, standing = effect.value) { it.firstOrNull()?.let(then) }
            is Effect.Bends -> aSetting(listOf(effect.parameter), rangedOnly = true, standing = effect.value) { it.firstOrNull()?.let(then) }
            is Effect.Chooses -> aMember(effect.aspect, effect.member) { then(effect.copy(member = it)) }
            is Effect.Admits, is Effect.Excludes -> says("d takes it out; a adds another")
            is Effect.Restricts -> key?.let { tag -> aBar(tag, effect.bars[tag] ?: DEFAULT_BAR) { then(effect.copy(bars = effect.bars + (tag to it))) } }
            is Effect.Leans -> key?.let { named -> aWeight(named, effect.weights[named] ?: A_WHOLE_LEAN) { then(effect.copy(weights = effect.weights + (named to it))) } }
            is Effect.LeansEverywhere -> key?.let { named ->
                aWeight(named, effect.weights[named] ?: A_WHOLE_LEAN) { then(effect.copy(weights = effect.weights + (named to it))) }
            }
            is Effect.Mints -> aMinting(effect, then)
            is Effect.Template -> aTemplateWorld(effect.name, then)
            is Effect.Abundance -> aRung(effect.rung, then)
            is Effect.MadeOf, is Effect.Bans, is Effect.LinksIn -> says("this is made by an offer's value, not written")
        }
    }

    /**
     * What an acceptance of [concept] makes of the value it takes, in the part [part] speaks for: an effect taking the
     * value, through a table where the setting is of another kind, or a fixed one.
     */
    fun aTemplate(concept: Concept, part: Aspect?, then: (EffectTemplate) -> Unit) {
        val options = listOf(
            Picker.Option(TAKING, "make something of the value", "${concept.id.path} is a ${concept.values.serializedName}"),
            Picker.Option(FIXED, "always the same effect", "whatever the value, where this takes it"),
        )
        questions.ask(Picker("What does it make of ${concept.id.path}?", options) { picked ->
            when (picked.value) {
                TAKING -> aTakingVerb(concept, part) { taking -> perhapsOnlyWhen(concept, taking, then) }
                FIXED -> anEffect("Always") { effects -> effects.forEach { then(EffectTemplate.Fixed(it)) } }
            }
        })
    }

    /** A template asked again, as adding it asked. */
    fun againTemplate(template: EffectTemplate, concept: Concept, part: Aspect?, then: (EffectTemplate) -> Unit, says: (String) -> Unit) {
        when (template) {
            is EffectTemplate.Fixed -> again(template.effect, key = null, { then(EffectTemplate.Fixed(it)) }, says)
            is EffectTemplate.Taking -> aTakingVerb(concept, part, then)
            is EffectTemplate.When -> aChoice(concept, template.choice) { choice -> then(template.copy(choice = choice)) }
        }
    }

    // -- an effect taking the value -------------------------------------------------------------------

    private fun aTakingVerb(concept: Concept, part: Aspect?, then: (EffectTemplate.Taking) -> Unit) {
        val options = EffectTemplate.Verb.entries.map { verb -> Picker.Option(verb.key, verb.key, VERB_HELP.getValue(verb)) }
        questions.ask(Picker("It ... the value of ${concept.id.path}", options) { picked ->
            val verb = EffectTemplate.Verb.entries.first { it.key == picked.value }
            aTarget(verb, part) { target ->
                if (verb.targets == EffectTemplate.Targets.PARAMETER) aReadingTable(concept, verb, target, then) else then(EffectTemplate.Taking(verb, target))
            }
        })
    }

    /** What a verb lands on: a parameter, its own part's where it names none; a part; every part; or nothing. */
    private fun aTarget(verb: EffectTemplate.Verb, part: Aspect?, then: (String) -> Unit) {
        when (verb.targets) {
            EffectTemplate.Targets.PARAMETER -> aParameter(rangedOnly = verb == EffectTemplate.Verb.BENDS, ownPart = part, alone = true) { keys ->
                keys.firstOrNull()?.let { then(it.spelled) }
            }
            EffectTemplate.Targets.PART -> aPart("${verb.key} in which part?", { true }) { then(it.page) }
            EffectTemplate.Targets.PART_OR_EVERY_PART -> {
                val every = Picker.Option(EffectTemplate.EVERY_PART, "everything", "every part it likes something in")
                val parts = Aspect.entries.mapIndexed { at, aspect -> Picker.Option(aspect.page, aspect.page, startsGroup = at == 0) }
                questions.ask(Picker("${verb.key} where?", listOf(every) + parts) { picked -> then(picked.value) })
            }
            EffectTemplate.Targets.NONE -> then(EffectTemplate.THE_VALUE)
        }
    }

    /** The value read as it is, or through a table naming what each value sets — the only way an amount sets anything. */
    private fun aReadingTable(concept: Concept, verb: EffectTemplate.Verb, target: String, then: (EffectTemplate.Taking) -> Unit) {
        val needsATable = concept.values == ValueType.AMOUNT
        val options = listOfNotNull(
            Picker.Option(AS_IT_IS, "the value as it is", "${concept.id.path}'s value is the setting").takeUnless { needsATable },
            Picker.Option(THROUGH_A_TABLE, "through a table", "each value, and what it sets: a compass point as a bearing"),
        )
        questions.ask(Picker("How is ${concept.id.path} read as $target?", options) { picked ->
            if (picked.value == AS_IT_IS) return@Picker then(EffectTemplate.Taking(verb, target))
            questions.ask(
                Prompt(
                    title = "${concept.id.path} as $target: each value, and what it sets",
                    hint = "north=0, east=90 — a value taken nowhere in the table sets nothing",
                    typed = "",
                    complaint = { typed -> if (tableIn(typed) == null) "pairs of a value and a setting, by commas" else null },
                    onDone = { typed -> tableIn(typed)?.let { then(EffectTemplate.Taking(verb, target, it)) } },
                ),
            )
        })
    }

    private fun tableIn(typed: String): Map<String, String>? = typed.split(',').map(String::trim).filter(String::isNotEmpty).associate { pair ->
        val value = pair.substringBefore('=').trim()
        val setting = pair.substringAfter('=', "").trim()
        if (value.isEmpty() || setting.isEmpty()) return null
        value to setting
    }.takeIf { it.isNotEmpty() }

    /** For a concept made of parts, the effect perhaps only where one part takes one choice: wear's holes where it is porous. */
    private fun perhapsOnlyWhen(concept: Concept, taking: EffectTemplate, then: (EffectTemplate) -> Unit) {
        if (concept.values != ValueType.PARTS) return then(taking)
        val options = listOf(Picker.Option(ALWAYS, "whatever its parts", "")) + choicesOf(concept).mapIndexed { at, (choice, part) ->
            Picker.Option(choice, "only when $choice", "its $part", startsGroup = at == 0)
        }
        questions.ask(Picker("Always, or only for one choice?", options) { picked ->
            then(if (picked.value == ALWAYS) taking else EffectTemplate.When(picked.value, taking))
        })
    }

    private fun aChoice(concept: Concept, standing: String, then: (String) -> Unit) {
        val options = choicesOf(concept).map { (choice, part) -> Picker.Option(choice, choice, "its $part", marked = choice == standing) }
        questions.ask(Picker("Only when which?", options) { picked -> then(picked.value) })
    }

    private fun choicesOf(concept: Concept): List<Pair<String, String>> =
        concept.parts.flatMap { (name, part) -> part.options.map { it to name } }

    // -- where an effect lands ------------------------------------------------------------------------

    private fun holdsMembers(part: Aspect) = vocabulary.candidatesFor(part).isNotEmpty()

    private fun isAPool(part: Aspect) = part.holds == Holds.WEIGHTED_SET

    private fun aPart(title: String, offers: (Aspect) -> Boolean, then: (Aspect) -> Unit) {
        val options = Aspect.entries.filter(offers).map { part ->
            Picker.Option(part.page, part.page, "${vocabulary.candidatesFor(part).size} members")
        }
        questions.ask(Picker(title, options) { picked -> Aspect.byPage(picked.value)?.let(then) })
    }

    /**
     * Every parameter by name with the parts that have it — then which of them. [ownPart], where given, is offered as
     * the bare name, which a word file reads as the accepting sense's own part's; [alone] takes one part at most.
     */
    private fun aParameter(rangedOnly: Boolean, ownPart: Aspect? = null, alone: Boolean = false, then: (List<ParameterKey>) -> Unit) {
        val byName = Aspect.entries.flatMap { part -> part.understood.map { it.name to part } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, parts) -> parts.distinct() }
            .filterKeys { name -> name != Parameter.CAST && (!rangedOnly || name in RANGED_PARAMETERS) }
        val options = byName.entries.sortedBy { it.key }.map { (name, parts) -> Picker.Option(name, name, parts.joinToString(" ") { it.page }) }
        questions.ask(Picker(if (rangedOnly) "Bend which range?" else "Set which property?", options) { picked ->
            val owners = byName[picked.value].orEmpty()
            when {
                ownPart != null && ownPart in owners -> then(listOf(ParameterKey(picked.value)))
                owners.size <= 1 -> then(listOf(ParameterKey(picked.value)))
                alone -> aPart("'${picked.value}' of which part?", owners::contains) { then(listOf(ParameterKey(picked.value, it))) }
                else -> whichOwners(picked.value, owners, then)
            }
        })
    }

    /** Which parts one name lands on: every one, unfenced, or those marked, each fenced to its part. */
    private fun whichOwners(name: String, owners: List<Aspect>, then: (List<ParameterKey>) -> Unit) {
        val options = listOf(Picker.Option(EVERYWHERE, "every part that has it", owners.joinToString(" ") { it.page })) +
            owners.map { Picker.Option(it.page, it.page, "only here", startsGroup = it == owners.first()) }
        questions.ask(
            Picker(
                title = "'$name' on which parts? enter marks, → takes them",
                options = options,
                marking = true,
                onOnly = { _, marked ->
                    then(if (EVERYWHERE in marked) listOf(ParameterKey(name)) else marked.mapNotNull(Aspect::byPage).map { ParameterKey(name, it) })
                },
            ) {},
        )
    }

    /** The value one or more keys of a parameter take: on its axis, from its options, or a block. */
    private fun aSetting(keys: List<ParameterKey>, rangedOnly: Boolean, standing: String?, then: (List<Effect>) -> Unit) {
        val first = keys.firstOrNull() ?: return
        val parameter = parameterFor(first) ?: return
        fun take(value: String) = then(keys.map { key -> if (rangedOnly) Effect.Bends(key, value) else Effect.Sets(key, value) })
        when {
            parameter.holds == Holds.RANGE -> questions.ask(
                Band(
                    title = "${first.spelled}${if (keys.size > 1) " and ${keys.size - 1} more" else ""}",
                    parameter = parameter,
                    presets = howOursSet(first.name),
                    said = standing.orEmpty(),
                    onDone = ::take,
                ),
            )
            parameter.material -> aMaterial(first.spelled, standing, ::take)
            else -> questions.ask(
                Picker("${first.spelled} as what?", parameter.options.map { option ->
                    Picker.Option(option, option, parameter.optionHelp[option].orEmpty(), marked = option == standing)
                }) { picked -> take(picked.value) },
            )
        }
    }

    private fun parameterFor(key: ParameterKey): Parameter? =
        Aspect.entries.filter(key::landsOn).firstNotNullOfOrNull { part -> part.understood.firstOrNull { it.name == key.name } }

    /** How our words already set [name], so "the same as `arid`" is a thing to pick. */
    private fun howOursSet(name: String): List<Picker.Option> = corpus.written
        .flatMap { word -> fixedEffectsOf(word).filterIsInstance<Effect.Sets>().filter { it.parameter.name == name }.map { word.name to it.value } }
        .distinctBy { it.second }
        .map { (word, value) -> Picker.Option(value, word, value) }

    /** A block a world may be made of, or a tag it is drawn from: `#frozen`. */
    private fun aMaterial(title: String, standing: String?, then: (String) -> Unit) {
        val tags = vocabulary.carriedTags.sorted().filter { vocabulary.materials.carriersOf(it).isNotEmpty() }.map { tag ->
            val spelled = "${Materials.QUERY_MARK}$tag"
            Picker.Option(spelled, spelled, "${vocabulary.materials.carriersOf(tag).size} blocks", marked = spelled == standing)
        }
        val blocks = BuiltInRegistries.BLOCK.keySet().map { it.toString() }.sorted().mapIndexed { at, block ->
            Picker.Option(block, block, marked = block == standing, startsGroup = at == 0)
        }
        questions.ask(Picker("$title as what? a block, or a tag it is drawn from", tags + blocks) { picked -> then(picked.value) })
    }

    // -- members, tags and leans ----------------------------------------------------------------------

    private fun aMember(part: Aspect, standing: String?, then: (String) -> Unit) {
        val options = vocabulary.candidatesFor(part).map { member ->
            Picker.Option(member.key, member.key, vocabulary.tagsOf(member).keys.sorted().joinToString(" ") { "#$it" }, marked = member.key == standing)
        }
        questions.ask(Picker("Which ${part.page}?", options) { picked -> then(picked.value) })
    }

    private fun membersToAdmit(part: Aspect, then: (Set<String>) -> Unit) {
        val curated = vocabulary.availableToBroadWordsIn(part).map { it.key }.toSet()
        val options = vocabulary.candidatesFor(part).filterNot { it.key in curated }.map { Picker.Option(it.key, it.key, "kept from broad words") }
        questions.ask(
            Picker(
                title = "Add which to the ${part.page}? enter marks, → takes them",
                options = options.ifEmpty { listOf(Picker.Option(NOTHING, "every member is in the pool already")) },
                marking = true,
                onOnly = { _, marked -> (marked - NOTHING).takeIf { it.isNotEmpty() }?.let(then) },
            ) {},
        )
    }

    private fun tagsIn(part: Aspect): List<String> = vocabulary.candidatesFor(part).flatMap { vocabulary.tagsOf(it).keys }.distinct().sorted()

    private fun carriersNote(part: Aspect, tag: String): String =
        "${vocabulary.candidatesFor(part).count { (vocabulary.tagsOf(it)[tag] ?: 0.0) > 0.0 }} carry it"

    private fun aTagToKeep(part: Aspect, then: (String) -> Unit) {
        val options = tagsIn(part).map { tag -> Picker.Option(tag, "#$tag", carriersNote(part, tag)) }
        questions.ask(Picker("Keep in the ${part.page} what carries which tag?", options) { picked -> then(picked.value) })
    }

    private fun somethingToStrike(part: Aspect, then: (Set<String>) -> Unit) {
        val tags = tagsIn(part).map { tag -> Picker.Option("#$tag", "#$tag", carriersNote(part, tag)) }
        val members = vocabulary.candidatesFor(part).mapIndexed { at, member -> Picker.Option(member.key, member.key, startsGroup = at == 0) }
        questions.ask(
            Picker(
                title = "Strike what from the ${part.page}? enter marks, → takes them",
                options = tags + members,
                marking = true,
                onOnly = { _, marked -> if (marked.isNotEmpty()) then(marked) },
            ) {},
        )
    }

    private fun aLean(then: (Effect) -> Unit) {
        val options = listOf(Picker.Option(EVERYWHERE, "everything", "every part it likes something in")) +
            Aspect.entries.filter(::holdsMembers).map { Picker.Option(it.page, it.page, "${vocabulary.candidatesFor(it).size} members") }
        questions.ask(Picker("Lean where?", options) { picked ->
            val part = Aspect.byPage(picked.value)
            val tags = (part?.let(::tagsIn) ?: vocabulary.carriedTags.sorted()).map { Picker.Option("#$it", "#$it") }
            val members = part?.let { vocabulary.candidatesFor(it).map { member -> Picker.Option(member.key, member.key) } }.orEmpty()
            questions.ask(Picker("Lean toward what? then - = on its row", tags + members) { named ->
                val weights = mapOf(named.value to A_WHOLE_LEAN)
                then(if (part == null) Effect.LeansEverywhere(weights) else Effect.Leans(part, weights))
            })
        })
    }

    private fun aWeight(named: String, standing: Double, then: (Double) -> Unit) = questions.ask(
        Prompt(
            title = "How strongly toward $named?",
            hint = "a weight: below nought leans away",
            typed = standing.toString(),
            complaint = { typed -> if (typed.toDoubleOrNull() == null) "a number" else null },
            onDone = { typed -> then(typed.toDouble()) },
        ),
    )

    private fun aBar(tag: String, standing: Double, then: (Double) -> Unit) = questions.ask(
        Prompt(
            title = "What must #$tag clear?",
            hint = "'>0.6' for at least, '<0.2' for at most",
            typed = Bars.spell(standing),
            complaint = { typed -> if (Bars.read(typed).result().isEmpty) "a bar: '>0.6' or '<0.2'" else null },
            onDone = { typed -> Bars.read(typed).result().ifPresent(then) },
        ),
    )

    // -- the rest -------------------------------------------------------------------------------------

    /** The patterns our words mint, and the placed features a pattern can be. */
    private fun aPattern(standing: Effect.Mints?, then: (Effect) -> Unit) {
        val minted = corpus.written.flatMap(::fixedEffectsOf).filterIsInstance<Effect.Mints>().map { it.pattern }.distinct().sorted()
        val features = vocabulary.candidatesFor(Aspect.FEATURES).map { it.key }.filterNot(minted::contains).sorted()
        val options = minted.map { Picker.Option(it, it, "minted by a word already") } +
            features.mapIndexed { at, feature -> Picker.Option(feature, feature, startsGroup = at == 0) }
        questions.ask(Picker("Mint which pattern?", options) { picked ->
            then(standing?.copy(pattern = picked.value) ?: Effect.Mints(picked.value, unstated = null, flows = false))
        })
    }

    private fun aMinting(mints: Effect.Mints, then: (Effect) -> Unit) {
        val options = listOf(
            Picker.Option(PATTERN, "the pattern", mints.pattern),
            Picker.Option(UNSTATED, "made of, where its clause lays nothing", mints.unstated ?: "nothing: it mints nothing then"),
            Picker.Option(FLOWS, if (mints.flows) "a block, not a fluid" else "a fluid, flowing", "what the substance does"),
        )
        questions.ask(Picker("The minting of ${mints.pattern}", options) { picked ->
            when (picked.value) {
                PATTERN -> aPattern(mints, then)
                UNSTATED -> aMaterial("made of", mints.unstated) { then(mints.copy(unstated = it)) }
                FLOWS -> then(mints.copy(flows = !mints.flows))
            }
        })
    }

    private fun aTemplateWorld(standing: String?, then: (Effect) -> Unit) {
        val options = AgeTemplate.entries.map { Picker.Option(it.key, it.key, marked = it.key == standing) }
        questions.ask(Picker("Begin from which world?", options) { picked -> then(Effect.Template(picked.value)) })
    }

    private fun aRung(standing: Double?, then: (Effect) -> Unit) {
        val options = RUNGS.map { rung -> Picker.Option(rung.toString(), "${Rung.spelled(rung)} times as many", marked = rung == standing) }
        questions.ask(Picker("How many?", options) { picked -> then(Effect.Abundance(picked.value.toDouble())) })
    }

    companion object {
        /** What a new restriction starts at: at least three tenths. */
        const val DEFAULT_BAR = 0.3

        const val A_WHOLE_LEAN = 1.0

        /** The amounts a writer asks for, as the quantifiers already do. */
        val RUNGS = listOf(0.25, 0.5, 2.0, 4.0)

        /** Every fixed effect [word] writes: its senses' own meanings, their readings, and what their acceptances always make. */
        fun fixedEffectsOf(word: Word): List<Effect> = word.senses.flatMap { sense ->
            val becomes = sense.accepts.flatMap { (it.outcome as? Outcome.Becomes)?.effects.orEmpty() }
            sense.means + sense.offers.flatMap { it.reading } + becomes.filterIsInstance<EffectTemplate.Fixed>().map { it.effect }
        }

        private val VERB_HELP = mapOf(
            EffectTemplate.Verb.SETS to "a setting, to the value",
            EffectTemplate.Verb.BENDS to "a range, moved by the value",
            EffectTemplate.Verb.CHOOSES to "the member the value names",
            EffectTemplate.Verb.ADMITS to "the member the value names, into a pool",
            EffectTemplate.Verb.EXCLUDES to "the member the value names, out of a pool",
            EffectTemplate.Verb.RESTRICTS to "a pool, to what clears the value's bars",
            EffectTemplate.Verb.LEANS to "a draw, by the value's weights",
            EffectTemplate.Verb.TEMPLATE to "the world the book starts from",
            EffectTemplate.Verb.MADE_OF to "what the thing is built of",
            EffectTemplate.Verb.BANS to "the block the value names, from the whole Age",
            EffectTemplate.Verb.ABUNDANCE to "how many there are, by the value's amount",
        )

        private const val SETS = "sets"
        private const val BENDS = "bends"
        private const val CHOOSES = "chooses"
        private const val ADMITS = "admits"
        private const val KEEPS = "keeps"
        private const val STRIKES = "strikes"
        private const val LEANS = "leans"
        private const val MINTS = "mints"
        private const val TEMPLATE = "template"
        private const val ABUNDANCE = "abundance"
        private const val PATTERN = "pattern"
        private const val UNSTATED = "unstated"
        private const val FLOWS = "flows"
        private const val TAKING = "\u0000taking"
        private const val FIXED = "\u0000fixed"
        private const val AS_IT_IS = "\u0000as it is"
        private const val THROUGH_A_TABLE = "\u0000table"
        private const val ALWAYS = "\u0000always"
        private const val EVERYWHERE = "\u0000all"
        private const val NOTHING = "\u0000nothing"
    }
}
