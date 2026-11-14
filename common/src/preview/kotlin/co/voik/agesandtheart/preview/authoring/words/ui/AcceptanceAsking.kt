package co.voik.agesandtheart.preview.authoring.words.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Concept
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.ValueFilter
import co.voik.agesandtheart.preview.authoring.ui.Picker
import co.voik.agesandtheart.preview.authoring.ui.Prompt
import co.voik.agesandtheart.preview.authoring.ui.Questions
import co.voik.agesandtheart.preview.authoring.words.WordCorpus
import co.voik.agesandtheart.preview.authoring.words.withChanceFromWholeAge

/**
 * The questions an acceptance asks: which concept a sense takes, which of its values, what it makes of them, and how
 * willing it is to take them from the whole Age or from a dangling page.
 */
class AcceptanceAsking(
    private val corpus: WordCorpus,
    private val questions: Questions,
    private val asking: Asking,
    private val effects: EffectAsking,
) {
    /** A new acceptance by a sense speaking for [part]: a concept, then what it makes of it. */
    fun anAcceptance(part: Aspect?, then: (Acceptance) -> Unit) {
        asking.aConcept("Take what?") { concept ->
            anOutcome(concept, part, standing = null) { outcome -> then(Acceptance(concept.id, outcome = outcome)) }
        }
    }

    /** [acceptance] opened: each of its answers asked again, the new acceptance handed to [then]. */
    fun about(acceptance: Acceptance, part: Aspect?, then: (Acceptance) -> Unit, says: (String) -> Unit) {
        val concept = corpus.words.concepts[acceptance.concept] ?: return says("${acceptance.concept} is no concept")
        val options = listOf(
            Picker.Option(CONCEPT, "its concept", acceptance.concept.path),
            Picker.Option(ONLY, "which values", filterSaid(acceptance.only)),
            Picker.Option(OUTCOME, "what it makes of them", outcomeSaid(acceptance.outcome)),
            Picker.Option(FROM_THE_AGE, "how willing from the whole Age", Sections.percent(acceptance.chanceFromWholeAge)),
            Picker.Option(
                DANGLING,
                if (acceptance.canResolveDanglingOffers) "never takes a dangling page" else "may take a dangling page",
                if (acceptance.canResolveDanglingOffers) "a page nothing took may be drawn onto this" else "no page nothing took is drawn onto this",
            ),
            Picker.Option(
                TAGS_FOR_IT,
                if (acceptance.tagsOnlyWhenOfferedForIt) "takes a #tag from anyone" else "takes a #tag only when offered for it",
                if (acceptance.tagsOnlyWhenOfferedForIt) "a #tag only from an offer for its part" else "a #tag from any offer",
            ),
        )
        questions.ask(Picker("The acceptance of ${acceptance.concept.path}", options) { picked ->
            when (picked.value) {
                CONCEPT -> asking.aConcept("Take which concept instead?") { chosen -> then(acceptance.copy(concept = chosen.id, only = ValueFilter.Anything)) }
                ONLY -> aFilter(concept, acceptance.only) { then(acceptance.copy(only = it)) }
                OUTCOME -> anOutcome(concept, part, acceptance.outcome) { then(acceptance.copy(outcome = it)) }
                FROM_THE_AGE -> asking.aChance(
                    "How willing it is to take ${concept.id.path} from what `age` hands out",
                    acceptance.chanceFromWholeAge,
                    "a share from 0 to 1, multiplying the offer's own: 0 where the whole Age would surprise",
                    mayBeNothing = false,
                ) { chance -> then(acceptance.withChanceFromWholeAge(chance ?: Offer.CERTAIN)) }
                DANGLING -> then(acceptance.copy(canResolveDanglingOffers = !acceptance.canResolveDanglingOffers))
                TAGS_FOR_IT -> then(acceptance.copy(tagsOnlyWhenOfferedForIt = !acceptance.tagsOnlyWhenOfferedForIt))
            }
        })
    }

    private fun anOutcome(concept: Concept, part: Aspect?, standing: Outcome?, then: (Outcome) -> Unit) {
        val options = listOf(
            Picker.Option(BECOMES, "makes effects of it", "said of this sense's part", marked = standing is Outcome.Becomes),
            Picker.Option(FOLDS, "folds it into what it offers", "for whatever takes that: north in 'north rising sun'", marked = standing == Outcome.Folds),
            Picker.Option(RENAMES, "passes it on as another concept", "a pace beside turning colours is the colours' pace", marked = standing is Outcome.Renames),
            Picker.Option(SCALES, "passes it on, scaled", "the same concept, its value multiplied", marked = standing is Outcome.Scales),
            Picker.Option(DROPS, "takes it, and makes nothing", "so it neither lands nor dangles", marked = standing == Outcome.Drops),
        )
        questions.ask(Picker("What does it make of ${concept.id.path}?", options) { picked ->
            when (picked.value) {
                BECOMES -> {
                    val kept = (standing as? Outcome.Becomes)?.effects.orEmpty()
                    effects.aTemplate(concept, part) { template -> then(Outcome.Becomes(kept + template)) }
                }
                FOLDS -> then(Outcome.Folds)
                RENAMES -> asking.aConcept("Passed on as which concept?") { then(Outcome.Renames(it.id)) }
                SCALES -> questions.ask(
                    Prompt(
                        title = "Scaled by how much?",
                        hint = "2 doubles it, 0.5 halves it",
                        typed = (standing as? Outcome.Scales)?.factor?.toString().orEmpty(),
                        complaint = { typed -> if ((typed.toDoubleOrNull() ?: 0.0) > 0.0) null else "a number above nought" },
                        onDone = { typed -> then(Outcome.Scales(typed.toDouble())) },
                    ),
                )
                DROPS -> then(Outcome.Drops)
            }
        })
    }

    /** Which values it takes: any, these keys or `#tags`, or members clearing these bars. */
    private fun aFilter(concept: Concept, standing: ValueFilter, then: (ValueFilter) -> Unit) {
        val options = listOf(
            Picker.Option(ANYTHING, "any value", "", marked = standing == ValueFilter.Anything),
            Picker.Option(ONE_OF, "only these", "keys, or members of these #tags", marked = standing is ValueFilter.OneOf),
            Picker.Option(CLEARING, "only members clearing these bars", "#woody >0.3: what a group holds", marked = standing is ValueFilter.Clearing),
        )
        questions.ask(Picker("Which values of ${concept.id.path}?", options) { picked ->
            when (picked.value) {
                ANYTHING -> then(ValueFilter.Anything)
                ONE_OF -> questions.ask(
                    Prompt(
                        title = "Only which, by spaces?",
                        hint = "#agesandtheart:whole_blocks minecraft:stone",
                        typed = (standing as? ValueFilter.OneOf)?.keys?.joinToString(" ").orEmpty(),
                        complaint = { typed -> if (typed.isBlank()) "at least one" else null },
                        onDone = { typed -> then(ValueFilter.OneOf(typed.split(' ').filter(String::isNotBlank).toSet())) },
                    ),
                )
                CLEARING -> questions.ask(
                    Prompt(
                        title = "Only members whose tags clear which bars?",
                        hint = "woody >0.3, wet <0.2",
                        typed = (standing as? ValueFilter.Clearing)?.bars?.entries?.joinToString(", ") { (tag, bar) -> "$tag ${Bars.spell(bar)}" }.orEmpty(),
                        complaint = { typed -> if (barsIn(typed) == null) "pairs of a tag and a bar, by commas" else null },
                        onDone = { typed -> barsIn(typed)?.let { then(ValueFilter.Clearing(it)) } },
                    ),
                )
            }
        })
    }

    private fun barsIn(typed: String): Map<String, Double>? = typed.split(',').map(String::trim).filter(String::isNotEmpty).associate { pair ->
        val tag = pair.substringBefore(' ').removePrefix("#").trim()
        val bar = Bars.read(pair.substringAfter(' ', "").trim()).result().orElse(null) ?: return null
        tag to bar
    }.takeIf { it.isNotEmpty() }

    companion object {
        fun filterSaid(filter: ValueFilter): String = when (filter) {
            ValueFilter.Anything -> "any"
            is ValueFilter.OneOf -> filter.keys.joinToString(" ")
            is ValueFilter.Clearing -> filter.bars.entries.joinToString(" ") { (tag, bar) -> "#$tag ${Bars.spell(bar)}" }
        }

        fun outcomeSaid(outcome: Outcome): String = when (outcome) {
            is Outcome.Becomes -> "${outcome.effects.size} effect${if (outcome.effects.size == 1) "" else "s"}"
            Outcome.Folds -> "folds it into what it offers"
            is Outcome.Renames -> "passes it on as ${outcome.concept.path}"
            is Outcome.Scales -> "passes it on scaled by ${outcome.factor}"
            Outcome.Drops -> "drops it"
        }

        private const val CONCEPT = "concept"
        private const val ONLY = "only"
        private const val OUTCOME = "outcome"
        private const val FROM_THE_AGE = "from the Age"
        private const val DANGLING = "dangling"
        private const val TAGS_FOR_IT = "tags for it"
        private const val BECOMES = "becomes"
        private const val FOLDS = "folds"
        private const val RENAMES = "renames"
        private const val SCALES = "scales"
        private const val DROPS = "drops"
        private const val ANYTHING = "\u0000anything"
        private const val ONE_OF = "\u0000one of"
        private const val CLEARING = "\u0000clearing"
    }
}
