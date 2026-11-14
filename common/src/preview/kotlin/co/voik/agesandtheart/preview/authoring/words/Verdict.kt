package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.reading.StructureWord
import co.voik.agesandtheart.age.reading.WordsInTheWorld
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words

/**
 * What is wrong with a word being written, and what is worth thinking about. **What would stop it shipping is loading's
 * to say** — [Words.of] and [WordsInTheWorld], the one copy of those rules, which the game and the checks ask too — and
 * a verdict reports it for this word, adding only what is advice rather than a bar: warnings, the settled rulings of
 * `notes/decisions.md` and the design's as nudges, and the words it is charged beside. Pure: a candidate and a corpus in,
 * a list out.
 */
object Verdict {

    enum class Standing { REFUSED, WARNED, NUDGED, NOTED }

    /** One thing to say about a word. [heldBy] names what holds the rule, where something does. */
    data class Finding(val standing: Standing, val says: String, val because: String? = null, val heldBy: String? = null)

    /** Everything to say about [candidate] against [corpus]; [quarrels] adds the slow sweep of what it is charged beside. */
    fun on(candidate: Candidate, corpus: WordCorpus, quarrels: Boolean = true): List<Finding> {
        // A word as it is saved is judged among the words already loaded; one being written, among them with it in.
        val isAsSaved = candidate.word in corpus.written
        val words = if (isAsSaved) corpus.words else corpus.with(listOf(candidate.word))
        val word = words.word(candidate.name) ?: return listOf(refused("it did not load", heldBy = WORDS_OF))
        val world = if (isAsSaved) corpus.world else WordsInTheWorld(words, corpus.vocabulary, corpus.membership, corpus.tagsOnlyAServerGrants)
        val judging = Judging(word, words, world)
        return buildList {
            addAll(words.problemsOf(word).map { refused(it.says, heldBy = WORDS_OF) })
            addAll(world.problemsOf(word).map { refused(it.says, heldBy = WORDS_IN_THE_WORLD) })
            addAll(judging.warnings())
            addAll(judging.nudges())
            if (quarrels) addAll(judging.quarrels(corpus))
        }
    }

    /** What would stop the word shipping — what has to be cleared before saving. */
    fun refusals(findings: List<Finding>) = findings.filter { it.standing == Standing.REFUSED }

    /** Named words worst first: most refused, then most warned, then most nudged, then by name. */
    val WORST_FIRST: Comparator<Pair<String, List<Finding>>> =
        compareByDescending<Pair<String, List<Finding>>> { (_, found) -> found.count { it.standing == Standing.REFUSED } }
            .thenByDescending { (_, found) -> found.count { it.standing == Standing.WARNED } }
            .thenByDescending { (_, found) -> found.count { it.standing == Standing.NUDGED } }
            .thenBy { (name, _) -> name }

    private class Judging(val word: Word, val words: Words, val world: WordsInTheWorld) {

        val senses = word.senses

        /** What will never do anything: an offer nothing takes, or one with no chance anywhere. */
        fun warnings(): List<Finding> = senses.flatMap { sense ->
            sense.offers.filterNot(Offer::isAReading).flatMap { offer ->
                val said = "its ${offer.concept.path}${valueSaid(offer.value)}${audienceSaid(offer.audience)}"
                val neverApplies = offer.chance == 0.0 && offer.chanceFromWholeAge == 0.0
                listOfNotNull(
                    Finding(Standing.WARNED, "nothing takes $said", "it will always dangle").takeIf { world.sensesTaking(offer).isEmpty() },
                    Finding(Standing.WARNED, "$said can never apply", "its chance is nothing, said of a thing or of the Age").takeIf { neverApplies },
                )
            }
        }

        fun nudges(): List<Finding> = senses.flatMap { sense ->
            val written = listOf(sense.speaksFor to sense.means) + readingsOf(sense).map { (aspect, effects) -> aspect to effects }
            val landing = written.flatMap { (part, effects) -> effects.map { EffectTemplate.fencedTo(it, part) } }
            val needlessFences = written.flatMap { (part, effects) -> effects.mapNotNull { needlessFence(it, part) } }
            landing.flatMap(::quietEnd) + needlessFences + sense.offers.flatMap(::wholeAxisOffer) +
                readingsOf(sense).mapNotNull { (aspect, effects) -> readingAConceptReaches(aspect, effects.map { EffectTemplate.fencedTo(it, aspect) }) }
        }

        /** Each reading's part, and its effects as written. */
        private fun readingsOf(sense: Sense): List<Pair<Aspect, List<Effect>>> = sense.offers.filter(Offer::isAReading).mapNotNull { offer ->
            val aspect = (offer.audience as? Audience.Part)?.aspect ?: return@mapNotNull null
            aspect to offer.reading
        }

        /** **Never ship a word that says what saying nothing says** (§5.2). */
        private fun quietEnd(effect: Effect): List<Finding> {
            val sets = effect as? Effect.Sets ?: return emptyList()
            val key = sets.parameter
            val parameters = Aspect.entries.filter(key::landsOn).flatMap { aspect -> aspect.understood.filter { it.name == key.name } }
            val parameter = parameters.firstOrNull() ?: return emptyList()
            if (parameter.holds != Holds.RANGE) {
                val isTheDefault = parameters.all { it.default == sets.value }
                return listOfNotNull(nudged("'${key.spelled}' is already '${sets.value}' by default", "so this says what saying nothing says").takeIf { isTheDefault })
            }
            return listOfNotNull(wholeAxis("'${key.spelled}'", sets.value))
        }

