package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.location
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier

/**
 * Words the corpus-wide loot draws skip: a found page and a found notebook. The word still exists and is
 * writable once learned another way — [DerivedWords.DOES_NOT_HAVE_A_WORD] is the fence that unmakes one.
 *
 * A word naming a registry entry is kept out by tagging the entry; an authored word names nothing, so it
 * is listed by name in [WordRarity.KEPT_OUT_OF_LOOT_FILE] — the same split [InkRequirement] makes. A
 * [WriterStock] pool is asked only about [EASTER_EGG], since a pool already names what belongs in it.
 */
object CannotAppearInLoot {
    val TAG_NAME: Identifier = "cannot_appear_in_loot".location()

    /**
     * What only an easter-egg Age is made of — the Spire's barrens. Kept out of every page drawn, a pool's
     * included, so the only way to meet one is to find the Age. Any registry a word can name may carry it.
     */
    val EASTER_EGG: Identifier = "easter_egg".location()

    fun keepsOut(word: Word, vocabulary: Vocabulary, registries: RegistryAccess): Boolean =
        word.name in vocabulary.rarity.keptOutOfLoot ||
            registries.carriesTagNamed(word, TAG_NAME) ||
            isEasterEgg(word, registries)

    fun isEasterEgg(word: Word, registries: RegistryAccess): Boolean = registries.carriesTagNamed(word, EASTER_EGG)
}
