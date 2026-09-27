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
 * [WriterStock] pool is not asked, since a pool already names exactly what belongs in it.
 */
object CannotAppearInLoot {
    val TAG_NAME: Identifier = "cannot_appear_in_loot".location()

    fun keepsOut(word: Word, vocabulary: Vocabulary, registries: RegistryAccess): Boolean =
        word.name in vocabulary.rarity.keptOutOfLoot || registries.carriesTagNamed(word, TAG_NAME)
}
