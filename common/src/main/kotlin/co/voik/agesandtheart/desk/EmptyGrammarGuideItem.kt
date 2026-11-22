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

/** An empty grammar guide in hand, saying what it is for and whether it has been studied in part. */
class EmptyGrammarGuideItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        builder.accept(Component.translatable(HINT).withStyle(ChatFormatting.GRAY))
        if (stack.getOrDefault(AgeComponents.GRAMMAR_STUDIED, 0) > 0) {
            builder.accept(Component.translatable(PARTLY_STUDIED).withStyle(ChatFormatting.GRAY))
        }
    }

    private companion object {
        const val HINT = "item.agesandtheart.empty_grammar_guide.hint"
        const val PARTLY_STUDIED = "item.agesandtheart.empty_grammar_guide.partly_studied"
    }
}
