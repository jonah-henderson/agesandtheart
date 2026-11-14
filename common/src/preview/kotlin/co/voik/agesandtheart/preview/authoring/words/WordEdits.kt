package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Word

/**
 * The edits a word takes that are more than a `copy`, as pure functions: a word, sense, offer or acceptance in, a new one
 * out. Each keeps what its file can hold: one default sense, one acceptance of a concept, a chance that is a share, and
 * one effect of each kind at each place, as a list of effects is written.
 */

fun Word.withSense(sense: Sense = Sense()): Word = copy(senses = senses + sense)

fun Word.withoutSense(at: Int): Word = copy(senses = senses.removedAt(at))

fun Word.changingSense(at: Int, change: (Sense) -> Sense): Word = copy(senses = senses.replacedAt(at, change))

/** The sense at [at] marked as the one read where the neighbours fit two, and no other; null marks none. */
fun Word.withDefaultSense(at: Int?): Word = copy(senses = senses.mapIndexed { index, sense -> sense.copy(isDefault = index == at) })

fun Sense.withOffer(offer: Offer): Sense = copy(offers = offers + offer)

fun Sense.withoutOffer(at: Int): Sense = copy(offers = offers.removedAt(at))

fun Sense.changingOffer(at: Int, change: (Offer) -> Offer): Sense = copy(offers = offers.replacedAt(at, change))

/** [acceptance] taken, in place of any acceptance of its concept already here, since only the first would be read. */
fun Sense.withAcceptance(acceptance: Acceptance): Sense {
    val standing = accepts.indexOfFirst { it.concept == acceptance.concept }
    return if (standing < 0) copy(accepts = accepts + acceptance) else copy(accepts = accepts.replacedAt(standing) { acceptance })
}

fun Sense.withoutAcceptance(at: Int): Sense = copy(accepts = accepts.removedAt(at))

fun Sense.changingAcceptance(at: Int, change: (Acceptance) -> Acceptance): Sense = copy(accepts = accepts.replacedAt(at, change))

fun Sense.changingMeans(change: (List<Effect>) -> List<Effect>): Sense = copy(means = change(means))

fun Offer.changingReading(change: (List<Effect>) -> List<Effect>): Offer = copy(reading = change(reading))

/** The reading offered at [at] changed, and taken out where it is left meaning nothing: a reading is its effects. */
fun Sense.changingReading(at: Int, change: (List<Effect>) -> List<Effect>): Sense {
    val changed = offers.getOrNull(at)?.takeIf(Offer::isAReading)?.let { change(it.reading) } ?: return this
    return if (changed.isEmpty()) withoutOffer(at) else changingOffer(at) { it.copy(reading = changed) }
}

fun Offer.withChance(chance: Double): Offer = copy(chance = chance.asAShare())

/** Its chance from the whole Age, or null to follow its chance said of a thing. */
fun Offer.withChanceFromWholeAge(chance: Double?): Offer = copy(ownChanceFromWholeAge = chance?.asAShare())

fun Acceptance.withChanceFromWholeAge(chance: Double): Acceptance = copy(chanceFromWholeAge = chance.asAShare())

/** Its effects changed, an acceptance that did something else becoming one that turns what it takes into them. */
fun Acceptance.changingEffects(change: (List<EffectTemplate>) -> List<EffectTemplate>): Acceptance {
    val effects = (outcome as? Outcome.Becomes)?.effects.orEmpty()
    return copy(outcome = Outcome.Becomes(change(effects)))
}

/** [effect] added, merged into the effect of its kind already standing at its place. */
fun List<Effect>.withEffect(effect: Effect): List<Effect> {
    val standing = indexOfFirst { sharesAPlaceWith(it, effect) }
    return if (standing < 0) this + effect else replacedAt(standing) { merged(it, effect) }
}

/** The effect at [index] taken out whole, or only its entry [key] where it holds several. */
fun List<Effect>.withoutEntry(index: Int, key: String? = null): List<Effect> {
    val effect = getOrNull(index) ?: return this
    val left = key?.let { withoutKey(effect, it) }
    return if (left == null) removedAt(index) else replacedAt(index) { left }
}

/** The weight or bar of [key] in the effect at [index] set to [value]. */
fun List<Effect>.withEntryValue(index: Int, key: String, value: Double): List<Effect> = replacedAt(index) { effect ->
    when (effect) {
        is Effect.Leans -> effect.copy(weights = effect.weights + (key to value))
        is Effect.LeansEverywhere -> effect.copy(weights = effect.weights + (key to value))
        is Effect.Restricts -> effect.copy(bars = effect.bars + (key to value))
        else -> effect
    }
}

fun <T> List<T>.removedAt(at: Int): List<T> = filterIndexed { index, _ -> index != at }

fun <T> List<T>.replacedAt(at: Int, change: (T) -> T): List<T> = mapIndexed { index, item -> if (index == at) change(item) else item }

private fun Double.asAShare(): Double = coerceIn(Offer.NEVER, Offer.CERTAIN)

/** Whether two effects are one kind at one place, so that a file holds them as one. */
private fun sharesAPlaceWith(one: Effect, other: Effect): Boolean = when {
    one is Effect.Sets && other is Effect.Sets -> one.parameter == other.parameter
    one is Effect.Bends && other is Effect.Bends -> one.parameter == other.parameter
    one is Effect.Chooses && other is Effect.Chooses -> one.aspect == other.aspect
    one is Effect.Admits && other is Effect.Admits -> one.aspect == other.aspect
    one is Effect.Excludes && other is Effect.Excludes -> one.aspect == other.aspect
    one is Effect.Restricts && other is Effect.Restricts -> one.aspect == other.aspect
    one is Effect.Leans && other is Effect.Leans -> one.aspect == other.aspect
    one is Effect.LeansEverywhere && other is Effect.LeansEverywhere -> true
    one is Effect.Mints && other is Effect.Mints -> true
    one is Effect.Template && other is Effect.Template -> true
    one is Effect.Abundance && other is Effect.Abundance -> true
    else -> false
}

private fun merged(standing: Effect, added: Effect): Effect = when {
    standing is Effect.Admits && added is Effect.Admits -> standing.copy(members = standing.members + added.members)
    standing is Effect.Excludes && added is Effect.Excludes -> standing.copy(members = standing.members + added.members)
    standing is Effect.Restricts && added is Effect.Restricts -> standing.copy(bars = standing.bars + added.bars)
    standing is Effect.Leans && added is Effect.Leans -> standing.copy(weights = standing.weights + added.weights)
    standing is Effect.LeansEverywhere && added is Effect.LeansEverywhere -> standing.copy(weights = standing.weights + added.weights)
    else -> added
}

/** [effect] without its entry [key], or null where nothing of it is left. */
private fun withoutKey(effect: Effect, key: String): Effect? = when (effect) {
    is Effect.Admits -> (effect.members - key).takeIf { it.isNotEmpty() }?.let { effect.copy(members = it) }
    is Effect.Excludes -> (effect.members - key).takeIf { it.isNotEmpty() }?.let { effect.copy(members = it) }
    is Effect.Restricts -> (effect.bars - key).takeIf { it.isNotEmpty() }?.let { effect.copy(bars = it) }
    is Effect.Leans -> (effect.weights - key).takeIf { it.isNotEmpty() }?.let { effect.copy(weights = it) }
    is Effect.LeansEverywhere -> (effect.weights - key).takeIf { it.isNotEmpty() }?.let { effect.copy(weights = it) }
    else -> null
}
