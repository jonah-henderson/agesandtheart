package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.RANGED_PARAMETERS
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.namesARegistryEntry
import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.language.DerivedWords
import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.word.MaterialTable
import co.voik.agesandtheart.age.word.Vocabulary

/**
 * What is wrong with a word, and what is merely worth thinking about — **the one copy of the rules a word is held
 * to**, asked while it is written and by `VocabularyCheck` of every word the pack ships.
 *
 * Every [Standing.REFUSED] is a word the pack must not ship, naming the check that holds it; every [Standing.NUDGED]
 * is a settled ruling of `notes/decisions.md`, advice rather than a bar. Pure: a word and a corpus in, a
 * list out.
 */
object Verdict {

    enum class Standing { REFUSED, WARNED, NUDGED, NOTED }

    /** One thing to say about a word. [heldBy] names the check that owns the rule, where one does. */
    data class Finding(val standing: Standing, val says: String, val because: String? = null, val heldBy: String? = null)

    /**
     * Everything to say about [candidate] against [vocabulary]. [onlyAServerGrants] are the tags offline cannot
     * know, which a word may ask for unchallenged; [quarrels] adds the slow sweep of what it contradicts.
     */
    fun on(
        candidate: Candidate,
        vocabulary: Vocabulary,
        onlyAServerGrants: Set<String> = vocabulary.tagsOnlyAServerGrants,
        quarrels: Boolean = true,
    ): List<Finding> {
        val word = candidate.asWord()
        val judge = Judging(word, vocabulary, onlyAServerGrants, isDerived = candidate.isDerived)
        return buildList {
            if (!candidate.isDerived) addAll(nameFaults(candidate))
            addAll(judge.meaningFaults())
            addAll(judge.tagFaults())
            addAll(judge.restrictionFaults())
            addAll(judge.parameterFaults())
            addAll(judge.memberFaults())
            addAll(judge.materialFaults())
            if (!candidate.isDerived) {
                addAll(judge.synonymFaults())
                addAll(judge.hiddenPageFaults())
                addAll(judge.nudges())
            }
            addAll(judge.duplicateChoices())
            if (quarrels && !candidate.isDerived) addAll(judge.quarrels())
        }
    }

    fun on(candidate: Candidate, corpus: Corpus): List<Finding> =
        on(candidate, corpus.vocabulary, corpus.vocabulary.tagsOnlyAServerGrants + corpus.snapshot?.serverOnly?.keys.orEmpty())

    /** What would stop the word shipping — what has to be cleared before saving. */
    fun refusals(findings: List<Finding>) = findings.filter { it.standing == Standing.REFUSED }

    /** Named words worst first: most refused, then most warned, then most nudged, then by name. */
    val WORST_FIRST: Comparator<Pair<String, List<Finding>>> =
        compareByDescending<Pair<String, List<Finding>>> { (_, found) -> found.count { it.standing == Standing.REFUSED } }
            .thenByDescending { (_, found) -> found.count { it.standing == Standing.WARNED } }
            .thenByDescending { (_, found) -> found.count { it.standing == Standing.NUDGED } }
            .thenBy { (name, _) -> name }

    private fun nameFaults(candidate: Candidate): List<Finding> = buildList {
        val name = candidate.name
        if (name.isBlank()) {
            add(Finding(Standing.REFUSED, "a word needs a name"))
            return@buildList
        }
        if (!WordFile.couldBeAName(name)) {
            add(Finding(Standing.REFUSED, "'$name' cannot be a resource path", "lower case, digits and _ - . / only"))
        }
        Aspect.byPage(name)?.let { aspect ->
            add(Finding(Standing.REFUSED, "'$name' is already ${aspect.key}'s own page", heldBy = "Vocabulary.load"))
        }
    }

