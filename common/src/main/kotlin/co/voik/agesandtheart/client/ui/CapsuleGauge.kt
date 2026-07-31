package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * A capsule-shaped well filled from the bottom.
 *
 * The shape is the brewing stand's `fuel_length` gauge stood on end — a bar whose ends are rounded by
 * cutting the corners back. Vanilla ships only the horizontal one, so the vertical is drawn here;
 * [CAP_INSETS] is the entire shape, and widening it rounds the ends harder.
 *
 * Its readings are functions, so the gauge is built once and redrawn from live state.
 */
class CapsuleGauge(
    width: Int,
    height: Int,
    private val reading: () -> Float,
    private val colour: () -> Int,
    private val tooltip: () -> Component,
) : AbstractWidget(0, 0, width, height, Component.empty()) {

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) {
        val bounds = Rect(x, y, width, height)
        fillCapsule(graphics, bounds, bounds, Palette.WELL_EDGE)

        val well = bounds.inset(1)
        fillCapsule(graphics, well, well, Palette.WELL)
        val level = (well.height * reading().coerceIn(0f, 1f)).toInt()
        if (level > 0) {
            fillCapsule(graphics, well, Rect(well.x, well.bottom - level, well.width, level), colour())
        }

        if (bounds.contains(mouseX.toDouble(), mouseY.toDouble())) {
            graphics.setTooltipForNextFrame(tooltip(), mouseX, mouseY)
        }
    }

    /** Reports rather than accepts, so it never takes a click from anything beneath it. */
    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

    private companion object {
        /** How far each row from an end is drawn in — which is the whole of the capsule's shape. */
        val CAP_INSETS = intArrayOf(2, 1)

        fun insetWithin(shape: Rect, y: Int): Int {
            val fromEnd = minOf(y - shape.y, shape.bottom - 1 - y)
            return if (fromEnd in CAP_INSETS.indices) CAP_INSETS[fromEnd] else 0
        }

        /**
         * Fills [region]'s rows, each clipped to the capsule profile of [shape].
         *
         * Runs of equal inset are drawn as one rectangle, so a tall gauge costs five fills rather than one
         * per row.
         */
        fun fillCapsule(graphics: GuiGraphicsExtractor, shape: Rect, region: Rect, colour: Int) {
            var top = region.y
            while (top < region.bottom) {
                val inset = insetWithin(shape, top)
                var bottom = top + 1
                while (bottom < region.bottom && insetWithin(shape, bottom) == inset) bottom++
                graphics.fill(shape.x + inset, top, shape.right - inset, bottom, colour)
                top = bottom
            }
        }
    }
}
