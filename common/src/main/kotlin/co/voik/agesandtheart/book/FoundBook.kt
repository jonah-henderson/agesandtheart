package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.generation.AgeName
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import kotlin.random.Random

/**
 * A book somebody else already wrote (design §4.5).
 *
 * **The same components in the same order the desk sets**, because a found book and a bound one have to be
 * the same object: everything a player can do with one they must be able to do with the other, and the
 * moment the two differ, testing either says nothing about it.
 */
object FoundBook {
    /** The generation grammar a found book is written from — `art/generation/book.json`. */
    const val GRAMMAR = "book"

    /** What a book is called where the pack ships no name grammar to draw one from. */
    private const val UNNAMED = "Untitled"

    /**
     * [stack] written as a book somebody once wrote, at [seed].
     *
     * **Writes the words even where there is nothing to write**, so a blank book is blank *once*: the
     * caller's cue to try is the words being absent, and a pack with no book grammar would otherwise have
     * every book in the world re-reading the corpus for as long as it existed.
     */
    fun write(stack: ItemStack, server: MinecraftServer, seed: Long) {
        val vocabulary = Vocabulary.of(server)
        val pages = vocabulary.generation.grammar(GRAMMAR)?.expand(Random(seed)).orEmpty()
        val words = pages.mapNotNull { page -> vocabulary.word(page)?.id ?: vocabulary.grammarWord(page)?.id }
        stack.set(AgeContent.BOOK_WORDS, words)
        if (words.isEmpty()) return
        stack.set(AgeContent.BOOK_TITLE, AgeName.drawn(vocabulary, seed)?.read ?: UNNAMED)
        // A generation grammar that dropped the `age` page has written something no player could bind, so
        // the book goes out unread rather than carrying a reading of a sentence it does not spell.
        val read = Grammar.read(vocabulary, pages) ?: return
        stack.set(AgeContent.BOOK_READING, Readout.columnsOf(read))
    }
}
