package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.PageScreen
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
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
        if (stack.get(AgeContent.PAGE_WORD) == null) return InteractionResult.PASS
        // Guarded so the screen class is never loaded on a dedicated server.
        if (level.isClientSide) PageScreen.open(stack)
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val word = stack.get(AgeContent.PAGE_WORD) ?: return
        builder.accept(WordNames.readable(word).copy().withStyle(ChatFormatting.GRAY))
    }
}
