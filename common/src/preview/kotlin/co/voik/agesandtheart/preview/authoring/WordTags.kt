package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Effect

/** The tags a word speaks of, read off its effects — what the tag screens ask of the words. */

private const val TAG_MARK = "#"

private fun tagsNamedIn(keys: Collection<String>): Set<String> =
    keys.filter { it.startsWith(TAG_MARK) }.map { it.drop(1) }.toSet()

/** The tags it narrows toward. */
val Word.wantedTags: Set<String>
    get() = effects.filterIsInstance<Effect.Restricts>().flatMap { it.bars.filterValues(Bars::isAtLeast).keys }.toSet()

/** The tags it strikes or bars against. */
val Word.unwantedTags: Set<String>
    get() = effects.filterIsInstance<Effect.Excludes>().flatMap { tagsNamedIn(it.members) }.toSet() +
        effects.filterIsInstance<Effect.Restricts>().flatMap { it.bars.filterValues(Bars::isAtMost).keys }

/** The tags it only leans by. */
val Word.leanedTags: Set<String>
    get() = effects.flatMap { effect ->
        when (effect) {
            is Effect.Leans -> tagsNamedIn(effect.weights.keys)
            is Effect.LeansEverywhere -> tagsNamedIn(effect.weights.keys)
            else -> emptySet()
        }
    }.toSet()

/** Every tag it speaks of, any way. */
val Word.tagsSpokenOf: Set<String> get() = wantedTags + unwantedTags + leanedTags

/** The member of [aspect] it chooses outright, or null. */
fun Word.choiceIn(aspect: Aspect): Taggable? =
    effects.filterIsInstance<Effect.Chooses>().firstOrNull { it.aspect == aspect }?.let { aspect.presetFor(it.member) }
