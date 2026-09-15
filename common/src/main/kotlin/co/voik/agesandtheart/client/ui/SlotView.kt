package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents

/**
 * An inventory slot, as it looks.
 *
 * Built from the item position the menu states; the widget covers the 18×18 frame around that 16×16.
 *
 * Never takes input: the menu's own `Slot` is what a click resolves against, and answering here would
 * shadow it.
 */
class SlotView(itemX: Int, itemY: Int) : AbstractWidget(
    itemX - Palette.SLOT_INSET,
    itemY - Palette.SLOT_INSET,
    Palette.SLOT,
    Palette.SLOT,
    CommonComponents.EMPTY,
) {

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) = SlotSurface.draw(graphics, Rect(x, y, width, height))

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
