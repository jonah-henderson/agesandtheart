package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Setting

/**
 * Every pair of words that could not both be what they said where they landed together (`notes/meanings-plan.md`
 * §2, Charging incoherence), read off what each could have landed before anything was drawn, so the same book is
 * charged the same at every seed. One flaw per pair of words, naming the place they first clashed and what over.
 *
 * Two landings clash where they are at one place and said of members in common, sited alike and not joined by
 * `and`, and: settle one parameter to values that cannot both hold; are made of different things; choose different
 * members of a catalogue; one strikes what the other chooses, or wants a tag the other bars; want opposed tags; or
 * one keeps only its own members of a population another adds to. A word never clashes with itself.
 */
internal fun incoherence(world: WorldFacts, possible: List<Landing>): List<Flaw> {
    val found = linkedMapOf<Pair<String, String>, Flaw>()
    for ((aspect, here) in possible.groupBy { it.aspect }.toSortedMap(compareBy(Aspect::ordinal))) {
        for (first in here.indices) {
            for (second in first + 1..<here.size) {
                val one = here[first]
                val other = here[second]
                val words = listOf(one.word, other.word).sorted().let { it[0] to it[1] }
                if (one.word == other.word || words in found || !meet(one, other)) continue
                val over = clashBetween(world, aspect, one, other) ?: clashBetween(world, aspect, other, one) ?: continue
                found[words] = Flaw(Register.INCOHERENT, listOf(one.word, other.word), aspect, over)
            }
        }
    }
    return found.values.toList()
}

/** Whether two landings are in one place: the same members in part, the same body, the same biome, unjoined. */
private fun meet(one: Landing, other: Landing): Boolean {
    if (one.isJoinedTo(other) || one.sitedIn != other.sitedIn) return false
    val oneMembers = one.members
    val otherMembers = other.members
    val membersApart = oneMembers != null && otherMembers != null && oneMembers.none { it in otherMembers }
    val bodiesApart = one.body != null && other.body != null && one.body != other.body
    return !membersApart && !bodiesApart
}

/** What [one] and [other] clash over at [aspect], asked one way round, or null where they do not. */
private fun clashBetween(world: WorldFacts, aspect: Aspect, one: Landing, other: Landing): List<String>? {
    for ((parameter, mine) in one.settings) {
        val theirs = other.settings[parameter] ?: continue
        if (!canBothHold(mine, theirs)) return listOf(parameter)
    }
    val oneSubstance = one.madeOf
    val otherSubstance = other.madeOf
    val madeOfTwoThings = oneSubstance != null && otherSubstance != null && oneSubstance != otherSubstance
    if (madeOfTwoThings) return listOfNotNull(oneSubstance, otherSubstance)
    val oneChoice = one.choice
    val otherChoice = other.choice
    val chooseApart = aspect.holds == Holds.CATALOGUE && oneChoice != null && otherChoice != null && oneChoice != otherChoice
    if (chooseApart) return listOfNotNull(oneChoice?.key, otherChoice?.key)
    if (otherChoice != null && one.strikes(otherChoice, world.tagsOf(otherChoice))) return listOf(otherChoice.key)
    for (wanted in one.wanted) {
        if (wanted in other.unwanted) return listOf(wanted)
        other.wanted.firstOrNull { world.areOpposed(wanted, it) }?.let { return listOf(wanted, it) }
    }
    val onlyAgainstAnother = aspect.holds == Holds.WEIGHTED_SET && one.polarity == Polarity.ONLY &&
        other.polarity == Polarity.ASSERTED && other.narrows
    if (onlyAgainstAnother) return listOf(Polarity.ONLY.name.lowercase())
    return null
}

/**
 * Whether two values one parameter was settled to can both hold: the same value, or two settings of a range that
 * settle together — a nudge never fails, two bands only where they do not meet.
 */
private fun canBothHold(mine: String, theirs: String): Boolean {
    if (mine == theirs) return true
    val asked = listOfNotNull(Setting.read(mine), Setting.read(theirs))
    val bothAreSettingsOfARange = asked.size == 2
    return bothAreSettingsOfARange && Setting.settle(asked) != null
}

private val Landing.madeOf: String? get() = effects.filterIsInstance<Effect.MadeOf>().firstOrNull()?.substance
