package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word
import net.minecraft.core.component.DataComponents
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.PageScreen
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * A page of the Art with one word written on it.
 *
 * The word is rolled when the page is generated, not when it is read, so a page is a *specific* find —
 * two players opening the same chest see the same word, and a page can be traded for what it says.
 * Learning happens on pickup (see `PageLearning`); reading it is flavour.
 */
class PageItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (stack.get(AgeComponents.PAGE_WORD) == null) return InteractionResult.PASS
        // Guarded so the screen class is never loaded on a dedicated server.
        if (level.isClientSide) PageScreen.open(stack)
        return InteractionResult.SUCCESS
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val word = stack.get(AgeComponents.PAGE_WORD) ?: return
        builder.accept(WordNames.readable(word).copy().withStyle(ChatFormatting.GRAY))
    }

    companion object {
        /**
         * A page with [word] written on it — **the one way to make one.**
         *
         * A written page is a stack plus a data component, and spelling that out at each of the eight
         * places that hand one over meant anything a page must carry besides its word would have to be
         * added at all eight. The loot functions, the desk, the instruments and the toast all come through
         * here now.
         */
        fun writtenWith(word: Identifier): ItemStack =
            ItemStack(AgeContent.PAGE).also { it.set(AgeComponents.PAGE_WORD, word) }

        /** The same, with the name coloured by the rarity of [word]'s bucket — what every server path makes. */
        fun writtenWith(word: Word, vocabulary: Vocabulary): ItemStack =
            ItemStack(AgeContent.PAGE).also { write(it, word, vocabulary) }

        /**
         * [word] written on [stack] in place, as a loot function writes the page it is handed.
         *
         * **Every server path must stamp the rarity**, or two pages of one word carry different components
         * and will not stack.
         */
        fun write(stack: ItemStack, word: Word, vocabulary: Vocabulary) {
            stack.set(AgeComponents.PAGE_WORD, word.id)
            val bucket = vocabulary.rarity.bucketOf(word) ?: return
            stack.set(DataComponents.RARITY, bucket.itemRarity)
        }
    }
}