        private fun wholeAxisOffer(offer: Offer): List<Finding> {
            val text = (offer.value as? Value.Text)?.text ?: return emptyList()
            return listOfNotNull(wholeAxis("its ${offer.concept.path}", text))
        }

        private fun wholeAxis(said: String, value: String): Finding? {
            val settled = Setting.read(value) as? Setting.Fixed ?: return null
            val excludesNothing = settled.span.width >= WHOLE_AXIS
            return nudged("$said is bounded to the whole axis", "a band that excludes nothing asks for nothing").takeIf { excludesNothing }
        }

        /** A fence naming the part an effect said of [part] lands on anyway, or the only part with the setting. */
        private fun needlessFence(effect: Effect, part: Aspect?): Finding? {
            val key = (effect as? Effect.Sets)?.parameter ?: (effect as? Effect.Bends)?.parameter ?: return null
            val fence = key.fencedTo ?: return null
            val namesItsOwnPart = fence == part
            val onlyItHasIt = part == null && Aspect.entries.filter { it.ownsParameterNamed(key.name) } == listOf(fence)
            val because = if (namesItsOwnPart) "it is said of the ${fence.page} already" else "only the ${fence.page} has '${key.name}'"
            return nudged("'${key.spelled}' does not need its part named", because).takeIf { namesItsOwnPart || onlyItHasIt }
        }

        /**
         * **A reading is the last resort** (`notes/the-word-rework.md`): one that only turns what its part's page already turns
         * through one shared concept is that concept, offered for the part. A reading through several is a bundle landing as
         * one, which is what readings are for.
         */
        private fun readingAConceptReaches(aspect: Aspect, effects: List<Effect>): Finding? {
            val page = words.partPage(aspect)?.defaultSense ?: return null
            val turnedThroughAConcept = page.acceptances.flatMap { acceptance ->
                takingTargets(acceptance.outcome, aspect).map { it to acceptance.concept.path }
            }.toMap()
            val turned = effects.map { (it as? Effect.Sets)?.parameter ?: (it as? Effect.Bends)?.parameter }
            if (turned.isEmpty() || turned.any { it == null || it !in turnedThroughAConcept }) return null
            val concept = turned.mapNotNull { turnedThroughAConcept[it] }.distinct().singleOrNull() ?: return null
            return nudged("its reading of the ${aspect.page} turns only what the ${aspect.page} takes as $concept", "offer $concept for the ${aspect.page} instead")
        }

        private fun takingTargets(outcome: Outcome, aspect: Aspect): List<ParameterKey> {
            fun targetOf(template: EffectTemplate): ParameterKey? = when (template) {
                is EffectTemplate.Fixed -> null
                is EffectTemplate.When -> targetOf(template.template)
                is EffectTemplate.Taking -> {
                    val turnsASetting = template.verb == EffectTemplate.Verb.SETS || template.verb == EffectTemplate.Verb.BENDS
                    val written = ParameterKey.read(template.target)
                    if (!turnsASetting) null else if (written.fencedTo == null) ParameterKey(written.name, aspect) else written
                }
            }
            return (outcome as? Outcome.Becomes)?.effects.orEmpty().mapNotNull(::targetOf)
        }

        /** The words of ours this one is charged beside, both said of the Age. */
        fun quarrels(corpus: WordCorpus): List<Finding> {
            val writing = corpus.writing(words)
            val others = corpus.written.filter { other ->
                val isAPlainWord = other.senses.none { it.behaviour != null } && Aspect.byPage(other.name) == null
                isAPlainWord && other.name != word.name
            }
            val against = others.filter { other ->
                val flaws = writing.resolve(listOf(word.name, other.name, StructureWord.Age.id.path), QUARREL_SEED).instability.flaws
                flaws.any { word.name in it.words && other.name in it.words }
            }.map(Word::name)
            if (against.isEmpty()) return emptyList()
            return listOf(Finding(Standing.NOTED, "said of the Age beside ${against.size} word(s) it is charged", against.joinToString(" ")))
        }

        private fun valueSaid(value: Value): String = when (value) {
            is Value.Text -> " '${value.text}'"
            is Value.Amount -> " ${value.multiple}"
            is Value.Parts -> " ${value.parts.values.joinToString(" ")}"
            else -> ""
        }

        private fun audienceSaid(audience: Audience): String = when (audience) {
            Audience.Anyone -> ""
            Audience.TheAge -> " for the Age"
            is Audience.Part -> " for the ${audience.aspect.page}"
        }

        private fun nudged(says: String, because: String) = Finding(Standing.NUDGED, says, because)
    }

    private fun refused(says: String, heldBy: String) = Finding(Standing.REFUSED, says, heldBy = heldBy)

    private const val WORDS_OF = "Words.of"
    private const val WORDS_IN_THE_WORLD = "WordsInTheWorld"

    /** Incoherence is charged from what could land, so any seed asks it. */
    private const val QUARREL_SEED = 0L

    /** A band this wide over a natural axis of -1..1 leaves nothing out, so it demands nothing. */
    private const val WHOLE_AXIS = 2.0
}
