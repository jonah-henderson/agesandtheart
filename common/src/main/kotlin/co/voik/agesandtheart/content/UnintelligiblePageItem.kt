package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.Vocabulary
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/**
 * A page whose word the Art no longer has: it teaches nothing, opens nothing and no archive takes it, and it burns.
 * A page becomes one where its word is gone, wherever a player comes across it ([clearedOfGoneWords]).
 */
class UnintelligiblePageItem(properties: Properties) : Item(properties) {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        builder.accept(Component.translatable(SUBTITLE).withStyle(ChatFormatting.GRAY))
    }

    companion object {
        private const val SUBTITLE = "item.agesandtheart.unintelligible_page.subtitle"

        /**
         * What [stack] becomes with every word [vocabulary] no longer names taken out of it — a page made
         * unintelligible, or a notebook holding unintelligible pages where those were — or null where nothing changes.
         */
        fun clearedOfGoneWords(stack: ItemStack, vocabulary: Vocabulary): ItemStack? = when (stack.item) {
            AgeContent.PAGE -> unintelligibleIn(stack, vocabulary)
            AgeContent.NOTEBOOK -> notebookClearedOf(stack, vocabulary)
            else -> null
        }

        private fun unintelligibleIn(page: ItemStack, vocabulary: Vocabulary): ItemStack? {
            val word = page.get(AgeComponents.PAGE_WORD) ?: return null
            if (vocabulary.stillNames(word)) return null
            return ItemStack(AgeContent.UNINTELLIGIBLE_PAGE, page.count)
        }

        private fun notebookClearedOf(notebook: ItemStack, vocabulary: Vocabulary): ItemStack? {
            val pages = NotebookItem.pagesIn(notebook)
            val cleared = pages.map { page -> unintelligibleIn(page, vocabulary) ?: page }
            if (cleared == pages) return null
            return notebook.copy().also { NotebookItem.setPages(it, cleared) }
        }
    }
}
