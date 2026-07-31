package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents

/**
 * An inventory slot, as it looks.
 *
 * The recess is not decoration applied to a slot — it is what an empty slot *is*, so the two are one thing
 * and a slot always brings its own. Built from the item position the menu already states, because that is
 * the number that has to agree; the widget covers the 18×18 frame around that 16×16.
 *
 * It never takes input: the menu's own `Slot` is what a click resolves against, and answering here would
 * shadow it.
 */
class SlotView(itemX: Int, itemY: Int) : AbstractWidget(
    itemX - Palette.SLOT_INSET,
    itemY - Palette.SLOT_INSET,
    Palette.SLOT,
    Palette.SLOT,
    CommonComponents.EMPTY,
) {

    /** Moves the recess to frame the item area at [itemX], [itemY]. */
    fun frameItemAt(itemX: Int, itemY: Int) {
        setPosition(itemX - Palette.SLOT_INSET, itemY - Palette.SLOT_INSET)
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) = SlotSurface.draw(graphics, Rect(x, y, width, height))

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
