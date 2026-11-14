package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.words.Word

/**
 * How rare each word's page is, as a list a player orders by rarity is told it; what a word applies to is
 * [co.voik.agesandtheart.age.reading.Headings]'.
 */
object WordHeadings {

    /**
     * The rarities a player is shown, rarest first. The staple and critical buckets are set apart only so a loot table
     * or a structure can ask for them by name, and are shown as common (Jonah).
     */
    enum class Rarity(val key: String) { RARE("rare"), UNCOMMON("uncommon"), COMMON("common") }

    /** The rarity [word]'s page is drawn at, as a player is told it. */
    fun rarityOf(vocabulary: Vocabulary, word: Word): Rarity = when (vocabulary.bucketOf(word)?.name) {
        RARE_BUCKET -> Rarity.RARE
        UNCOMMON_BUCKET -> Rarity.UNCOMMON
        else -> Rarity.COMMON
    }

    private const val RARE_BUCKET = "rare"
    private const val UNCOMMON_BUCKET = "uncommon"
}
