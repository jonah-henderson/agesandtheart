package co.voik.agesandtheart.content

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Block
import java.util.function.Consumer

/**
 * A toolbox in hand, saying what spares it holds — **one line a kind of tool**, counted across its
 * compartments.
 *
 * Vanilla's container tooltip gives every compartment its own line, which in a box of spares is the same
 * pickaxe three times over. The item hides that one ([AgeContent.TOOLBOX]) and says this instead, in its
 * words: "3x Stone Pickaxe", five lines at most, and how many more beyond them.
 */
class ToolboxItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val held = stack.get(DataComponents.CONTAINER) ?: return
        val kinds = kindsAmong(held.nonEmptyItemCopyStream().toList())
        val shown = kinds.take(MOST_LINES)
        for (kind in shown) {
            builder.accept(Component.translatable(ITEM_COUNT, kind.count, kind.name))
        }
        val notShown = kinds.drop(MOST_LINES).sumOf { it.count }
        if (notShown > 0) {
            builder.accept(Component.translatable(MORE_ITEMS, notShown).withStyle(ChatFormatting.ITALIC))
        }
    }

    /** One kind of thing in the box, and how many of it there are. */
    data class Kind(val name: Component, val count: Int)

    companion object {
        /**
         * What [held] comes to, a line a kind, in the order each kind first appears.
         *
         * Two spares are one kind where they are the same item **and read the same**: wear does not split
         * them, since a box of spares is mostly worn tools, but a renamed one is told apart.
         */
        fun kindsAmong(held: List<ItemStack>): List<Kind> =
            held.groupBy { it.item to it.hoverName.string }
                .values
                .map { alike -> Kind(alike.first().hoverName, alike.sumOf { it.count }) }

        /** Vanilla's own: a container's tooltip shows five lines before it says how many more. */
        private const val MOST_LINES = 5

        private const val ITEM_COUNT = "item.container.item_count"
        private const val MORE_ITEMS = "item.container.more_items"
    }
}
