package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueFilter
import co.voik.agesandtheart.age.words.Word

/**
 * The tag layer's tags a word speaks of, wherever it spells one — what it offers, what it takes, what it means — and the
 * word with one renamed or forgotten. A tag is `#frozen` among values, weights and members and bare among bars; a
 * namespaced `#minecraft:ice` is a block tag, not the layer's.
 */
object WordTags {

    /** Every tag [word] speaks of. */
    fun of(word: Word): Set<String> = buildSet { edited(word) { tag -> add(tag); tag } }

    /** [word] speaking of [to] wherever it spoke of [from]. */
    fun renamed(word: Word, from: String, to: String): Word = edited(word) { if (it == from) to else it }

    /** [word] saying nothing of [tag]: whatever spoke only of it goes, and an offer or acceptance left with nothing to say goes too. */
    fun forgotten(word: Word, tag: String): Word = edited(word) { it.takeUnless { named -> named == tag } }

    /** [word] with each tag it spells put through [each], which answers the tag it becomes, or null for none. */
    private fun edited(word: Word, each: (String) -> String?): Word = Edit(each).word(word)

    private class Edit(private val each: (String) -> String?) {

        fun word(word: Word): Word = word.copy(senses = word.senses.map(::sense))

        private fun sense(sense: Sense) = sense.copy(
            means = sense.means.mapNotNull(::effect),
            offers = sense.offers.mapNotNull(::offer),
            accepts = sense.accepts.mapNotNull(::acceptance),
        )

        private fun offer(offer: Offer): Offer? {
            if (offer.isAReading) {
                val reading = offer.reading.mapNotNull(::effect)
                return if (reading.isEmpty()) null else offer.copy(reading = reading)
            }
            val value = value(offer.value) ?: return null
            return offer.copy(value = value)
        }

        private fun value(value: Value): Value? = when (value) {
            is Value.Text -> marked(value.text)?.let(Value::Text)
            is Value.Weights -> markedKeys(value.weights)?.let(Value::Weights)
            is Value.Bars -> bareKeys(value.bars)?.let(Value::Bars)
            else -> value
        }

        private fun acceptance(acceptance: Acceptance): Acceptance? {
            val only = when (val filter = acceptance.only) {
                ValueFilter.Anything -> filter
                is ValueFilter.OneOf -> filter.keys.mapNotNull(::marked).toSet().ifEmpty { null }?.let(ValueFilter::OneOf)
                is ValueFilter.Clearing -> bareKeys(filter.bars)?.let(ValueFilter::Clearing)
            } ?: return null
            val outcome = when (val outcome = acceptance.outcome) {
                is Outcome.Becomes -> {
                    val kept = outcome.effects.mapNotNull(::template)
                    if (kept.isEmpty() && outcome.effects.isNotEmpty()) return null
                    Outcome.Becomes(kept)
                }
                else -> outcome
            }
            return acceptance.copy(only = only, outcome = outcome)
        }

        private fun template(template: EffectTemplate): EffectTemplate? = when (template) {
            is EffectTemplate.Fixed -> effect(template.effect)?.let(EffectTemplate::Fixed)
            is EffectTemplate.Taking -> template
            is EffectTemplate.When -> template(template.template)?.let { template.copy(template = it) }
        }

        private fun effect(effect: Effect): Effect? = when (effect) {
            is Effect.Restricts -> bareKeys(effect.bars)?.let { effect.copy(bars = it) }
            is Effect.Leans -> markedKeys(effect.weights)?.let { effect.copy(weights = it) }
            is Effect.LeansEverywhere -> markedKeys(effect.weights)?.let { effect.copy(weights = it) }
            is Effect.Excludes -> effect.members.mapNotNull(::marked).toSet().ifEmpty { null }?.let { effect.copy(members = it) }
            is Effect.Admits -> effect.members.mapNotNull(::marked).toSet().ifEmpty { null }?.let { effect.copy(members = it) }
            else -> effect
        }

        /** A key spelled `#tag` where it is one of the layer's, put through [each]; anything else as it was. */
        private fun marked(key: String): String? {
            val isTheLayers = key.startsWith(TAG_MARK) && NAMESPACE_MARK !in key
            if (!isTheLayers) return key
            return each(key.removePrefix(TAG_MARK))?.let { "$TAG_MARK$it" }
        }

        private fun markedKeys(weights: Map<String, Double>): Map<String, Double>? =
            weights.mapNotNull { (key, weight) -> marked(key)?.let { it to weight } }.toMap().ifEmpty { null }

        private fun bareKeys(bars: Map<String, Double>): Map<String, Double>? =
            bars.mapNotNull { (tag, bar) -> each(tag)?.let { it to bar } }.toMap().ifEmpty { null }
    }

    private const val TAG_MARK = "#"
    private const val NAMESPACE_MARK = ':'
}
