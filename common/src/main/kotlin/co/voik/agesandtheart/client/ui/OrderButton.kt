package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.network.chat.Component

/**
 * A small square button that cycles a list through the orders it offers, showing the current one's glyph and naming
 * it on hover. [offered] is asked at each press, so an order that becomes possible while the screen is open is
 * there for the next.
 */
class OrderButton<O>(
    size: Int,
    private var current: O,
    private val offered: () -> List<O>,
    private val glyph: (O) -> String,
    private val named: (O) -> Component,
    private val chosen: (O) -> Unit,
) : Button(0, 0, size, size, Component.literal(glyph(current)), { }, DEFAULT_NARRATION) {

    init {
        showing(current)
    }

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractDefaultSprite(graphics)
        extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE))
    }

    override fun onPress(input: InputWithModifiers) {
        val orders = offered()
        val next = orders.getOrNull(orders.indexOf(current) + 1) ?: orders.first()
        showing(next)
        chosen(next)
    }

    /** Shows [order] without choosing it — for a screen putting back the order it was in. */
    fun showing(order: O) {
        current = order
        message = Component.literal(glyph(order))
        setTooltip(Tooltip.create(named(order)))
    }
}
