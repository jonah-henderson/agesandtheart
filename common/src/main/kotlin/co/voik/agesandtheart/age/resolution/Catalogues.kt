package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Terrain
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/** One preset an aspect ended up holding, and how much of the world it covers. */
internal data class Filling(val preset: Taggable, val share: Double)

/**
 * A set of landings that can all be satisfied at once, and what is left that satisfies them. Disjoint by
 * construction: a new territory is only started by a landing sharing no candidate with any existing one.
 */
private data class Territory(val landings: List<Landing>, val candidates: List<Taggable>) {
    operator fun plus(joining: Territory) =
        Territory(landings + joining.landings, candidates.filter { it in joining.candidates })
}

/** How many ways one aspect may divide — a limit on legibility rather than machinery. */
internal const val MOST_TERRITORIES = 3

// A floor under every candidate, so a lean tilts the draw rather than deciding it.
private const val BASE_WEIGHT = 0.35

// No candidate's chance reaches zero, or a landing pushing hard against something could eliminate it.
private const val FAINTEST_CHANCE = 0.02

// How well liked a preset must be, against the best already seated, to join for harmony's sake.
private const val COMPANY_SHARE_OF_BEST = 0.75

// A preset that can do everything asked keeps its full claim; one that can do none of it keeps this much.
private const val FULLY_CAPABLE = 1.0
private const val INCAPABLE_FACTOR = 0.04

/**
 * What fills [aspect]: one preset, or several where what landed left it no way to be one thing. Each narrowing
 * landing says which presets it keeps; those are gathered into territories, landings satisfiable together; then one
 * preset is drawn per territory, tilted by every lean. A territory there is no room for is dropped — what the two
 * words disagreed over is charged as incoherence, not here.
 */
internal fun Resolving.fill(aspect: Aspect): List<Filling> {
    if (aspect.holds != Holds.CATALOGUE) return emptyList()
    val speaking = speakingTo(aspect)
    val pool = world.availableToBroadWordsIn(aspect) +
        speaking.flatMap { it.admitted }.distinct().mapNotNull(aspect::presetFor)
    val narrowing = ranked(aspect, speaking.filter { it.narrows && it.narrowsMembers })

    val territories = mutableListOf<Territory>()
    // A landing that also steers this aspect settles last and never fractures it: a reusable word only has to
    // land one of its senses, so `colossal archipelagic landmass` is large islands rather than two territories.
    val (steering, choosing) = narrowing.partition { steersInstead(aspect, it) }
    for (landing in choosing + steering) {
        val carriers = carriersOf(landing, aspect)
        if (carriers.isEmpty()) {
            lose(landing, Decision.MEMBERS)
            if (steersInstead(aspect, landing)) continue
            flaws += flaw(Register.UNBACKED, listOf(landing), aspect)
            continue
        }
        val home = territories.indexOfFirst { it.candidates.any { candidate -> candidate in carriers } }
        if (home < 0) {
            if (territories.isNotEmpty() && steersInstead(aspect, landing)) {
                lose(landing, Decision.MEMBERS)
                continue
            }
            territories += Territory(listOf(landing), carriers)
        } else {
            territories[home] += Territory(listOf(landing), carriers)
        }
    }

    // A preset that can only be the whole aspect takes it alone: vanilla's rock beside a landform of ours keeps the rock.
    fun takesTheWhole(territory: Territory) = territory.candidates.all { it.takesTheWholeAspect }
    val whole = territories.firstOrNull(::takesTheWhole)
    val ordered = if (whole == null) territories else listOf(whole) + (territories - whole)
    val room = if (aspect.spatial && whole == null) MOST_TERRITORIES else 1
    val kept = ordered.take(room)
    for (unseated in ordered.drop(room)) unseated.landings.forEach { lose(it, Decision.MEMBERS) }
    divide(aspect, kept.map { it.landings })

    val chosen = mutableListOf<Taggable>()
    for ((index, territory) in kept.withIndex()) chosen += pick(territory.candidates, speaking, aspect, seat = index)
    if (chosen.isEmpty()) {
        if (pool.isEmpty()) return emptyList()
        chosen += pick(unaskedDraw(aspect, pool, speaking), speaking, aspect, seat = 0)
    }
    chosen += company(aspect, kept, speaking, chosen, room)
    return sharedOut(aspect, chosen, speaking)
}

/** The members [landing] keeps in [aspect]: the one it chose, or those of the pool it accepts. */
internal fun Resolving.carriersOf(landing: Landing, aspect: Aspect): List<Taggable> {
    landing.choice?.let { return listOf(it) }
    val pool = world.availableToBroadWordsIn(aspect) + landing.admitted.mapNotNull(aspect::presetFor)
    return pool.distinct().filter { landing.accepts(it, world.tagsOf(it)) }
}

/** Whether [landing] has a steering sense here — something it sets that this aspect turns. */
private fun Resolving.steersInstead(aspect: Aspect, landing: Landing): Boolean =
    (landing.settings.keys + landing.bendings.keys).any { world.turnsAParameter(aspect, it) }

/**
 * What an aspect no landing narrowed may be drawn from: the whole [pool], or for one that keeps its readiest
 * unasked, the readiest member and whatever something leans towards.
 */
private fun Resolving.unaskedDraw(aspect: Aspect, pool: List<Taggable>, speaking: List<Landing>): List<Taggable> {
    if (!aspect.keepsItsReadiestUnasked) return pool
    val readiest = pool.maxBy(world::readinessOf)
    val leanedTowards = pool.filter { claimOn(it, speaking) > 0.0 }
    return (listOf(readiest) + leanedTowards).distinct()
}

