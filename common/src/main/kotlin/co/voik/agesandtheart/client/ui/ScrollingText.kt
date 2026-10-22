package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.FittingMultiLineTextWidget
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Vanilla's text that scrolls where it is too long, with the [ContainerScrollbar] the word lists and the template
 * wear in place of vanilla's, drawn beside it — leave [ROOM] for it to the right.
 */
class ScrollingText(x: Int, y: Int, width: Int, height: Int, font: Font) :
    FittingMultiLineTextWidget(x, y, width, height, Component.empty(), font) {

    private val scrollbar = ContainerScrollbar()

    override fun scrollbarWidth(): Int = ContainerScrollbar.WIDTH

    override fun extractScrollbar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (scrollable()) scrollbar.draw(graphics, track(), scrolledFraction())
    }

    /** The scrollbar's presses and drags are [ContainerScrollbar]'s, which keeps the knob under the pointer. */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val onTheScrollbar = visible && scrollable() && isOverScrollbar(event.x(), event.y())
        if (!onTheScrollbar) return super.mouseClicked(event, doubleClick)
        scrollTo(scrollbar.press(track(), scrolledFraction(), event.y()))
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val held = scrollbar.drag(track(), event.y()) ?: return super.mouseDragged(event, dragX, dragY)
        scrollTo(held)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        scrollbar.release()
        return super.mouseReleased(event)
    }

    override fun scrollBarX(): Int = right + GAP

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean =
        super.isMouseOver(mouseX, mouseY) || (active && visible && isOverScrollbar(mouseX, mouseY))

    private fun track() = Rect(scrollBarX(), y, scrollbarWidth(), height)

    private fun scrolledFraction(): Double = if (maxScrollAmount() > 0) scrollAmount() / maxScrollAmount() else 0.0

    private fun scrollTo(fraction: Double) = setScrollAmount(fraction * maxScrollAmount())

    companion object {
        /** Between the text and its scrollbar. */
        private const val GAP = 2

        /** What a [ScrollingText] needs to its right for its scrollbar. */
        const val ROOM = ContainerScrollbar.WIDTH + GAP
    }
}
