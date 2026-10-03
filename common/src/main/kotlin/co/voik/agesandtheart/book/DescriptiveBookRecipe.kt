package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.content.AgeComponents
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
    fun of(stack: ItemStack, server: MinecraftServer, ageId: Identifier): AgeRecipe =
        of(
            words = stack.get(AgeComponents.BOOK_WORDS).orEmpty(),
            // The desk's own, where the book has one — so the Age is the world the writer was shown while
            // they were laying the pages out, and not merely one their words could have made.
            seed = stack.get(AgeComponents.BOOK_SEED),
            // Whether the rewards will pay out here (design §7.7). A found book carries no such mark, so an
            // Age handed over already written is one you may live in and never one you are paid for.
            authored = stack.get(AgeComponents.BOOK_AUTHORED) == true,
            server = server,
            ageId = ageId,
        )

    /**
     * The Age a book holding [words] at [seed] would describe — asked by the crystal viewer before the book
     * exists, so the world it previews and the world the bound book makes are one expression's answer.
     */
    fun of(words: List<Identifier>, seed: Long?, authored: Boolean, server: MinecraftServer, ageId: Identifier): AgeRecipe {
        if (words.isEmpty()) return sayingNothing(server, ageId)
        val vocabulary = Vocabulary.of(server)
        val spoken = words.map { it.path }
        // Null is a book with no `age` page, which the desk refuses to bind and no generated book lacks —
        // so this is reachable only from a hand-built stack, and it falls in with "says nothing readable".
        val read = Grammar.read(vocabulary, spoken) ?: return sayingNothing(server, ageId)
        if (read.isEmpty) return sayingNothing(server, ageId)
        val drawnAt = seed ?: AgeRecipe.seedFor(ageId)
        return AgeRecipe.written(server, Resolver.resolve(vocabulary, read, drawnAt), spoken, drawnAt, authored)
    }

    /**
     * What a book that says nothing readable describes: a book of the one page `age`, so whatever the
     * defaults draw. It was the Spire, which now stands over a plasma sea a player has to pay masterwork ink
     * for, and which nothing a player holds may reach.
     */
    fun sayingNothing(server: MinecraftServer, ageId: Identifier): AgeRecipe {
        val vocabulary = Vocabulary.of(server)
        val read = requireNotNull(Grammar.read(vocabulary, listOf(AGE_PAGE))) { "a book of `age` alone does not read" }
        val drawnAt = AgeRecipe.seedFor(ageId)
        return AgeRecipe.written(server, Resolver.resolve(vocabulary, read, drawnAt), listOf(AGE_PAGE), drawnAt, false)
    }

    private const val AGE_PAGE = "age"
}
