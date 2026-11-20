package co.voik.agesandtheart.book

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Block
import java.util.function.Consumer

/**
 * A block in hand with how to use it beneath its name: [lines] lines in grey, each its own translation,
 * `<the block's key>.tooltip.0` and on, so each breaks where it was written to.
 */
class HintedBlockItem(block: Block, properties: Properties, private val lines: Int) : BlockItem(block, properties) {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        for (line in 0 until lines) builder.accept(Component.translatable("$descriptionId.tooltip.$line").withStyle(ChatFormatting.GRAY))
    }
}
