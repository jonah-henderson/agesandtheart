package co.voik.agesandtheart.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * An item with how much of it there is, set against the right edge.
 *
 * Right-aligned so the digits line up down a column rather than the icons — a stock list is read by
 * comparing quantities, and ragged numbers make that work.
 */
class CountedItem(
    width: Int,
    /** The line's own height, which a tight list may set below an item's 16 so the rows sit closer. */
    height: Int = Palette.ITEM,
    private val icon: () -> ItemStack,
    private val count: () -> Int,
) : AbstractWidget(0, 0, width, height, Component.empty()) {

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) {
        val font = Minecraft.getInstance().font
        graphics.item(icon(), x, y)
        val shown = "${count()}"
        val baseline = y + (height - font.lineHeight) / 2
        graphics.text(font, shown, right - font.width(shown), baseline, Palette.TEXT, false)
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
