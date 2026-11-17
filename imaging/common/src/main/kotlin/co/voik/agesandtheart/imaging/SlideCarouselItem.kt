package co.voik.agesandtheart.imaging

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Block
import java.util.function.Consumer

/** The slide carousel as an item, saying how a projector is put together. */
class SlideCarouselItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        // A line a key, since a tooltip does not wrap.
        for (line in 1..HOW_LINES) {
            builder.accept(Component.translatable("block.agesandtheart_imaging.slide_carousel.how.$line").withStyle(ChatFormatting.GRAY))
        }
    }

    private companion object {
        const val HOW_LINES = 3
    }
}
