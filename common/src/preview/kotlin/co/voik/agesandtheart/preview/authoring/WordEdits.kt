package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.resolution.Effect

/**
 * The edits a word takes, as pure functions: a candidate in, a new one out. A meaning holds one effect of each kind
 * at each place, as its file does, so an effect added where one already stands merges into it.
 */

fun Candidate.withMeaning(meaning: Meaning): Candidate = copy(meanings = meanings + meaning)

fun Candidate.withoutMeaning(at: Int): Candidate = copy(meanings = meanings.filterIndexed { index, _ -> index != at })

fun Candidate.changingMeaning(at: Int, change: (Meaning) -> Meaning): Candidate =
    copy(meanings = meanings.mapIndexed { index, meaning -> if (index == at) change(meaning) else meaning })

/** [effect] added, merged into the effect of its kind already standing at its place. */
fun Meaning.withEffect(effect: Effect): Meaning {
    val standing = effects.indexOfFirst { sharesAPlaceWith(it, effect) }
    if (standing < 0) return copy(effects = effects + effect)
    val merged = merged(effects[standing], effect)
    return copy(effects = effects.mapIndexed { index, it -> if (index == standing) merged else it })
}

/** The effect at [index] taken out whole, or only its entry [key] where it holds several. */
fun Meaning.withoutEntry(index: Int, key: String?): Meaning {
    val effect = effects.getOrNull(index) ?: return this
    val left = if (key == null) null else withoutKey(effect, key)
    return copy(effects = effects.mapIndexedNotNull { at, it -> if (at == index) left else it })
}

/** The weight or bar of [key] in the effect at [index] set to [value]. */
fun Meaning.withEntryValue(index: Int, key: String, value: Double): Meaning {
    val effect = effects.getOrNull(index) ?: return this
    val changed = when (effect) {
        is Effect.Leans -> effect.copy(weights = effect.weights + (key to value))
        is Effect.LeansEverywhere -> effect.copy(weights = effect.weights + (key to value))
        is Effect.Restricts -> effect.copy(bars = effect.bars + (key to value))
        else -> return this
    }
    return copy(effects = effects.mapIndexed { at, it -> if (at == index) changed else it })
}

/** The effect at [index] replaced whole. */
fun Meaning.withEffectAt(index: Int, effect: Effect): Meaning =
    copy(effects = effects.mapIndexed { at, it -> if (at == index) effect else it })

/** Certain to land, aimed or bare — which takes its chances away, since one that must apply has none to give. */
fun Meaning.mustAlways(must: Boolean): Meaning =
    if (must) copy(mustAlwaysApply = true, chanceToApply = Meaning.CERTAIN, unaimedChanceToApply = null)
    else copy(mustAlwaysApply = false)

fun Meaning.withChance(chance: Double): Meaning =
    copy(chanceToApply = chance.coerceIn(Meaning.NEVER, Meaning.CERTAIN), mustAlwaysApply = false)

/** The chance laid bare, or null for the Art's own share of the aimed one. */
fun Meaning.withUnaimedChance(chance: Double?): Meaning =
    copy(unaimedChanceToApply = chance?.coerceIn(Meaning.NEVER, Meaning.CERTAIN), mustAlwaysApply = false)

fun Meaning.asserting(asserts: Boolean): Meaning = copy(asserts = asserts)

/** Offered to [words] only, or to whatever takes its page where there are none. */
fun Meaning.offering(words: Set<String>): Meaning = copy(offeredTo = words)

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
