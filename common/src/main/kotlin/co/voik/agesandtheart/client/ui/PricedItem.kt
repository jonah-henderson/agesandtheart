package co.voik.agesandtheart.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * One line of a price: an item, and how much of it is wanted, in warning ink when there is not enough.
 *
 * The amount is text rather than a count so a fraction can be said. Every reading is a function, so the
 * widget is built once and redrawn from live state.
 */
class PricedItem(
    width: Int,
    private val icon: () -> ItemStack,
    private val amount: () -> String,
    private val isShort: () -> Boolean,
    private val tooltip: () -> List<Component>,
) : AbstractWidget(0, 0, width, Palette.ITEM, Component.empty()) {

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val font = Minecraft.getInstance().font
        graphics.item(icon(), x, y)
        val ink = if (isShort()) Palette.WARNING else Palette.TEXT
        graphics.text(font, amount(), x + Palette.ITEM + AMOUNT_GAP, y + (height - font.lineHeight) / 2 + 1, ink, false)
        val hovered = mouseX >= x && mouseX < right && mouseY >= y && mouseY < bottom
        // An empty list is "nothing to say" rather than an empty box: a price that reads off the row
        // already does not want a tooltip repeating it, so most of these are silent most of the time.
        if (hovered) {
            val lines = tooltip()
            if (lines.isNotEmpty()) graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY)
        }
    }

    /** Reports rather than accepts, so it never takes a click from anything beneath it. */
    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    private companion object {
        const val AMOUNT_GAP = 1
    }
}
