package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.Acceptance
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Members
import net.minecraft.resources.Identifier

/**
 * What a word list may be ordered under, worked out on the server for the client: how rare each word's page is, and
 * which words each applies to (Jonah, 2026-10-21).
 */
object WordHeadings {

    /** The rarities a player is shown, rarest first; a word on the critical path is shown as rare. */
    enum class Rarity(val key: String) { RARE("rare"), UNCOMMON("uncommon"), COMMON("common"), STAPLE("staple") }

    /** The rarity [word]'s page is drawn at, as a player is told it. */
    fun rarityOf(vocabulary: Vocabulary, word: Word): Rarity = when (vocabulary.rarity.bucketOf(word)?.name) {
        RARE_BUCKET, CRITICAL_BUCKET -> Rarity.RARE
        UNCOMMON_BUCKET -> Rarity.UNCOMMON
        STAPLE_BUCKET -> Rarity.STAPLE
        else -> Rarity.COMMON
    }

    /**
     * The words each word in [vocabulary] applies to: every page that would take it but `age`, every word of ours that
     * takes pages, and a creature, feature or structure of the game's only where the word applies to it alone in its
     * part of the world — `charged` to the creeper, where `strong` would be every creature there is.
     */
    fun appliesTo(vocabulary: Vocabulary): Map<Identifier, List<Identifier>> {
        val lexicon = vocabulary.lexicon
        val takers = vocabulary.words.mapNotNull { taker -> lexicon.acceptance(taker)?.let { Taker(taker, it, vocabulary.isDerived(taker)) } }
        val (members, headings) = takers.partition { it.isOneOfTheGamesMembers }
        val takersNames = takers.map { it.word.id }.toSet()
        return vocabulary.words
            .filter { it.id !in takersNames }
            .associate { word ->
                val under = headings.filter { it.takes(word) }.map { it.word.id }
                val alone = members.filter { it.takes(word) }.groupBy { it.aspect }.values.mapNotNull { it.singleOrNull()?.word?.id }
                word.id to (under + alone).distinct()
            }
            .filterValues { it.isNotEmpty() }
    }

    private class Taker(val word: Word, val acceptance: Acceptance, isDerived: Boolean) {
        val aspect: Aspect = acceptance.place.aspect
        val isOneOfTheGamesMembers = isDerived && acceptance.place.members is Members.Named

        /** Whether [page] laid before this word would be taken by it. */
        fun takes(page: Word): Boolean = page.meanings.any { meaning ->
            if (meaning.isOffered) word.name in meaning.offeredTo else meaning.effects.any { acceptance.take(it) != null }
        }
    }

    private const val RARE_BUCKET = "rare"
    private const val CRITICAL_BUCKET = "critical"
    private const val UNCOMMON_BUCKET = "uncommon"
    private const val STAPLE_BUCKET = "staple"
}
