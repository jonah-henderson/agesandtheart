package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import net.minecraft.core.RegistryAccess
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack

/**
 * Writing an item that was sold **unwritten** — a page or a notebook carrying the stock it came from
 * rather than the words it holds.
 *
 * **Why anything is sold unwritten at all**: a villager's offer is built once and bought many times, so a
 * word rolled while the offer was being made is the word every copy carries. Deferring the roll to the
 * moment somebody takes the item is what makes a writer worth trading with twice.
 *
 * Called from `MerchantResultSlotMixin`, at the one instant a purchase is a single stack that has not yet
 * merged with anything — which is why this takes a whole stack and asks nothing about where it came from.
 */
object StockedItems {

    /** How many words a notebook bought from a stock holds. */
    private const val FEWEST_PAGES = 4
    private const val MOST_PAGES = 5

    /** Whether [stack] is waiting to be written, so a caller can skip the common case cheaply. */
    @JvmStatic
    fun isUnwritten(stack: ItemStack): Boolean = stack.has(AgeContent.STOCKED_FROM)

    /**
     * [stack] written from the stock it names, in place.
     *
     * Silent where the pool turns out to hold nothing: an empty stock is a content fault the checks catch,
     * and a page that stays unwritten says so plainly in the hand.
     */
    @JvmStatic
    fun write(stack: ItemStack, level: ServerLevel, random: RandomSource) =
        write(stack, Vocabulary.of(level.server), level.registryAccess(), random)

    /** The same, of a corpus and some registries — so what it writes can be checked without a server. */
    fun write(stack: ItemStack, vocabulary: Vocabulary, registries: RegistryAccess, random: RandomSource) {
        val pool = stack.get(AgeContent.STOCKED_FROM) ?: return
        when {
            stack.`is`(AgeContent.PAGE) -> {
                val word = vocabulary.stock.draw(pool, vocabulary, registries, random) ?: return
                stack.set(AgeContent.PAGE_WORD, word.id)
            }
            stack.`is`(AgeContent.NOTEBOOK) -> {
                val wanted = FEWEST_PAGES + random.nextInt(MOST_PAGES - FEWEST_PAGES + 1)
                val pages = (1..wanted)
                    .mapNotNull { vocabulary.stock.draw(pool, vocabulary, registries, random) }
                    // A notebook somebody kept would not hold the same word twice.
                    .distinctBy { it.id }
                    .map { word ->
                        ItemStack(AgeContent.PAGE).also { it.set(AgeContent.PAGE_WORD, word.id) }
                    }
                if (pages.isEmpty()) return
                NotebookItem.setPages(stack, pages)
            }
            else -> return
        }
        // Written now, so it is an ordinary page and stacks with other pages of that word.
        stack.remove(AgeContent.STOCKED_FROM)
    }
}
