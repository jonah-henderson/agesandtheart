package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.MATERIAL_PARAMETERS
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Setting

/**
 * Two landings that could not both be what they said where they met (`the-world-model.md` §8), the firmer holding, and
 * what over. Found among what could land before anything is drawn, so the same book clashes the same at every seed; the
 * weaker comes to nothing there, and is drawn elsewhere where its page then placed nothing — but where the weaker only
 * yields ([isAYield]), it does not apply there and nothing is charged.
 */
data class Clash(val firmer: Landing, val weaker: Landing, val aspect: Aspect, val over: List<String>, val isAYield: Boolean) {

    /** The flaw it costs, naming the two words in the order they were laid. */
    val flaw: Flaw get() {
        val inOrder = listOf(firmer, weaker).sortedBy { it.page }
        return Flaw(Register.INCOHERENT, inOrder.map(Landing::word), aspect, over, inOrder.map(Landing::page))
    }
}

/**
 * Every pair of landings among [possible] that clash, the firmer by [firmestFirst] at [draw] holding. Two clash where
 * they are at one place and said of members in common, sited alike and not joined by `and`, not said of another clause's
 * minting, and: settle one parameter to values that cannot both hold; are made of different things; choose different
 * members of a catalogue; one strikes what the other chooses; one wants or leans toward a tag the other bars or leans
 * against, or toward one opposed to a tag the other wants or leans toward; or one keeps only its own members of a
 * population another adds to. A word never clashes with itself, and what is said of the Age whole clashes with nothing.
 */
fun clashesAmong(world: WorldFacts, possible: List<Landing>, draw: Long): List<Clash> {
    val ofItsParts = possible.filter { it.banned.isEmpty() && it.linksIn == null }
    val mintingClauses = mintingClausesIn(ofItsParts)
    val sayings = ofItsParts.groupBy(::whereSaid)
    val found = mutableListOf<Clash>()
    for ((aspect, here) in ofItsParts.groupBy { it.aspect }.toSortedMap(compareBy(Aspect::ordinal))) {
        val ranking = firmestFirst(aspect, draw)
        for (first in here.indices) {
            for (second in first + 1..<here.size) {
                val one = here[first]
                val other = here[second]
                if (one.word == other.word || !meet(one, other, mintingClauses)) continue
                val over = clashBetween(world, aspect, one, other) ?: clashBetween(world, aspect, other, one) ?: continue
                fun yieldsTo(weaker: Landing, firmer: Landing) =
                    isASuggestionMeetingACertainty(weaker, firmer) || givesWayToANamedBlock(weaker, firmer, sayings)
                val (firmer, weaker) = when {
                    yieldsTo(one, other) -> other to one
                    yieldsTo(other, one) -> one to other
                    ranking.compare(one, other) <= 0 -> one to other
                    else -> other to one
                }
                found += Clash(firmer, weaker, aspect, over, isAYield = yieldsTo(weaker, firmer))
            }
        }
    }
    return found
}

/** The flaws [clashes] cost: one per pair of words, at the first place they clashed, and nothing where a suggestion yielded. */
fun flawsOf(clashes: List<Clash>): List<Flaw> = clashes.filterNot(Clash::isAYield).distinctBy { it.flaw.words.toSet() }.map(Clash::flaw)

private const val CERTAIN = 1.0

/** A mood's green grass beside `orange grass`: a suggestion yields to a certainty (Jonah). */
private fun isASuggestionMeetingACertainty(weaker: Landing, firmer: Landing) = weaker.chance < CERTAIN && firmer.chance >= CERTAIN

/**
 * Whether [asker] asks only for a material by tag where [namer] names a block, and its page says something more of the
 * same thing there, among [sayings]: `ancient gold_block arches` are gold, and still worn. A word that says nothing else
 * there — `red blackstone arches` — has nothing left if its material gives way, and clashes; nor does a lean toward
 * the very tag it asked for count as more (`molten granite landmass`).
 */
private fun givesWayToANamedBlock(asker: Landing, namer: Landing, sayings: Map<WhereSaid, List<Landing>>): Boolean {
    fun namesWhatIsAsked(parameter: String, asked: String): Boolean {
        val named = namer.materials[parameter]
        return Materials.isQuery(asked) && named != null && !Materials.isQuery(named)
    }
    val asksWhatTheOtherNames = isOnlyAMaterial(asker) && asker.materials.any { (parameter, asked) -> namesWhatIsAsked(parameter, asked) }
    val askedFor = asker.materials.values.filter(Materials::isQuery).toSet()
    fun saysMore(effect: Effect): Boolean {
        val leansOnlyTowardWhatItAsked = effect is Effect.Leans && effect.weights.keys.all { it in askedFor }
        return !isAMaterial(effect) && !leansOnlyTowardWhatItAsked
    }
    val saidThere = sayings[whereSaid(asker)].orEmpty()
    return asksWhatTheOtherNames && saidThere.any { landing -> landing.effects.any(::saysMore) }
}

