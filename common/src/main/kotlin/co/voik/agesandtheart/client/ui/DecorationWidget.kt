package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.CommonComponents

/**
 * A [Decoration] as a widget, so it can be positioned by a layout and rendered by a screen.
 *
 * **Never takes input.** `isMouseOver` is false so a surface can sit under a stack of live widgets without
 * swallowing a click meant for one of them — a screen picks the first child under the cursor, and a
 * background that answered would shadow everything it decorates.
 */
class DecorationWidget(
    private val decoration: Decoration,
) : AbstractWidget(0, 0, 0, 0, CommonComponents.EMPTY) {

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) = decoration.draw(graphics, Rect(x, y, width, height))

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
}
