package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * The creative inventory's protruding tabs, as one widget.
 *
 * Vanilla's own tab components (`TabNavigationBar`) are the options-screen style and cannot produce this
 * look, so the drawing is ours — but the geometry is stated once, in [boundsOf], and both the drawing and
 * the hit test read it from there.
 *
 * **Drawing takes two passes**, because the panel goes between them: unselected tabs tuck behind it and so
 * must be drawn before it ([backdrop]), while the selected tab sits proud and is drawn in the
 * widget pass, which the screen runs afterwards.
 */
class TabStrip<T : Any>(
    x: Int,
    y: Int,
    private val tabs: List<T>,
    initial: T,
    private val icon: (T) -> ItemStack,
    private val label: (T) -> Component,
    private val onSelect: (T) -> Unit,
) : AbstractWidget(x, y, tabs.size * SPACING, HEIGHT, CommonComponents.EMPTY) {

    var selected: T = initial
        private set

    /** When each tab last had news, so its icon can bounce. Zero means never. */
    var pulsedAt: (T) -> Long = { 0L }

    /** The one statement of where a tab is. Everything else asks this. */
    fun boundsOf(tab: T): Rect {
        val index = tabs.indexOf(tab).coerceAtLeast(0)
        return Rect(x + index * SPACING, y, WIDTH, HEIGHT)
    }

    private fun tabAt(pointX: Double, pointY: Double): T? =
        tabs.firstOrNull { boundsOf(it).contains(pointX, pointY) }

    /**
     * The unselected tabs, as a widget of their own.
     *
     * Added to a screen *before* the panel so they tuck under its edge, while the strip itself is added
     * after and draws the selected tab proud of it. Two widgets rather than a second render hook, so
     * everything on the screen stays in one back-to-front list.
     */
    val backdrop: AbstractWidget = DecorationWidget { graphics, _ ->
        tabs.forEach { if (it != selected) extract(graphics, it) }
    }

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) {
        extract(graphics, selected)
        tabAt(mouseX.toDouble(), mouseY.toDouble())?.let {
            graphics.setTooltipForNextFrame(label(it), mouseX, mouseY)
        }
    }

    private fun extract(graphics: GuiGraphicsExtractor, tab: T) {
        val at = boundsOf(tab)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, spriteFor(tab), at.x, at.y, at.width, at.height)

        val iconX = at.x + ICON_INSET
        val iconY = at.y + ICON_TOP
        val squash = squashOf(tab)
        if (squash == null) {
            graphics.item(icon(tab), iconX, iconY)
            return
        }
        // Vanilla's own squash: narrower as it stretches, so it reads as a bounce rather than a zoom.
        val centreX = iconX + HALF_ICON
        val centreY = iconY + HALF_ICON
        graphics.pose().pushMatrix()
        graphics.pose().translate(centreX, centreY)
        graphics.pose().scale(1f / squash, (squash + 1f) / 2f)
        graphics.pose().translate(-centreX, -centreY)
        graphics.item(icon(tab), iconX, iconY)
        graphics.pose().popMatrix()
    }

    /** How far through a bounce a tab is, or null if it is not bouncing. */
    private fun squashOf(tab: T): Float? {
        val since = pulsedAt(tab)
        if (since == 0L) return null
        val elapsed = System.currentTimeMillis() - since
        if (elapsed > PULSE_MS) return null
        return 1f + (1f - elapsed.toFloat() / PULSE_MS) * PULSE_DEPTH
    }

    /** Vanilla ships seven, each drawn differently, so the index has to follow the tab's own position. */
    private fun spriteFor(tab: T): Identifier {
        val state = if (tab == selected) "selected" else "unselected"
        val index = tabs.indexOf(tab).coerceIn(0, VANILLA_SPRITES - 1)
        return Identifier.withDefaultNamespace("container/creative_inventory/tab_top_${state}_${index + 1}")
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        val clicked = tabAt(event.x, event.y) ?: return
        if (clicked == selected) return
        selected = clicked
        onSelect(clicked)
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = tabAt(mouseX, mouseY) != null

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, label(selected))
    }

    companion object {
        // The creative inventory's own numbers.
        private const val WIDTH = 26
        private const val HEIGHT = 32
        private const val SPACING = 27
        private const val ICON_INSET = 5
        private const val ICON_TOP = 9
        private const val HALF_ICON = 8f
        private const val VANILLA_SPRITES = 7

        /** How far the strip stands above whatever it labels. */
        const val LIFT = 28

        /** A bounce of a quarter, decaying over a fifth of a second. */
        private const val PULSE_MS = 200f
        private const val PULSE_DEPTH = 0.25f
    }
}