    private class Judging(
        val word: Word,
        val vocabulary: Vocabulary,
        val onlyAServerGrants: Set<String>,
        val isDerived: Boolean,
    ) {
        val effects = word.effects
        val isAnAspectPage = Aspect.byPage(word.name) != null

        fun meaningFaults(): List<Finding> = buildList {
            if (word.meanings.isEmpty() && !isAnAspectPage) add(refused("it means nothing at all"))
            for ((at, meaning) in word.meanings.withIndex()) {
                if (meaning.effects.isEmpty()) add(refused("meaning ${at + 1} says nothing"))
                val alwaysAndSometimes = meaning.mustAlwaysApply &&
                    (meaning.chanceToApply != Meaning.CERTAIN || meaning.unaimedChanceToApply != null)
                if (alwaysAndSometimes) add(refused("meaning ${at + 1} must always apply and has a chance as well", heldBy = "WordFormat"))
                val chances = listOfNotNull(meaning.chanceToApply, meaning.unaimedChanceToApply)
                if (chances.any { it !in Meaning.NEVER..Meaning.CERTAIN }) add(refused("meaning ${at + 1}'s chance is not a share", heldBy = "WordFormat"))
                for (offeredTo in meaning.offeredTo.filter { vocabulary.word(it) == null && vocabulary.structurePage(it) == null }) {
                    add(refused("meaning ${at + 1} is offered to '$offeredTo', which is not a word"))
                }
            }
        }

        fun tagFaults(): List<Finding> {
            val known = vocabulary.carriedTags + onlyAServerGrants
            val unknown = word.tagsSpokenOf - known
            return unknown.sorted().map { refused("nothing carries #$it", "a typo, or a tag nothing was ever given") }
        }

        /**
         * A narrowing nothing in its part of the world meets. Two do another job and are exempt: one beside a
         * parameter the word turns there steers rather than chooses, and one wanting a tag of an opposed pair says
         * what the word means in tag space, which is how incoherence finds it.
         */
        fun restrictionFaults(): List<Finding> = buildList {
            fun isOpposable(tag: String) = vocabulary.opposedTags.any { it.first == tag || it.second == tag }
            val admitted = effects.filterIsInstance<Effect.Admits>()
            val turned = effects.mapNotNull { (it as? Effect.Sets)?.parameter ?: (it as? Effect.Bends)?.parameter }
            for (restricts in effects.filterIsInstance<Effect.Restricts>()) {
                val wanted = restricts.bars.filterValues(Bars::isAtLeast).keys
                val onlyAServerCouldAnswer = wanted.isNotEmpty() && wanted.all(onlyAServerGrants::contains)
                val aspect = restricts.aspect
                val steersHere = turned.any { it.landsOn(aspect) && vocabulary.turnsAParameter(aspect, it.name) }
                if (onlyAServerCouldAnswer || steersHere || wanted.any(::isOpposable)) continue
                val admittedHere = admitted.filter { it.aspect == aspect }.flatMap { it.members }.mapNotNull(aspect::presetFor)
                val pool = vocabulary.availableToBroadWordsIn(aspect) + admittedHere
                if (pool.none { Bars.cleared(vocabulary.tagsOf(it), restricts.bars) }) {
                    val said = restricts.bars.entries.joinToString { (tag, bar) -> "#$tag ${Bars.spell(bar)}" }
                    add(refused("nothing in the ${aspect.page} meets $said"))
                }
            }
        }

        fun parameterFaults(): List<Finding> = buildList {
            fun parametersCalled(name: String, aspect: Aspect) =
                (vocabulary.candidatesFor(aspect).flatMap { it.ownParameters } + aspect.parameters).filter { it.name == name }
            val said = effects.filterIsInstance<Effect.Sets>().map { it.parameter to it.value } +
                effects.filterIsInstance<Effect.Bends>().map { it.parameter to null }
            for ((parameter, value) in said) {
                val landsOn = Aspect.entries.filter(parameter::landsOn)
                if (landsOn.isEmpty()) {
                    add(refused("no part of the world has '${parameter.spelled}'"))
                    continue
                }
                val turnedOn = landsOn.filter { vocabulary.turnsAParameter(it, parameter.name) }
                if (turnedOn.isEmpty()) {
                    add(refused("'${parameter.spelled}' is declared and acted on by nothing"))
                    continue
                }
                if (value == null) {
                    if (parameter.name !in RANGED_PARAMETERS) add(refused("'${parameter.spelled}' has no range to bend"))
                    continue
                }
                val alternatives = value.split(ALTERNATIVES).map(String::trim).filter(String::isNotEmpty)
                fun isTaken(one: String) = turnedOn.any { aspect -> parametersCalled(parameter.name, aspect).any { it.acceptsFromAWord(one) } }
                val unacceptable = alternatives.filterNot(::isTaken)
                if (unacceptable.isNotEmpty()) {
                    val because = if (parameter.name == ROCK && unacceptable.none(Materials::isQuery)) "a rock must hold somebody up" else null
                    add(refused("'${parameter.spelled}' does not take '${unacceptable.joinToString("|")}'", because))
                }
            }
        }

        fun memberFaults(): List<Finding> {
            val named = effects.flatMap { effect ->
                when (effect) {
                    is Effect.Chooses -> listOf(effect.aspect to effect.member)
                    is Effect.Admits -> effect.members.map { effect.aspect to it }
                    else -> emptyList()
                }
            }
            return named.mapNotNull { (aspect, key) ->
                val oneOfOurDesigns = aspect.ownsPresetNamed(key)
                val anEntryOfItsRegistry = aspect.presetsAreEntriesOf != null && namesARegistryEntry(key)
                if (oneOfOurDesigns || anEntryOfItsRegistry) null
                else refused("the ${aspect.page} has no '$key'", "a design of ours is a bare key, a registry entry a namespaced id")
            }
        }

        /** A material asked for by tag that no block a world can be made of answers. */
        fun materialFaults(): List<Finding> = effects.filterIsInstance<Effect.Sets>()
            .filter { MaterialTable.asksByTag(it.parameter.spelled, it.value) }
            .flatMap { it.value.split(ALTERNATIVES).map(String::trim).filter(Materials::isQuery) }
            .filter { query -> vocabulary.materials.carriersOf(query.removePrefix(Materials.QUERY_MARK)).isEmpty() }
            .map { query -> refused("no block a world can be made of answers $query", heldBy = "MaterialQueryCheck") }

        /** An authored word that only names registry entries says nothing the derived word for them does not. */
        fun synonymFaults(): List<Finding> {
            fun namesAReferent(effect: Effect) = when (effect) {
                is Effect.Chooses -> namesARegistryEntry(effect.member)
                is Effect.Sets -> namesARegistryEntry(effect.value)
                else -> false
            }
            val onlyNamesReferents = effects.isNotEmpty() && effects.all(::namesAReferent)
            return if (onlyNamesReferents) listOf(refused("it only names what derived words already name")) else emptyList()
        }

        /** An authored word replaces a derived one of its id, and loses the page minted for a preset of that name. */
        fun hiddenPageFaults(): List<Finding> {
            val minted = DerivedWords.designs().firstOrNull { it.id == word.id } ?: return emptyList()
            val lost = minted.effects.filterIsInstance<Effect.Chooses>() - effects.filterIsInstance<Effect.Chooses>().toSet()
            return if (lost.isEmpty()) emptyList()
            else listOf(refused("it hides the page minted for ${lost.joinToString { "${it.aspect.page} ${it.member}" }}"))
        }

        /** A preset has one page that means it; an aspect's page announcing its own phenomenon is not counted. */
        fun duplicateChoices(): List<Finding> {
            if (isAnAspectPage) return emptyList()
            val mine = effects.filterIsInstance<Effect.Chooses>().toSet()
            if (mine.isEmpty()) return emptyList()
            return vocabulary.words.filter { other -> other.name != word.name && Aspect.byPage(other.name) == null }
                .flatMap { other -> other.effects.filterIsInstance<Effect.Chooses>().filter(mine::contains).map { other.name to it } }
                .map { (other, choice) -> refused("'$other' already chooses ${choice.aspect.page} ${choice.member}") }
        }

        fun nudges(): List<Finding> = buildList {
            for ((at, meaning) in word.meanings.withIndex()) {
                val neverAimed = !meaning.mustAlwaysApply && meaning.chanceToApply == Meaning.NEVER
                val neverBare = !meaning.appliesUnaimed
                if (neverAimed && neverBare) add(Finding(Standing.WARNED, "meaning ${at + 1} can never apply", "aimed or laid bare"))
            }
            // A default among alternatives is the odds of leaving the thing alone, not a word saying nothing.
            val keysSetByOneMeaning = word.meanings.flatMap { meaning -> meaning.effects.filterIsInstance<Effect.Sets>().map { it.parameter }.distinct() }
                .groupingBy { it }.eachCount().filterValues { it == 1 }.keys
            for (sets in effects.filterIsInstance<Effect.Sets>().filter { it.parameter in keysSetByOneMeaning }) addAll(quietEnd(sets.parameter, sets.value))
            for (key in effects.filterIsInstance<Effect.Sets>().map { it.parameter }.distinct()) addAll(needlessFence(key))
        }

        /** **Never ship a word that says what saying nothing says** (§5.2). */
        private fun quietEnd(key: ParameterKey, value: String): List<Finding> {
            val parameters = Aspect.entries.filter(key::landsOn).flatMap { aspect -> aspect.understood.filter { it.name == key.name } }
            val parameter = parameters.firstOrNull() ?: return emptyList()
            if (parameter.holds != Holds.RANGE) {
                return if (parameters.all { it.default == value }) {
                    listOf(Finding(Standing.NUDGED, "'${key.spelled}' is already '$value' by default", "so this says what saying nothing says"))
                } else {
                    emptyList()
                }
            }
            val settled = Setting.read(value) as? Setting.Fixed ?: return emptyList()
            return if (settled.span.width >= WHOLE_AXIS) {
                listOf(Finding(Standing.NUDGED, "'${key.spelled}' is bounded to the whole axis", "a band that excludes nothing asks for nothing"))
            } else {
                emptyList()
            }
        }

        /** A fence on a parameter only one part of the world has fences nothing. */
        private fun needlessFence(key: ParameterKey): List<Finding> {
            val fence = key.fencedTo ?: return emptyList()
            val owners = Aspect.entries.filter { it.ownsParameterNamed(key.name) }
            return if (owners == listOf(fence)) {
                listOf(Finding(Standing.NUDGED, "'${key.spelled}' does not need its part named", "only the ${fence.page} has '${key.name}'"))
            } else {
                emptyList()
            }
        }

        /** The authored words this one is charged against, laid bare together. */
        fun quarrels(): List<Finding> {
            val against = vocabulary.authoredWords.filter { it.name != word.name && Aspect.byPage(it.name) == null }
                .filter { other ->
                    val book = listOf(word.name, other.name, AGE_PAGE)
                    vocabulary.resolve(book, QUARREL_SEED)?.instability?.flaws.orEmpty().any { flaw ->
                        word.name in flaw.words && other.name in flaw.words
                    }
                }
                .map { it.name }
            if (against.isEmpty()) return emptyList()
            return listOf(Finding(Standing.NOTED, "laid bare beside ${against.size} word(s) it is charged", against.joinToString(" ")))
        }

        fun refused(says: String, because: String? = null, heldBy: String = "VocabularyCheck") =
            Finding(Standing.REFUSED, says, because, heldBy)
    }

    private const val ALTERNATIVES = '|'
    private const val ROCK = "stone"
    private const val AGE_PAGE = "age"

    /** Incoherence is charged from what could land, so any seed asks it. */
    private const val QUARREL_SEED = 0L

    /** A band this wide over a natural axis of -1..1 leaves nothing out, so it demands nothing. */
    private const val WHOLE_AXIS = 2.0
}