/** One page's saying of one thing at one place. */
private data class WhereSaid(val page: Int, val saidOf: Int, val place: Place)

private fun whereSaid(landing: Landing) = WhereSaid(landing.page, landing.saidOf, landing.place)

private const val MADE_OF = "made_of"

/** What it makes a thing of, by parameter, or [MADE_OF] for what a minting is made of. */
private val Landing.materials: Map<String, String>
    get() = settings.filterKeys { it in MATERIAL_PARAMETERS } + listOfNotNull(madeOf?.let { MADE_OF to it })

private fun isAMaterial(effect: Effect): Boolean =
    effect is Effect.MadeOf || (effect is Effect.Sets && effect.parameter.name in MATERIAL_PARAMETERS)

private fun isOnlyAMaterial(landing: Landing): Boolean = landing.effects.all(::isAMaterial)

/**
 * Whether two landings are in one place to disagree: the same members in part, the same body, the same biome,
 * unjoined, and in one clause where either was said of
 * what a clause minted — `deep ink lakes, shallow lava lakes` are two lakes.
 */
private fun meet(one: Landing, other: Landing, mintingClauses: Set<Pair<Aspect, Int>>): Boolean {
    if (mintingClauseOf(one, mintingClauses) != mintingClauseOf(other, mintingClauses)) return false
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
        if (!canBothHold(mine, theirs) && !world.agreeOnAMaterial(mine, theirs)) return listOf(parameter)
    }
    val oneSubstance = one.madeOf
    val otherSubstance = other.madeOf
    val madeOfTwoThings = oneSubstance != null && otherSubstance != null && !world.agreeOnAMaterial(oneSubstance, otherSubstance)
    if (madeOfTwoThings) return listOfNotNull(oneSubstance, otherSubstance)
    val oneChoice = one.choice
    val otherChoice = other.choice
    val bothChoose = aspect.holds == Holds.CATALOGUE && oneChoice != null && otherChoice != null
    if (bothChoose && oneChoice != otherChoice) return listOfNotNull(oneChoice.key, otherChoice.key)
    if (otherChoice != null && one.strikes(otherChoice, world.tagsOf(otherChoice))) return listOf(otherChoice.key)
    val otherFavours = other.wanted + other.leanedToward
    val otherDisfavours = other.unwanted + other.leanedAgainst
    for (favoured in one.wanted + one.leanedToward) {
        if (favoured in otherDisfavours) return listOf(favoured)
        otherFavours.firstOrNull { world.areOpposed(favoured, it) }?.let { return listOf(favoured, it) }
    }
    val onlyAgainstAnother = aspect.holds == Holds.WEIGHTED_SET && one.polarity == Polarity.ONLY &&
        other.polarity == Polarity.ASSERTED && other.narrows
    if (onlyAgainstAnother) return listOf(Polarity.ONLY.name.lowercase())
    return null
}

/**
 * Whether two materials asked for agree: the same, or one asked for by tag and the other a block carrying it — `black
 * blackstone arches`, `ancient blackstone arches` — which is then what both are.
 */
internal fun WorldFacts.agreeOnAMaterial(one: String, other: String): Boolean =
    one == other || answers(one, other) || answers(other, one)

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

/**
 * Every word asking for a block another word banned — `snow_block landmass no snow_block` — one flaw per pair of words.
 * Charged and not refused (Jonah, 2026-10-21): the ban stands, and a writer who bans what their Age is made of has an
 * Age they cannot stand on.
 */
internal fun bannedButAskedFor(possible: List<Landing>): List<Flaw> {
    val bans = possible.filter { it.banned.isNotEmpty() }
    fun asksFor(landing: Landing, block: String) = landing.effects.any { effect ->
        when (effect) {
            is Effect.Sets -> effect.value == block
            is Effect.Chooses -> effect.member == block
            is Effect.MadeOf -> effect.substance == block
            is Effect.Mints -> effect.unstated == block
            else -> false
        }
    }
    return bans.flatMap { ban ->
        ban.banned.flatMap { block ->
            possible.filter { it.banned.isEmpty() && asksFor(it, block) }.map { asking ->
                Flaw(Register.INCOHERENT, listOf(ban.word, asking.word), asking.aspect, listOf(block), listOf(ban.page, asking.page))
            }
        }
    }.distinctBy { it.words.sorted() }
}

private val Landing.madeOf: String? get() = effects.filterIsInstance<Effect.MadeOf>().firstOrNull()?.substance

/** The tags it leans by, each with its weight. */
private val Landing.tagLeans: Map<String, Double>
    get() = effects.filterIsInstance<Effect.Leans>().flatMap { it.weights.entries }
        .filter { (named, _) -> named.startsWith(TAG_MARK) }
        .associate { (named, weight) -> named.drop(1) to weight }

private val Landing.leanedToward: Set<String> get() = tagLeans.filterValues { it > 0.0 }.keys

private val Landing.leanedAgainst: Set<String> get() = tagLeans.filterValues { it < 0.0 }.keys

private const val TAG_MARK = "#"
