package co.voik.agesandtheart.content

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Block
import java.util.function.Consumer

/**
 * A seismograph in the hand, which says what to do with it.
 *
 * **A tooltip here and not on the astrite** (Jonah, 2026-09-07). The lure's height rule was refused one,
 * because a hidden mechanic scattered across whatever items happen to carry a hint teaches worse than an
 * advancement tree — but this is not a hidden mechanic. It is what the block in your hand is *for*, which
 * is the ordinary work a tooltip does.
 */
class SeismographItem(block: Block, properties: Item.Properties) : BlockItem(block, properties) {

    override fun appendHoverText(
        stack: net.minecraft.world.item.ItemStack,
        context: Item.TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        builder.accept(Component.translatable(HINT).withStyle(ChatFormatting.GRAY))
    }

    private companion object {
        const val HINT = "item.agesandtheart.seismograph.hint"
    }
}
