package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import net.minecraft.resources.Identifier

/**
 * What a word list may be ordered under, worked out from the acceptances (Jonah, 2026-10-21): which words each word
 * applies to, the words naming members, and the words naming a whole part of the world.
 */
object Headings {

    /**
     * The words each word in [words] applies to: every thing or part's page of a pack's that would take something it
     * offers, and a creature, feature or structure of the game's only where the word applies to it alone in its part —
     * where every creature takes it, it is said of the inhabitants, not of each. A word naming members of a part is
     * listed under that part's page: `zombie` and `monsters` under `inhabitants`.
     */
    fun appliesTo(words: Words): Map<Identifier, List<Identifier>> {
        val takers = takersIn(words)
        val (ofTheGame, headings) = takers.partition { it.word.id in words.derived && it.namesMembers }
        val takerIds = takers.map { it.word.id }.toSet()
        val pagesFor = takers.filter { it.isAPartsPage(words) }.groupBy({ it.part }, { it.word.id })
        val describing = words.all.filter { it.id !in takerIds && it.defaultSense?.behaviour == null }.associate { word ->
            val under = headings.filter { it.takes(word) }.map { it.word.id }
            val alone = ofTheGame.filter { it.takes(word) }.groupBy { it.part }.values.mapNotNull { it.singleOrNull()?.word?.id }
            word.id to (under + alone).distinct()
        }
        val naming = takers.filter { it.namesMembers }.associate { it.word.id to pagesFor[it.part].orEmpty() }
        return (describing + naming).filterValues { it.isNotEmpty() }
    }

    /** The words naming members of a part of the world — `zombie`, `monsters`, `plains`. */
    fun members(words: Words): Set<Identifier> = takersIn(words).filter { it.namesMembers }.map { it.word.id }.toSet()

    /** The words naming a whole part of the world — `inhabitants`, `sky`, `landmass`. */
    fun parts(words: Words): Set<Identifier> = takersIn(words).filter { it.isAPartsPage(words) }.map { it.word.id }.toSet()

    private fun takersIn(words: Words): List<Taker> = words.all.mapNotNull { word ->
        val sense = word.defaultSense?.takeIf { it.endsAPhrase && it.behaviour == null } ?: return@mapNotNull null
        val part = sense.speaksFor ?: return@mapNotNull null
        Taker(word, sense, part)
    }

    private class Taker(val word: Word, val sense: Sense, val part: Aspect) {
        val namesMembers: Boolean = sense.offers.any { it.concept == CoreConcepts.MEMBER } ||
            sense.means.any { it is Effect.Chooses || it is Effect.Admits }

        fun isAPartsPage(words: Words): Boolean = words.partPage(part)?.id == word.id

        /** Whether a page of [describing] laid before this word would be taken by it. */
        fun takes(describing: Word): Boolean = describing.senses.any { their -> their.offers.any(::takes) }

        private fun takes(offer: Offer): Boolean {
            val audience = offer.audience
            val admitted = audience == Audience.Anyone || (audience is Audience.Part && audience.aspect == part)
            if (!admitted) return false
            return offer.isAReading || sense.acceptanceOf(offer.concept) != null
        }
    }
}
