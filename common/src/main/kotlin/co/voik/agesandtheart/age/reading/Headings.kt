package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import net.minecraft.resources.Identifier

/**
 * What a word list may be ordered under, worked out from the acceptances (Jonah, 2026-10-21): which words each word
 * applies to, the words naming members, and the words naming a whole part of the world. Whether one word takes another
 * is [Usage]'s, which is the reader's rule.
 */
object Headings {

    /**
     * The words each word applies to: every thing or part's page of a pack's that would take something it offers, and a
     * creature, feature or structure of the game's only where the word applies to it alone in its part — where every
     * creature takes it, it is said of the inhabitants, not of each. A word naming members of a part is listed under that
     * part's page: `zombie` and `monsters` under `inhabitants`.
     */
    fun appliesTo(usage: Usage): Map<Identifier, List<Identifier>> {
        val words = usage.words
        val takers = takersIn(words)
        val (ofTheGame, headings) = takers.partition { it.word.id in words.derived && it.namesMembers }
        val takerIds = takers.map { it.word.id }.toSet()
        val pagesFor = takers.filter { it.isAPartsPage(words) }.groupBy({ it.part }, { it.word.id })
        fun takersOf(word: Word, among: List<Heading>) = among.filter { usage.takes(it.word, word) }
        val describing = words.all.filter { it.id !in takerIds && it.defaultSense?.behaviour == null }.associate { word ->
            val under = takersOf(word, headings).map { it.word.id }
            val alone = takersOf(word, ofTheGame).groupBy { it.part }.values.mapNotNull { it.singleOrNull()?.word?.id }
            word.id to (under + alone).distinct()
        }
        val naming = takers.filter { it.namesMembers }.associate { it.word.id to pagesFor[it.part].orEmpty() }
        return (describing + naming).filterValues { it.isNotEmpty() }
    }

    /** The words naming members of a part of the world — `zombie`, `monsters`, `plains`. */
    fun members(words: Words): Set<Identifier> = takersIn(words).filter { it.namesMembers }.map { it.word.id }.toSet()

    /** The words naming a whole part of the world — `inhabitants`, `sky`, `landmass`. */
    fun parts(words: Words): Set<Identifier> = takersIn(words).filter { it.isAPartsPage(words) }.map { it.word.id }.toSet()

    private fun takersIn(words: Words): List<Heading> = words.all.mapNotNull { word ->
        val sense = word.defaultSense?.takeIf { it.endsAPhrase && it.behaviour == null } ?: return@mapNotNull null
        val part = sense.speaksFor ?: return@mapNotNull null
        Heading(word, sense, part)
    }

    /** A word others may be listed under: a thing or a part's page, by its default sense. */
    private class Heading(val word: Word, sense: Sense, val part: Aspect) {
        val namesMembers: Boolean = sense.offers.any { it.concept == CoreConcepts.MEMBER } ||
            sense.means.any { it is Effect.Chooses || it is Effect.Admits }

        fun isAPartsPage(words: Words): Boolean = words.partPage(part)?.id == word.id
    }
}
