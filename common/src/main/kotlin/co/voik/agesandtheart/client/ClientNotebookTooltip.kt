package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.NotebookTooltip
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import kotlin.math.abs
import kotlin.math.exp

/**
 * A notebook's pages as a grid in its tooltip: [NotebookTooltip.ROWS] rows of [NotebookTooltip.COLUMNS], every cell a
 * page, under a small title saying which page is scrolled to of how many. Past the grid's edge it turns a whole page
 * of rows, easing there rather than jumping. Vanilla's bundle grid gave a cell to a count of what it hid and moved a
 * row at a time with the highlight held in the middle, which read oddly over a notebook of hundreds.
 */
class ClientNotebookTooltip(private val notebook: NotebookTooltip) : ClientTooltipComponent {

    private val rows: Int
        get() = Mth.positiveCeilDiv(notebook.pages.size, NotebookTooltip.COLUMNS).coerceAtMost(NotebookTooltip.ROWS)

    override fun getHeight(font: Font): Int = titleHeight(font) + rows * SLOT + MARGIN

    override fun getWidth(font: Font): Int = GRID_WIDTH

    override fun showTooltipWithItemInHand(): Boolean = true

    override fun extractImage(font: Font, x: Int, y: Int, w: Int, h: Int, graphics: GuiGraphicsExtractor) {
        val left = x + (w - GRID_WIDTH) / 2
        graphics.centeredText(font, title(), left + GRID_WIDTH / 2, y, TITLE_INK)
        val top = y + titleHeight(font)
        val firstRow = Scroll.shownRow(notebook)
        graphics.enableScissor(left, top, left + GRID_WIDTH, top + rows * SLOT)
        val fromRow = Mth.floor(firstRow)
        for (row in fromRow..fromRow + NotebookTooltip.ROWS) {
            val rowTop = top + Mth.floor((row - firstRow) * SLOT)
            for (column in 0..<NotebookTooltip.COLUMNS) {
                val index = row * NotebookTooltip.COLUMNS + column
                if (index !in notebook.pages.indices) continue
                extractSlot(graphics, font, index, left + column * SLOT, rowTop)
            }
        }
        graphics.disableScissor()
        extractSelectedName(font, graphics, x, y, w)
    }

    private fun title(): Component {
        val total = notebook.pages.size
        val selected = notebook.selected ?: return Component.translatable(COUNT_KEY, total)
        return Component.translatable(POSITION_KEY, selected + 1, total)
    }

    private fun titleHeight(font: Font): Int = font.lineHeight + MARGIN

    private fun extractSlot(graphics: GuiGraphicsExtractor, font: Font, index: Int, slotX: Int, slotY: Int) {
        val isSelected = index == notebook.selected
        val page = notebook.pages[index]
        val back = if (isSelected) HIGHLIGHT_BACK else BACKGROUND
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, back, slotX, slotY, SLOT, SLOT)
        graphics.item(page, slotX + ITEM_INSET, slotY + ITEM_INSET, index)
        graphics.itemDecorations(font, page, slotX + ITEM_INSET, slotY + ITEM_INSET)
        if (isSelected) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HIGHLIGHT_FRONT, slotX, slotY, SLOT, SLOT)
    }

    /** The page scrolled to, named in a tooltip of its own over this one, as vanilla names a bundle's. */
    private fun extractSelectedName(font: Font, graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int) {
        val page = notebook.selected?.let(notebook.pages::getOrNull) ?: return
        val name = page.styledHoverName.visualOrderText
        val nameTooltip = ClientTooltipComponent.create(name)
        val centre = x + w / 2 - SLOT / 2
        graphics.tooltip(
            font,
            listOf(nameTooltip),
            centre - font.width(name) / 2,
            y - NAME_ABOVE,
            DefaultTooltipPositioner.INSTANCE,
            page.get(DataComponents.TOOLTIP_STYLE),
            false,
        )
    }

    /**
     * Where the grid is shown from, in rows, easing toward the page that holds the one scrolled to. One tooltip is
     * drawn at a time, so one scroll is kept, and a different notebook starts where it is rather than sliding in.
     * Render thread only.
     */
    private object Scroll {
        private var shown = 0f
        private var lastPages: List<*>? = null
        private var lastNanos = 0L

        fun shownRow(notebook: NotebookTooltip): Float {
            val target = (notebook.firstShown / NotebookTooltip.COLUMNS).toFloat()
            val now = System.nanoTime()
            val last = lastPages
            val isAnotherNotebook = last?.size != notebook.pages.size || last.firstOrNull() != notebook.pages.firstOrNull()
            val startsAfresh = isAnotherNotebook || now - lastNanos > IDLE_NANOS
            shown = if (startsAfresh) target else eased(shown, target, (now - lastNanos) / NANOS_PER_SECOND)
            lastPages = notebook.pages
            lastNanos = now
            return shown
        }

        private fun eased(from: Float, to: Float, seconds: Float): Float {
            val left = to - from
            if (abs(left) < SETTLED) return to
            return from + left * (1f - exp(-seconds * EASING_PER_SECOND))
        }

        /** Past this long unshown, a tooltip is opened afresh and starts where it is. */
        private const val IDLE_NANOS = 200_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val EASING_PER_SECOND = 14f
        private const val SETTLED = 0.01f
    }

    private companion object {
        const val SLOT = 24
        const val ITEM_INSET = 4
        const val GRID_WIDTH = SLOT * NotebookTooltip.COLUMNS
        const val MARGIN = 4
        const val NAME_ABOVE = 15
        val TITLE_INK = 0xFFA0A0A0.toInt()
        const val COUNT_KEY = "item.agesandtheart.notebook.count"
        const val POSITION_KEY = "item.agesandtheart.notebook.position"

        val BACKGROUND: Identifier = Identifier.withDefaultNamespace("container/bundle/slot_background")
        val HIGHLIGHT_BACK: Identifier = Identifier.withDefaultNamespace("container/bundle/slot_highlight_back")
        val HIGHLIGHT_FRONT: Identifier = Identifier.withDefaultNamespace("container/bundle/slot_highlight_front")
    }
}
