package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeComponents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Block
import java.util.function.Consumer

/** An archive in hand, saying how many pages it is carrying. */
class ArchiveItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val pages = stack.get(AgeComponents.ARCHIVE_PAGES) ?: return
        builder.accept(
            Component.translatable("item.agesandtheart.archive.pages", pages.total, pages.words.size)
                .withStyle(ChatFormatting.GRAY),
        )
    }
}
