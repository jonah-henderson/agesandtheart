package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents

/**
 * The player's own inventory: three rows, with the hotbar set below them.
 *
 * The gap above the hotbar is vanilla's four pixels. Positions only — the slots that hold items are the
 * menu's, and this is what sits behind them.
 */
class PlayerInventoryView(
    itemX: Int,
    itemY: Int,
    /** Taken from the menu rather than assumed, so the recesses cannot drift from the real slots. */
    private val hotbarDrop: Int,
) : AbstractWidget(
    itemX - Palette.SLOT_INSET,
    itemY - Palette.SLOT_INSET,
    COLUMNS * Palette.SLOT,
    hotbarDrop + Palette.SLOT,
    CommonComponents.EMPTY,
) {

    fun frameItemAt(itemX: Int, itemY: Int) {
        setPosition(itemX - Palette.SLOT_INSET, itemY - Palette.SLOT_INSET)
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) {
        repeat(ROWS) { row -> drawRow(graphics, y + row * Palette.SLOT) }
        drawRow(graphics, y + hotbarDrop)
    }

    private fun drawRow(graphics: GuiGraphicsExtractor, rowY: Int) {
        repeat(COLUMNS) { column ->
            SlotSurface.draw(
                graphics,
                Rect(x + column * Palette.SLOT, rowY, Palette.SLOT, Palette.SLOT),
            )
        }
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    companion object {
        const val COLUMNS = 9
        const val ROWS = 3
        const val WIDTH = COLUMNS * Palette.SLOT
    }
}