/**
 * The presets an aspect takes on because what landed liked several of them — the harmonious division, and free.
 * Something must have chosen here at all; a choice forbids it; company must be nearly as well liked as what is
 * seated; and each aspect has its own appetite for it.
 */
private fun Resolving.company(
    aspect: Aspect,
    territories: List<Territory>,
    speaking: List<Landing>,
    seated: List<Taggable>,
    room: Int,
): List<Taggable> {
    if (!aspect.spatial || seated.size >= room) return emptyList()
    if (territories.isEmpty()) return emptyList()
    if (speaking.any { it.choice != null }) return emptyList()
    val eligible = territories.flatMap { it.candidates }
    val bar = COMPANY_SHARE_OF_BEST * seated.maxOf { strengthOf(it, speaking) }
    val welcome = eligible.filter { it !in seated && strengthOf(it, speaking) >= bar }
    if (welcome.isEmpty()) return emptyList()

    val random = XoroshiroRandomSource(draw xor (aspect.ordinal * Resolving.ASPECT_STRIDE) xor Resolving.COMPANY_SALT)
    val joining = mutableListOf<Taggable>()
    var appetite = aspect.appetiteForCompany
    while (seated.size + joining.size < room && random.nextDouble() < appetite) {
        val remaining = welcome.filter { it !in joining }
        if (remaining.isEmpty()) break
        joining += pick(remaining, speaking, aspect, seat = seated.size + joining.size)
        // Each further guest is less likely than the last, so three-way harmony stays a rarity.
        appetite *= appetite
    }
    return joining
}

/** How much ground each chosen preset covers: its claim as a fraction of the strongest claim there. */
private fun Resolving.sharedOut(aspect: Aspect, chosen: List<Taggable>, speaking: List<Landing>): List<Filling> {
    val claims = chosen.map { claimOn(it, speaking) }
    val strongest = claims.max()
    if (strongest <= FAINTEST_CHANCE) return chosen.map { Filling(it, Share.EVEN) }
    val shares = Share.findable(claims.map { Rung.legible(it / strongest) })
    return chosen.mapIndexed { index, preset -> Filling(preset, shares[index]) }.sortedByDescending { it.share }
}

/** How hard what landed claims [preset] — the tag weights alone, with no floor. */
private fun Resolving.claimOn(preset: Taggable, speaking: List<Landing>): Double {
    val tags = world.tagsOf(preset)
    val claimed = speaking.filter { it.narrows }.maxOfOrNull { it.claimOn(preset, tags) } ?: 0.0
    val leaned = speaking.sumOf { it.leanOn(preset, tags) }
    return (claimed + leaned).coerceAtLeast(0.0)
}

/**
 * How strong a claim what landed makes on [preset] — a base scaled by readiness, the strongest pull, and every lean,
 * times how much of what was set the preset can honour.
 */
private fun Resolving.strengthOf(preset: Taggable, speaking: List<Landing>): Double {
    val tags = world.tagsOf(preset)
    val claimed = speaking.filter { it.narrows }.maxOfOrNull { it.claimOn(preset, tags) } ?: 0.0
    val leaned = speaking.sumOf { it.leanOn(preset, tags) }
    val wanted = BASE_WEIGHT * world.readinessOf(preset) + claimed + leaned
    return (wanted * capabilityFactor(preset, speaking)).coerceAtLeast(FAINTEST_CHANCE)
}

/** How much of what was set here [preset] can honour — a tilt toward the capable, never a filter. */
private fun capabilityFactor(preset: Taggable, speaking: List<Landing>): Double {
    val asked = speaking.flatMap { it.settings.keys }.distinct().filter(preset.aspect::ownsParameterNamed)
    if (asked.isEmpty()) return FULLY_CAPABLE
    val share = asked.count(preset::honoursParameterNamed).toDouble() / asked.size
    return INCAPABLE_FACTOR + (FULLY_CAPABLE - INCAPABLE_FACTOR) * share
}

/** One preset from [candidates], drawn in proportion to how strongly what landed claims each. */
private fun Resolving.pick(candidates: List<Taggable>, speaking: List<Landing>, aspect: Aspect, seat: Int): Taggable {
    candidates.singleOrNull()?.let { return it }
    val scores = candidates.map { strengthOf(it, speaking) }
    val random = XoroshiroRandomSource(
        draw xor (aspect.ordinal * Resolving.ASPECT_STRIDE) xor (seat * Resolving.TERRITORY_STRIDE),
    )
    var remaining = random.nextDouble() * scores.sum()
    for ((index, score) in scores.withIndex()) {
        remaining -= score
        if (remaining <= 0.0) return candidates[index]
    }
    return candidates.last()
}

/** The composition these fillings describe; the stand-in terrain is overwritten, every catalogue holding one. */
internal fun compose(filled: Map<Aspect, List<Filling>>): AgeComposition {
    var composition = AgeComposition(terrains = listOf(Terrain.OVERWORLD))
    for ((aspect, filling) in filled) {
        if (aspect.holds != Holds.CATALOGUE) continue
        check(filling.isNotEmpty()) { "the ${aspect.page} aspect resolved to nothing, which no book can do" }
        composition = composition.withPresets(aspect, filling.map { it.preset.key }, filling.map { it.share })
    }
    return composition
}
