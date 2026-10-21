package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Pool
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitDouble
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/** The parts of the world that grow in places, so naming a member asks for more where it grows. */
val GROWS_IN_PLACES: Set<Aspect> = setOf(Aspect.FEATURES, Aspect.SPAWNS)

// Naming a member the world has anyway asks for half as much again (Jonah, 2026-10-05).
private const val A_MENTION_IS_WORTH = 0.5
private const val NOTHING_MORE = 0.0

// As much of the world as any one member may be talked into taking: `teeming` said twice.
private const val MOST_OF_A_WORLD = 8.0
private const val NONE_OF_IT = 0.0
private const val NO_LEAN = 0.0

/**
 * Where a description above ordinary introduces its member rather than reweighing one already there, and how many
 * one atmosphere may bring about.
 */
private val INTRODUCES_WHAT_IT_LIFTS = mapOf(Aspect.SPAWNS to 6, Aspect.PHENOMENA to 2)

/**
 * What landed asked each population to hold more or less of — the pass for the aspects nothing is drawn for. Every
 * member is asked how well it answers what landed on it, and each ground a landing is sited in is weighed on its own.
 */
internal fun Resolving.weighed(composition: AgeComposition): AgeComposition {
    var weighed = composition
    for (aspect in Aspect.entries.filter { it.holds == Holds.WEIGHTED_SET }) {
        val pool = aspect.pool ?: continue
        val speaking = speakingTo(aspect)
        if (speaking.isEmpty()) continue
        for (ground in speaking.map { it.sitedIn }.distinct()) {
            weighed = weighedIn(weighed, aspect, pool, speaking.filter { it.sitedIn == ground }, ground)
        }
    }
    return weighed
}

private fun Resolving.weighedIn(
    composition: AgeComposition,
    aspect: Aspect,
    pool: Pool,
    speaking: List<Landing>,
    ground: Identifier?,
): AgeComposition {
    fun namedBy(said: Landing) = said.admitted + listOfNotNull(said.choice?.key)
    val curated = world.availableToBroadWordsIn(aspect)
    val drawnFrom = (curated + speaking.flatMap(::namedBy).distinct().mapNotNull(aspect::presetFor)).distinct()
    val claims = drawnFrom.mapNotNull { member -> claimForMember(member, pool, speaking, aspect, member in curated, ground) }
    val reached = drawnAmong(claims, drawnFrom, speaking, aspect)
    val capped = if (aspect == Aspect.SPAWNS && ground == null) withCaps(composition, drawnFrom, claims, speaking) else composition
    if (reached.isEmpty()) return capped
    val settled = capped.optionsFor(aspect, 0).allOf(pool)
    return capped.withOptions(aspect, pool.name, (settled + spelled(reached, pool, drawnFrom, ground)).distinct())
}

/**
 * [composition] holding the caps what landed moved ([Spawns.CAPS]): for each pass, the mean of what a quantifier
 * asked of each creature in [drawnFrom] it offers, one [claims] struck out not counting.
 */
private fun withCaps(composition: AgeComposition, drawnFrom: List<Taggable>, claims: List<Claim>, speaking: List<Landing>): AgeComposition {
    val struck = claims.filter { it.polarity == Polarity.EXCEPT }.map { it.value }.toSet()
    fun quantityAskedOf(member: Taggable) = abundanceOf(speaking.filter { it.members == null || member.key in it.members.orEmpty() })
    val caps = drawnFrom
        .filter { it.key !in struck }
        .mapNotNull { member -> Identifier.tryParse(member.key)?.let(Spawns::passOf)?.let { it to quantityAskedOf(member) } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, asked) -> asked.average() }
        .filterValues { !Rung.isOrdinary(Rung.legible(it)) }
    if (caps.isEmpty()) return composition
    return composition.withOptions(Aspect.SPAWNS, Spawns.CAPS.name, Spawns.capsSpelled(caps))
}

/**
 * How much of the world one member should have, against what it would have had anyway, or null where nothing that
 * landed reaches it. A lean tilts by how well it answers; a bar bears down on those that clear it; naming one the
 * world has anyway asks for a little more of it unless an amount was asked; `only` and `except` keep and strike.
 */
