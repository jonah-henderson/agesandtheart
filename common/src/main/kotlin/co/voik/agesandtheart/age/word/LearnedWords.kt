package co.voik.agesandtheart.age.word

import com.mojang.serialization.Codec
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/**
 * The words one player has read, and so may write.
 *
 * Modelled on `ServerRecipeBook`, vanilla's only knowledge-acquisition machinery: a set the player
 * carries, saved in their own data, copied when the entity is rebuilt, validated on load.
 *
 * Insertion-ordered — the sequence is a record of where the player has been.
 */
class LearnedWords {
    private val known = LinkedHashSet<Identifier>()

    val words: Set<Identifier> get() = known

    fun knows(word: Identifier): Boolean = word in known

    /** @return whether this word was new, which is what decides if the player is told. */
    fun learn(word: Identifier): Boolean = known.add(word)

    fun forget(word: Identifier): Boolean = known.remove(word)

    /**
     * Takes over [other]'s knowledge wholesale. Not optional: a `ServerPlayer` is rebuilt on death and on
     * every dimension change, which this mod does constantly.
     */
    fun copyFrom(other: LearnedWords) {
        known.clear()
        known += other.known
    }

    fun pack(): Packed = Packed(known.toList())

    /**
     * Restores from [packed] **without validating**, unlike vanilla's `loadUntrusted`.
     *
     * Recipes are code and can only vanish permanently; words are datapack content, so a pack removed for
     * an evening would otherwise erase what a player had found. An id the vocabulary no longer has is kept,
     * and means nothing until a pack supplies it again.
     */
    fun load(packed: Packed) {
        known.clear()
        known += packed.words
    }

    /** The saved form. A list, not a set: the order is part of the record. */
    data class Packed(val words: List<Identifier>) {
        companion object {
            /** `@JvmField` so the Mixin, which is Java, can name it without going through the companion. */
            @JvmField
            val CODEC: Codec<Packed> = Identifier.CODEC.listOf().xmap(::Packed, Packed::words)
        }
    }

    companion object {
        /** Where this sits in the player's save data, beside vanilla's `recipeBook`. */
        const val SAVE_KEY = "learned_words"
    }
}

/**
 * Added to `ServerPlayer` by Mixin, so a player carries this the way they carry the recipe book rather
 * than through a side table. The prefixed name avoids colliding with another mod doing the same.
 */
interface LearnedWordsHolder {
    fun agesandtheart_learnedWords(): LearnedWords
}

/** The words this player knows. */
val ServerPlayer.learnedWords: LearnedWords
    get() = (this as LearnedWordsHolder).agesandtheart_learnedWords()
