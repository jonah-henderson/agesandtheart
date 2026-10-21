package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.LinkAnchor

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Bars

/** What a landing claims, read off its effects — the questions the resolver asks of every claim it weighs. */
internal val Landing.aspect: Aspect get() = place.aspect

/** The one member it chooses outright, or null where it chooses none. */
internal val Landing.choice: Taggable?
    get() = effects.filterIsInstance<Effect.Chooses>().firstOrNull()?.let { aspect.presetFor(it.member) }

/** Members it puts into the pool by name. */
internal val Landing.admitted: Set<String>
    get() = effects.filterIsInstance<Effect.Admits>().flatMap { it.members }.toSet()

private val Landing.struck: Set<String>
    get() = effects.filterIsInstance<Effect.Excludes>().flatMap { it.members }.toSet()

/** Where this says a link into the Age arrives — `link in jungle` — or null where it says nothing of it. */
internal val Landing.linksIn: LinkAnchor?
    get() = effects.filterIsInstance<Effect.LinksIn>().firstOrNull()?.anchor

/** The blocks this bans from the whole Age — `no snow`. */
internal val Landing.banned: Set<String>
    get() = effects.filterIsInstance<Effect.Bans>().map { it.block }.toSet()

/** Its bars, by tag, signed as `Bars` reads one. */
internal val Landing.bars: Map<String, Double>
    get() = effects.filterIsInstance<Effect.Restricts>().fold(emptyMap()) { held, next -> held + next.bars }

/** Every parameter it settles, by name. */
internal val Landing.settings: Map<String, String>
    get() = effects.filterIsInstance<Effect.Sets>().associate { it.parameter.name to it.value }

/** Every ranged parameter it leans, by name. */
internal val Landing.bendings: Map<String, String>
    get() = effects.filterIsInstance<Effect.Bends>().associate { it.parameter.name to it.value }

/** Whether it says anything about which member fills its part of the world, as opposed to how it is steered. */
internal val Landing.narrowsMembers: Boolean get() = choice != null || struck.isNotEmpty() || bars.isNotEmpty()

/** Whether it narrows anything at all, as opposed to only leaning and bending. */
internal val Landing.narrows: Boolean get() = effects.any(Effect::narrows)

/** The body of a population it describes, or null where it is said of the population as a whole. */
internal val Landing.body: Int? get() = (place.members as? Members.Body)?.index

/** The members it is said of, or null where it is said of the whole part of the world. */
internal val Landing.members: Set<String>? get() = (place.members as? Members.Named)?.keys

/** How firmly it claims: choosing or settling decides, barring bars, the rest leans (world model §3). */
internal val Landing.firmness: Int
    get() = when {
        effects.any { it is Effect.Chooses || it is Effect.Sets || it is Effect.Mints } -> DECIDES
        effects.any { it is Effect.Restricts || it is Effect.Excludes } -> BARS
        else -> LEANS
    }

internal val Landing.highestBar: Double get() = bars.values.filter(Bars::isAtLeast).maxOrNull() ?: 0.0

/** Whether it takes [member] out, by key or by a `#tag` the member carries. */
internal fun Landing.strikes(member: Taggable, tags: Map<String, Double>): Boolean = struck.any { named ->
    if (named.startsWith(TAG_MARK)) tags.containsKey(named.drop(1)) else named == member.key
}

/** Whether [tags] breaks none of its at-most bars. */
internal fun Landing.withinItsLimits(tags: Map<String, Double>): Boolean =
    bars.filterValues(Bars::isAtMost).all { (tag, bar) -> (tags[tag] ?: 0.0) <= -bar }

/** How far it leans the draw toward [member], by key or by a `#tag` it carries, summed. */
internal fun Landing.leanOn(member: Taggable, tags: Map<String, Double>): Double =
    effects.filterIsInstance<Effect.Leans>().flatMap { it.weights.entries }.sumOf { (named, weight) ->
        when {
            named == member.key -> weight
            named.startsWith(TAG_MARK) -> weight * (tags[named.drop(1)] ?: 0.0)
            else -> 0.0
        }
    }

/** How strongly the member carries its best wanted tag, [PULL_PER_CARRIED] times over. */
internal fun Landing.pullOn(tags: Map<String, Double>): Double =
    bars.filterValues(Bars::isAtLeast).keys.maxOfOrNull { tag -> tags[tag] ?: 0.0 }?.let { it * PULL_PER_CARRIED } ?: 0.0

/** How strongly it claims [member]: absolute where it chose it, else how well the member answers its bars. */
internal fun Landing.claimOn(member: Taggable, tags: Map<String, Double>): Double =
    if (choice?.key == member.key) CHOSEN_OUTRIGHT else pullOn(tags)

/**
 * Whether [member] survives it: chosen where it chose, not struck, within its limits, and clearing [share] of any
 * at-least bar where it set one.
 */
internal fun Landing.accepts(member: Taggable, tags: Map<String, Double>, share: Double = Bars.STRICT): Boolean {
    choice?.let { return it.key == member.key }
    if (strikes(member, tags)) return false
    return Bars.cleared(tags, bars, share)
}

/** The tags it narrows toward. */
internal val Landing.wanted: Set<String> get() = bars.filterValues(Bars::isAtLeast).keys

/** The tags it strikes or bars against. */
internal val Landing.unwanted: Set<String>
    get() = struck.filter { it.startsWith(TAG_MARK) }.map { it.drop(1) }.toSet() + bars.filterValues(Bars::isAtMost).keys

/**
 * What resolution may keep or overrule of it, one name each: every parameter it sets, the members it narrows to, the
 * substance it makes a minting of, and the minting. What only leans, bends or counts is kept whatever else holds.
 */
internal val Landing.decisions: Set<String>
    get() = effects.map { effect ->
        when (effect) {
            is Effect.Sets -> effect.parameter.name
            is Effect.Chooses, is Effect.Admits, is Effect.Excludes, is Effect.Restricts -> Decision.MEMBERS
            is Effect.Mints -> Decision.MINTS
            is Effect.MadeOf -> Decision.SUBSTANCE
            is Effect.Bends, is Effect.Leans, is Effect.LeansEverywhere, is Effect.Abundance, is Effect.Template,
            is Effect.Bans, is Effect.LinksIn -> Decision.KEPT
        }
    }.toSet()

/** The names [Landing.decisions] gives what is not a parameter. */
internal object Decision {
    const val MEMBERS = "#members"
    const val MINTS = "#mints"
    const val SUBSTANCE = "#substance"
    const val KEPT = "#kept"

    /** Every decision of a landing at once, for one overruled outright. */
    const val WHOLE = "#whole"
}

/** Whether `and` joined these two: a division asked for, which nothing charges. */
internal fun Landing.isJoinedTo(other: Landing): Boolean = joined != null && joined == other.joined

private const val TAG_MARK = "#"

private const val DECIDES = 3
private const val BARS = 2
private const val LEANS = 1

/** What choosing a member outright is worth, against [pullOn]. */
private const val CHOSEN_OUTRIGHT = 3.0

/** What carrying a wanted tag fully pulls by. */
private const val PULL_PER_CARRIED = 2.0