private fun Resolving.claimForMember(
    member: Taggable,
    pool: Pool,
    speaking: List<Landing>,
    aspect: Aspect,
    alreadyInThePool: Boolean,
    ground: Identifier?,
): Claim? {
    val reaching = speaking.filter { it.members == null || member.key in it.members.orEmpty() }
    val tags = world.tagsOf(member)
    fun strikes(said: Landing) = said.strikes(member, tags) || !said.withinItsLimits(tags)
    if (reaching.any { it.narrows && strikes(it) }) return Claim(member.key, Polarity.EXCEPT, confinedTo = ground)

    fun strictnessFor(said: Landing): Double =
        if (said.polarity != Polarity.ASSERTED) Bars.STRICT else strictnessOf(aspect, said)
    val insisting = reaching.filter { said ->
        val narrowsHere = said.narrows && said.narrowsMembers
        narrowsHere && said.accepts(member, tags, strictnessFor(said))
    }
    val insisted = insisting.sumOf { it.pullOn(tags) }
    val mentions = reaching.count { it.choice?.key == member.key }
    val mentioned = if (world.isPresentAnyway(member)) mentions * A_MENTION_IS_WORTH else NOTHING_MORE
    val leaned = reaching.sumOf { it.leanOn(member, tags) }
    val wanting = insisting + reaching.filter { it !in insisting && it.leanOn(member, tags) > 0.0 }
    val polarity = wanting.map { it.polarity }.firstOrNull { it != Polarity.ASSERTED }
    // Struck by name, `no desert`: named outright, and still asked for none of it.
    if (polarity == Polarity.EXCEPT) return Claim(member.key, Polarity.EXCEPT, confinedTo = ground)
    val rung = abundanceOf(reaching)
    val isQuantified = !Rung.isOrdinary(rung)
    val mentionedUnlessQuantified = if (isQuantified) NOTHING_MORE else mentioned
    val asked = (Rung.ORDINARY + mentionedUnlessQuantified + insisted + leaned) * rung
    val weight = Rung.legible(asked.coerceIn(pool.leastKept, MOST_OF_A_WORLD))
    if (weight <= NONE_OF_IT) return Claim(member.key, Polarity.EXCEPT, confinedTo = ground)

    fun namesItOutright(said: Landing) = said.choice?.key == member.key || member.key in said.admitted
    val namedOutright = reaching.any(::namesItOutright)
    val widened = reaching.any { it.everywhere }
    val growsInPlaces = aspect in GROWS_IN_PLACES
    val namedWhereItGrows = namedOutright && growsInPlaces && !widened
    val described = polarity == null && !widened && (!namedOutright || namedWhereItGrows)
    val speaksForItself = namedOutright && !world.isPresentAnyway(member)
    val nothingToSay = polarity == null && Rung.isOrdinary(weight) && alreadyInThePool && !speaksForItself
    if (nothingToSay) return null
    return Claim(
        member.key,
        polarity ?: Polarity.ASSERTED,
        weight,
        confinedTo = ground,
        onlyWhereItGrows = described,
        introducedIfAbsent = described && namedOutright,
    )
}

/** How many times as much of a member [landings] ask for: every abundance among them, a joined run counted once. */
internal fun abundanceOf(landings: List<Landing>): Double {
    val asking = landings.filter { landing -> landing.effects.any { it is Effect.Abundance } }
    val counted = asking.withIndex().distinctBy { (at, landing) -> landing.joined ?: (-1 - at) }.map { it.value }
    return counted.fold(Rung.ORDINARY) { held, landing ->
        held * landing.effects.filterIsInstance<Effect.Abundance>().fold(Rung.ORDINARY) { rung, it -> rung * it.rung }
    }
}

/**
 * How strict this Age is being about one landing in one aspect — a cut drawn once, between nothing and its own
 * bars, so the same word written twice gives two worlds. Only the uncertain carriers move.
 */
private fun Resolving.strictnessOf(aspect: Aspect, said: Landing): Double {
    val key = draw xor (aspect.ordinal * Resolving.ASPECT_STRIDE) xor
        (said.word.hashCode().toLong() * Resolving.WORD_MIXER) xor Resolving.STRICTNESS_SALT
    return XoroshiroRandomSource(key).nextDouble() * Bars.STRICT
}

