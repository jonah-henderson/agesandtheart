package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Claims
import co.voik.agesandtheart.age.word.Draws
import co.voik.agesandtheart.age.word.Facets

/**
 * **How hard a word claims a property** — whether it insists, or merely offers and gives way to the book.
 *
 * Only properties have this: a claim on a population cannot fail, or is a removal, and neither has
 * anything to yield (`Word.biases`). The population's own steps are `ui.Step`, which only the screens read.
 */
enum class Insistence(val required: Boolean, val title: String, val about: String) {
    REQUIRED(true, "required", "always applies, and overrides anything else"),
    REQUESTED(false, "requested", "applies only where the book said nothing"),
}

/**
 * Where a setting is going: which half of the word, which pool, and which of that pool's offers.
 *
 * [offer] is null for a setting of its own — the ordinary case, and what every facet was before a pool
 * could hold several together. Past the last is what makes a new one, exactly as [pool] does.
 */
data class Into(val insistence: Insistence, val pool: Int? = null, val offer: Int? = null)

/**
 * This word with [parameter] set to [value] wherever [into] points — **making the pool where it is new.**
 *
 * A pool with nothing in it cannot be drawn from and has no heading to edit, so one is never created
 * empty and waiting: the first facet is what brings it into being, pointed one past the last.
 */
fun Candidate.putting(into: Into, parameter: String, value: String): Candidate = when {
    into.pool == null -> putting(into.insistence, parameter, value)
    into.pool >= poolsOn(into.insistence).size -> addingAPool(into.insistence, parameter, value)
    else -> puttingInPool(into.insistence, into.pool, into.offer, parameter, value)
}

/** What is already there, wherever [into] points. */
fun Candidate.holding(into: Into): Map<String, String> =
    if (into.pool == null) settingOn(into.insistence) else poolsOn(into.insistence).getOrNull(into.pool)?.facets.orEmpty()

/**
 * This word leaning [named] by [weight], wherever [aspect] points — `null` being the whole Age.
 *
 * **A lean of nothing is not a lean, so it leaves.** Zero is where every row on the leaning list starts
 * and what enter puts one back to; kept, it is a row on the populations page saying the word does nothing
 * to that member, which is what every member it never mentioned also does.
 */
fun Candidate.leaning(aspect: Aspect?, named: String, weight: Double): Candidate {
    if (aspect == null) {
        val left = if (weight == 0.0) leansEverywhere - named else leansEverywhere + (named to weight)
        return copy(leansEverywhere = left)
    }
    val here = biases[aspect].orEmpty().let { if (weight == 0.0) it - named else it + (named to weight) }
    return copy(biases = if (here.isEmpty()) biases - aspect else biases + (aspect to here))
}

/** The half of this word [insistence] names, whole — what it always does, and every pool it draws from. */
fun Candidate.claimsOn(insistence: Insistence): Claims =
    if (insistence.required) Claims(sets, pools) else requests

/** What that half always does, drawn or not. */
fun Candidate.settingOn(insistence: Insistence): Map<String, String> = claimsOn(insistence).sets

/** The pools on that half, in the order they were written — which is also how the draw is salted. */
fun Candidate.poolsOn(insistence: Insistence): List<Facets> = claimsOn(insistence).pools

/** Everything that half could ever turn, whatever an Age's draw settles on. */
fun Candidate.everythingOn(insistence: Insistence): Map<String, String> = claimsOn(insistence).everything

/** This word with [insistence]'s claims replaced — the one funnel every edit below goes through. */
fun Candidate.withClaims(insistence: Insistence, claims: Claims): Candidate =
    if (insistence.required) copy(sets = claims.sets, pools = claims.pools)
    else copy(requests = requests.copy(sets = claims.sets, pools = claims.pools))

/** This word with [parameter] claimed at [insistence] and always, rather than drawn from a pool. */
fun Candidate.putting(insistence: Insistence, parameter: String, value: String): Candidate =
    withClaims(insistence, claimsOn(insistence).let { it.copy(sets = it.sets + (parameter to value)) })

/** This word without [parameter] among what it claims at [insistence] always. */
fun Candidate.without(insistence: Insistence, parameter: String): Candidate =
    withClaims(insistence, claimsOn(insistence).let { it.copy(sets = it.sets - parameter) })

/**
 * This word with [parameter] set to [value] in the pool [at], among what it claims at [insistence].
 *
 * **[offer] says which of the pool's offers it joins** — null, or past the last, for one of its own. A
 * setting that joins an offer is drawn with the rest of it or not at all, which is the whole of what a
 * group is; a setting of its own is what every facet used to be and still is by default.
 */
fun Candidate.puttingInPool(
    insistence: Insistence,
    at: Int,
    offer: Int?,
    parameter: String,
    value: String,
): Candidate = changingPool(insistence, at) { pool ->
    val joining = offer?.takeIf { it in pool.offers.indices }
    val offers = if (joining == null) {
        pool.offers + listOf(mapOf(parameter to value))
    } else {
        pool.offers.mapIndexed { where, one -> if (where == joining) one + (parameter to value) else one }
    }
    pool.copy(offers = offers)
}

