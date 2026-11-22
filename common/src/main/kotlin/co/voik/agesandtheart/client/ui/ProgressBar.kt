package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/** A well filled from the left as [reading] goes from nothing to done, with [tooltip] over it. */
class ProgressBar(
    width: Int,
    height: Int,
    private val reading: () -> Float,
    private val colour: Int,
    private val tooltip: () -> Component,
) : AbstractWidget(0, 0, width, height, Component.empty()) {

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val bounds = Rect(x, y, width, height)
        graphics.fill(bounds.x, bounds.y, bounds.right, bounds.bottom, Palette.WELL_EDGE)
        val well = bounds.inset(1)
        graphics.fill(well.x, well.y, well.right, well.bottom, Palette.WELL)
        val filled = (well.width * reading().coerceIn(0f, 1f)).toInt()
        if (filled > 0) graphics.fill(well.x, well.y, well.x + filled, well.bottom, colour)
        if (bounds.contains(mouseX.toDouble(), mouseY.toDouble())) {
            graphics.setTooltipForNextFrame(tooltip(), mouseX, mouseY)
        }
    }

    /** Reports rather than accepts, so it never takes a click from anything beneath it. */
    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
