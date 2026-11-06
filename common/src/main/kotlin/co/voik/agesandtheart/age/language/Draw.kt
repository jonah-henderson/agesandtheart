package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.math.mix64
import kotlin.random.Random

/**
 * Which of a book's claims land, drawn page by page off the book's seed (`the-world-model.md` §3):
 *
 * - each claim lands on its own chance — aimed its chance to apply, laid bare its chance unaimed, or its chance to
 *   apply times [Meaning.BARE_CHANCE] where it names none;
 * - alternatives — one page setting the same parameters at one place, or minting — land as one, and which by their
 *   chances;
 * - a meaning that must always apply lands at least once, and a page with none lands at least one claim;
 * - a broad meaning laid bare is drawn once, and lands at every place it reaches or at none, winning each place over
 *   the alternatives it meets there.
 */
internal object Draw {

    // Arbitrary, and only so neighbouring pages draw apart.
    private const val PAGE_STRIDE = -0x61c8_8646_80b5_83ebL

    fun of(claims: List<Claim>, pages: List<PageRead>, template: String?, seed: Long): Reading {
        val landed = claims.withIndex().groupBy { it.value.page }.toSortedMap().values.flatMap { ofThePage ->
            val page = ofThePage.first().value.page
            landedOf(ofThePage, Random(mix64(seed xor (page * PAGE_STRIDE))))
        }
        return Reading(pages, landed.map(::landing), claims.map(::landing), template)
    }

    private fun landedOf(ofThePage: List<IndexedValue<Claim>>, random: Random): List<Claim> {
        val landed = sortedSetOf<Int>()
        val broadDrawn = broadMeaningsDrawn(ofThePage, random)
        fun isOfADrawnBroadMeaning(claim: Claim) = isBroadHere(claim) && broadDrawn.any { it === claim.meaning }
        fun isOfAnUndrawnBroadMeaning(claim: Claim) = isBroadHere(claim) && broadDrawn.none { it === claim.meaning }
        for (alternatives in ofThePage.groupBy { alternativesKey(it.value) ?: it.index }.values) {
            val broad = alternatives.firstOrNull { isOfADrawnBroadMeaning(it.value) }
            if (broad != null) {
                landed += broad.index
                continue
            }
            val drawable = alternatives.filterNot { isOfAnUndrawnBroadMeaning(it.value) }
            if (drawable.isEmpty()) continue
            val chance = drawable.maxOf { chanceOf(it.value) }
            if (random.nextDouble() < chance) landed += pickedFrom(drawable, random).index
        }
        // In the order the page holds them, so a book draws the same every time; by identity, since two meanings of
        // one word may be equal and still be two meanings. A broad one laid bare has been drawn whole already.
        val mustApply = mutableListOf<Meaning>()
        for ((claim, meaning) in ofThePage.map { it.value to it.value.meaning }) {
            if (isBroadHere(claim)) continue
            if (meaning.mustAlwaysApply && mustApply.none { it === meaning }) mustApply += meaning
        }
        for (meaning in mustApply) {
            val its = ofThePage.filter { it.value.meaning === meaning }
            if (its.none { it.index in landed }) landed += its[random.nextInt(its.size)].index
        }
        if (landed.isEmpty() && ofThePage.isNotEmpty()) {
            val likeliest = ofThePage.maxBy { chanceOf(it.value) }
            // A broad meaning lands whole or not at all, so where it is the one landed, it lands everywhere it reaches.
            val its = if (isBroadHere(likeliest.value)) ofThePage.filter { it.value.meaning === likeliest.value.meaning } else listOf(likeliest)
            landed += its.map { it.index }
        }
        return ofThePage.filter { it.index in landed }.map { it.value }
    }

    /** Whether [claim] is of a broad meaning laid bare, which is drawn once for every place it reaches. */
    private fun isBroadHere(claim: Claim): Boolean = claim.meaning.broad && !claim.aimed

    /** The broad meanings laid bare on this page that land, each drawn once: certain where it must always apply. */
    private fun broadMeaningsDrawn(ofThePage: List<IndexedValue<Claim>>, random: Random): List<Meaning> {
        val broad = mutableListOf<Claim>()
        for (claim in ofThePage.map { it.value }) {
            if (isBroadHere(claim) && broad.none { it.meaning === claim.meaning }) broad += claim
        }
        return broad.filter { claim -> claim.meaning.mustAlwaysApply || random.nextDouble() < chanceOf(claim) }.map { it.meaning }
    }

    /** What makes claims alternatives: the place, and the parameters they set or that they mint. */
    private fun alternativesKey(claim: Claim): Any? {
        val parameters = claim.effects.filterIsInstance<Effect.Sets>().map { it.parameter.name }.toSet()
        val mints = claim.effects.any { it is Effect.Mints }
        if (parameters.isEmpty() && !mints) return null
        return Triple(claim.place, parameters, mints)
    }

    private fun pickedFrom(alternatives: List<IndexedValue<Claim>>, random: Random): IndexedValue<Claim> {
        val chances = alternatives.map { chanceOf(it.value) }
        var roll = random.nextDouble() * chances.sum()
        for ((at, chance) in chances.withIndex()) {
            roll -= chance
            if (roll <= 0.0) return alternatives[at]
        }
        return alternatives.last()
    }

    private fun chanceOf(claim: Claim): Double {
        val meaning = claim.meaning
        return when {
            claim.aimed && meaning.mustAlwaysApply -> Meaning.CERTAIN
            claim.aimed -> meaning.chanceToApply
            meaning.mustAlwaysApply -> Meaning.BARE_CHANCE
            else -> meaning.unaimedChanceToApply ?: (meaning.chanceToApply * Meaning.BARE_CHANCE)
        }
    }

    private fun landing(claim: Claim) = Landing(
        effects = claim.effects,
        place = claim.place,
        word = claim.word.name,
        clause = claim.clause,
        page = claim.page,
        aimed = claim.aimed,
        chance = chanceOf(claim),
        isAnOffer = !claim.meaning.mustAlwaysApply && claim.meaning.chanceToApply < Meaning.CERTAIN,
        joined = claim.joined,
        sitedIn = claim.sitedIn,
        everywhere = claim.everywhere,
        polarity = claim.polarity,
    )
}