/**
 * This word without [parameter] in the pool [at] — **and without the pool where that was the last of it.**
 *
 * A pool with nothing in it draws from nothing and has no heading left to edit, so the only way back
 * would be the file.
 */
fun Candidate.withoutInPool(insistence: Insistence, at: Int, parameter: String): Candidate {
    val standing = poolsOn(insistence).getOrNull(at) ?: return this
    // Out of whichever offer held it, and the offer with it where that was the last of it — an empty
    // offer is a thing the pool might draw and nothing would happen.
    val left = standing.offers.map { it - parameter }.filter { it.isNotEmpty() }
    if (left.isEmpty()) return withoutPool(insistence, at)
    return changingPool(insistence, at) {
        it.copy(offers = left, draws = Draws.of(it.draws.most.coerceAtMost(left.size)))
    }
}

/**
 * This word without the whole of one of a pool's groups — **and without the pool where that was the last
 * of it**, a pool with nothing to draw being a claim written down and never read.
 */
fun Candidate.withoutOffer(insistence: Insistence, at: Int, which: Int): Candidate {
    val standing = poolsOn(insistence).getOrNull(at) ?: return this
    val left = standing.offers.filterIndexed { where, _ -> where != which }
    if (left.isEmpty()) return withoutPool(insistence, at)
    return changingPool(insistence, at) {
        it.copy(offers = left, draws = Draws.of(it.draws.most.coerceAtMost(left.size)))
    }
}

/**
 * Where this word turns [spelled], outside [into]: in a pool of either half, or set in the half [into] is
 * not. Null where it turns it nowhere else — including where it is already right where [into] points.
 */
fun Candidate.heldElsewhere(spelled: String, into: Into): Held? {
    if (spelled in holding(into)) return null
    val inAPool = Insistence.entries.any { half -> poolsOn(half).any { spelled in it.facets } }
    if (inAPool) return Held.IN_A_POOL
    return Insistence.entries.firstOrNull { half -> half != into.insistence && spelled in settingOn(half) }
        ?.let { if (it.required) Held.REQUIRED else Held.REQUESTED }
}

/** Where a setting being moved came from, as the picker says it. */
enum class Held(val said: String) {
    IN_A_POOL("move out of pool"),
    REQUIRED("move out of required"),
    REQUESTED("move out of requested"),
}

/** What this word says [spelled] is, wherever it holds it — empty where it holds it nowhere. */
fun Candidate.saying(spelled: String): String =
    Insistence.entries.firstNotNullOfOrNull { everythingOn(it)[spelled] }.orEmpty()

/** This word without [spelled] anywhere it is claimed — both halves, and every pool of either. */
fun Candidate.withoutAnywhere(spelled: String): Candidate = Insistence.entries.fold(this) { word, half ->
    // Last pool first, since a pool left empty goes and would move every index after it. Only the pools
    // holding it: taking something out of a pool also re-caps its draw, which the others must keep.
    val holding = word.poolsOn(half).indices.filter { spelled in word.poolsOn(half)[it].facets }.reversed()
    holding.fold(word.without(half, spelled)) { held, at -> held.withoutInPool(half, at, spelled) }
}

/** This word with a pool added to [insistence] — one facet and a count of one, which is the least a pool is. */
fun Candidate.addingAPool(insistence: Insistence, parameter: String, value: String): Candidate =
    withClaims(insistence, claimsOn(insistence).let {
        it.copy(pools = it.pools + Facets(listOf(mapOf(parameter to value)), Draws.of(1)))
    })

fun Candidate.withoutPool(insistence: Insistence, at: Int): Candidate =
    withClaims(insistence, claimsOn(insistence).let { it.copy(pools = it.pools.filterIndexed { where, _ -> where != at }) })

/** This word with the pool [at] drawing [draws] of itself. */
fun Candidate.drawing(insistence: Insistence, at: Int, draws: Draws): Candidate =
    changingPool(insistence, at) { it.copy(draws = draws) }

private fun Candidate.changingPool(insistence: Insistence, at: Int, change: (Facets) -> Facets): Candidate =
    withClaims(insistence, claimsOn(insistence).let { claims ->
        claims.copy(pools = claims.pools.mapIndexed { where, pool -> if (where == at) change(pool) else pool })
    })

/** This word asking [aspect]'s members to carry [tag] at [weight] — the one way a restriction is written. */
fun Candidate.restricting(aspect: Aspect, tag: String, weight: Double): Candidate =
    copy(restricts = restricts + (aspect to (restricts[aspect].orEmpty() + (tag to weight))))

/** One entry out of a map of sets, and the key with it where nothing is left under it. */
internal fun Map<Aspect, Set<String>>.without(aspect: Aspect?, named: String): Map<Aspect, Set<String>> {
    val where = aspect ?: return this
    val here = (this[where].orEmpty() - named).takeIf { it.isNotEmpty() } ?: return this - where
    return this + (where to here)
}

/** The same for a map of weighted things. */
internal fun Map<Aspect, Map<String, Double>>.dropping(
    aspect: Aspect?,
    named: String,
): Map<Aspect, Map<String, Double>> {
    val where = aspect ?: return this
    val here = (this[where].orEmpty() - named).takeIf { it.isNotEmpty() } ?: return this - where
    return this + (where to here)
}
