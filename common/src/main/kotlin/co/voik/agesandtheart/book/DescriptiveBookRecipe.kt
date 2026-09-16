package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack

/** Turning a book's pages back into the Age they describe. */
object DescriptiveBookRecipe {
    /**
     * The Age this book describes.
     *
     * Page order is word order, so the stored list *is* the sentence — it goes through the same grammar
     * and resolver a written `/age` command does, which is what makes a desk-bound book and a typed
     * command the same act.
     */
    fun of(stack: ItemStack, server: MinecraftServer, ageId: Identifier): AgeRecipe {
        val words = stack.get(AgeContent.BOOK_WORDS).orEmpty()
        if (words.isEmpty()) return AgeRecipe.of(AgePreset.SPIRE, ageId)
        val vocabulary = Vocabulary.of(server)
        val spoken = words.map { it.path }
        // Null is a book with no `age` page, which the desk refuses to bind and no generated book lacks —
        // so this is reachable only from a hand-built stack, and it falls in with "says nothing readable".
        val read = Grammar.read(vocabulary, spoken) ?: return AgeRecipe.of(AgePreset.SPIRE, ageId)
        if (read.isEmpty) return AgeRecipe.of(AgePreset.SPIRE, ageId)
        // The desk's own, where the book has one — so the Age is the world the writer was shown while
        // they were laying the pages out, and not merely one their words could have made.
        val seed = stack.get(AgeContent.BOOK_SEED) ?: AgeRecipe.seedFor(ageId)
        // Whether the rewards will pay out here (design §7.7). A found book carries no such mark, so an
        // Age handed over already written is one you may live in and never one you are paid for.
        val authored = stack.get(AgeContent.BOOK_AUTHORED) == true
        return AgeRecipe.written(server, Resolver.resolve(vocabulary, read, seed), spoken, seed, authored)
    }
}
