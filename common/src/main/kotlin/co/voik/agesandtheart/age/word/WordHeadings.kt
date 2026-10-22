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

    /**
     * The rarities a player is shown, rarest first. The staple and critical buckets are set apart only so a loot table
     * or a structure can ask for them by name, and are shown as common (Jonah).
     */
    enum class Rarity(val key: String) { RARE("rare"), UNCOMMON("uncommon"), COMMON("common") }

    /** The rarity [word]'s page is drawn at, as a player is told it. */
    fun rarityOf(vocabulary: Vocabulary, word: Word): Rarity = when (vocabulary.rarity.bucketOf(word)?.name) {
        RARE_BUCKET -> Rarity.RARE
        UNCOMMON_BUCKET -> Rarity.UNCOMMON
        else -> Rarity.COMMON
    }

    /**
     * The words each word in [vocabulary] applies to: every page that would take it but `age`, every word of ours that
     * takes pages, and a creature, feature or structure of the game's only where the word applies to it alone in its
     * part of the world — `charged` to the creeper, where `strong` would be every creature there is. A word naming
     * members of a part of the world, `zombie` or `monsters`, is listed under the page for that part, `inhabitants`.
     */
    fun appliesTo(vocabulary: Vocabulary): Map<Identifier, List<Identifier>> {
        val takers = takersIn(vocabulary)
        val (members, headings) = takers.partition { it.isOneOfTheGamesMembers }
        val takersNames = takers.map { it.word.id }.toSet()
        val pagesFor = takers.filter { it.isAPart }.groupBy({ it.aspect }, { it.word.id })
        val modifiers = vocabulary.words
            .filter { it.id !in takersNames }
            .associate { word ->
                val under = headings.filter { it.takes(word) }.map { it.word.id }
                val alone = members.filter { it.takes(word) }.groupBy { it.aspect }.values.mapNotNull { it.singleOrNull()?.word?.id }
                word.id to (under + alone).distinct()
            }
        val namingMembers = takers.filter { it.namesMembers }.associate { it.word.id to pagesFor[it.aspect].orEmpty() }
        return (modifiers + namingMembers).filterValues { it.isNotEmpty() }
    }

    /** The words naming members of a part of the world — `zombie`, `monsters`, `plains`. */
    fun members(vocabulary: Vocabulary): Set<Identifier> = takersIn(vocabulary).filter { it.namesMembers }.map { it.word.id }.toSet()

    /** The words naming a whole part of the world — `inhabitants`, `sky`, `landmass`. */
    fun parts(vocabulary: Vocabulary): Set<Identifier> = takersIn(vocabulary).filter { it.isAPart }.map { it.word.id }.toSet()

    private fun takersIn(vocabulary: Vocabulary): List<Taker> = vocabulary.words.mapNotNull { taker ->
        vocabulary.lexicon.acceptance(taker)?.let { Taker(taker, it, vocabulary.isDerived(taker)) }
    }

    private class Taker(val word: Word, val acceptance: Acceptance, isDerived: Boolean) {
        val aspect: Aspect = acceptance.place.aspect
        val namesMembers = acceptance.place.members is Members.Named
        val isAPart = acceptance.place.members == Members.All
        val isOneOfTheGamesMembers = isDerived && namesMembers

        /** Whether [page] laid before this word would be taken by it. */
        fun takes(page: Word): Boolean = page.meanings.any { meaning ->
            if (meaning.isOffered) word.name in meaning.offeredTo else meaning.effects.any { acceptance.take(it) != null }
        }
    }

    private const val RARE_BUCKET = "rare"
    private const val UNCOMMON_BUCKET = "uncommon"
}
