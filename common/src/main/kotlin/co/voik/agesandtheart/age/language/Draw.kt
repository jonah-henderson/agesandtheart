package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.math.mix64
import kotlin.random.Random

/**
 * Which of a book's claims land, drawn page by page off the book's seed (`notes/meanings-plan.md` §2, Drawing what
 * lands):
 *
 * - each claim lands on its own chance — aimed its chance to apply, laid bare its chance unaimed, or its chance to
 *   apply times [Meaning.BARE_CHANCE] where it names none;
 * - alternatives — one page setting the same parameters at one place, or minting — land as one, and which by their
 *   chances;
 * - a meaning that must always apply lands at least once, and a page with none lands at least one claim.
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
        for (alternatives in ofThePage.groupBy { alternativesKey(it.value) ?: it.index }.values) {
            val chance = alternatives.maxOf { chanceOf(it.value) }
            if (random.nextDouble() < chance) landed += pickedFrom(alternatives, random).index
        }
        // In the order the page holds them, so a book draws the same every time; by identity, since two meanings of
        // one word may be equal and still be two meanings.
        val mustApply = mutableListOf<Meaning>()
        for (meaning in ofThePage.map { it.value.meaning }) {
            if (meaning.mustAlwaysApply && mustApply.none { it === meaning }) mustApply += meaning
        }
        for (meaning in mustApply) {
            val its = ofThePage.filter { it.value.meaning === meaning }
            if (its.none { it.index in landed }) landed += its[random.nextInt(its.size)].index
        }
        if (landed.isEmpty() && ofThePage.isNotEmpty()) landed += ofThePage.maxBy { chanceOf(it.value) }.index
        return ofThePage.filter { it.index in landed }.map { it.value }
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