/**
 * What a lean brings about, drawn rather than taken whole where a lift introduces its member, weighted by how hard
 * it leaned — so `foreboding` makes an Age dreadful with a few of the right things rather than the catalogue.
 */
private fun Resolving.drawnAmong(
    reached: List<Claim>,
    drawnFrom: List<Taggable>,
    speaking: List<Landing>,
    aspect: Aspect,
): List<Claim> {
    val allowed = INTRODUCES_WHAT_IT_LIFTS[aspect] ?: return reached
    val byKey = drawnFrom.associateBy(Taggable::key)
    fun leanedOn(claim: Claim): Double {
        val member = byKey[claim.value] ?: return NO_LEAN
        val tags = world.tagsOf(member)
        return speaking.sumOf { it.leanOn(member, tags) }
    }
    val lifted = reached
        .filter { it.onlyWhereItGrows && !it.introducedIfAbsent && it.density > Rung.ORDINARY }
        .map { it to leanedOn(it) }
        .filter { (_, leaned) -> leaned > NO_LEAN }
    if (lifted.size <= allowed) return reached
    val kept = drawnFew(lifted, allowed, mix64(draw xor aspect.ordinal.toLong()))
    val dropped = lifted.map { it.first.value }.toSet() - kept
    return reached.filterNot { it.value in dropped }
}

/** [howMany] of [among], drawn without replacement and weighted by how hard each was leaned on. */
private fun drawnFew(among: List<Pair<Claim, Double>>, howMany: Int, seed: Long): Set<String> {
    val left = among.toMutableList()
    val chosen = mutableSetOf<String>()
    var roll = seed
    while (chosen.size < howMany && left.isNotEmpty()) {
        roll = mix64(roll)
        val landing = unitDouble(roll) * left.sumOf { it.second }
        var walked = NO_LEAN
        var taken = left.lastIndex
        for (index in left.indices) {
            walked += left[index].second
            if (walked > landing) {
                taken = index
                break
            }
        }
        chosen += left[taken].first.value
        left.removeAt(taken)
    }
    return chosen
}

/** These claims as a recipe holds them, collapsed to the population's word for emptiness where all are struck. */
private fun spelled(claims: List<Claim>, pool: Pool, drawnFrom: List<Taggable>, ground: Identifier?): List<String> {
    val emptied = pool.emptiedBy ?: return claims.map { it.spelled() }
    val everythingStruck = claims.size == drawnFrom.size && claims.all { it.polarity == Polarity.EXCEPT }
    return if (everythingStruck && ground == null) listOf(emptied) else claims.map { it.spelled() }
}

/**
 * A population's roll grown to what a landing asked, where no page described a body — only into silence, and the
 * largest asked rather than the sum.
 */
internal fun Resolving.cast(composition: AgeComposition): AgeComposition =
    Aspect.entries.filter { it.holds == Holds.POPULATION }.fold(composition) { held, aspect ->
        val speaking = speakingTo(aspect)
        if (speaking.any { it.body != null }) return@fold held
        val asked = speaking.mapNotNull { it.settings[Parameter.CAST]?.toIntOrNull() }.maxOrNull()
        if (asked == null) held else held.withCastOf(aspect, asked)
    }

/** As many bodies of each population as pages described. */
internal fun Resolving.describedMembers(composition: AgeComposition): AgeComposition =
    said.mapNotNull { landing -> landing.body?.let { landing.aspect to it } }
        .groupBy({ it.first }, { it.second })
        .entries.fold(composition) { held, (aspect, bodies) -> held.withCastOf(aspect, bodies.max() + 1) }

/** An abundance landing on an aspect that counts — `teeming stars` — sets how many there are. */
internal fun Resolving.counted(composition: AgeComposition): AgeComposition =
    Aspect.entries.fold(composition) { held, aspect ->
        val count = aspect.count ?: return@fold held
        val rung = abundanceOf(speakingTo(aspect).filter { it.members == null })
        if (Rung.isOrdinary(rung)) return@fold held
        val at = count.at(rung)
        held.withOptions(aspect, count.parameter.name, listOf(Span(at, at).spelled()))
    }
