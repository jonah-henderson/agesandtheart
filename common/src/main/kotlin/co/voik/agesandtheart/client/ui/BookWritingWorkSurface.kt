package co.voik.agesandtheart.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component

/**
 * Where a book is laid out: pages in a wrapped row, rearranged by dragging.
 *
 * **The order is the content.** Page order is word order, so this is a sequence that happens to wrap, not a
 * grid — an insertion goes *between* two pages and pushes the rest along, rather than swapping cells. The
 * columns exist to use the width, and nothing may depend on which column a page lands in.
 *
 * Scrolls, because a book outgrows any fixed height, and grows a row whenever the last one fills. Dragging
 * near an edge scrolls the surface, or a page could never reach a row that is off screen.
 *
 * Drops from elsewhere — the word list, the player's inventory — are the screen's to route: it knows where
 * a drag began, so it asks [insertionAt] where the drop would land and calls back. That keeps this
 * ignorant of everything except the sequence it holds.
 */
class BookWritingWorkSurface<T : Any>(
    bounds: Rect,
    private val columns: Int,
    private val cellHeight: Int,
    private val gutterHeight: Int,
    /** Null leaves the page blank, which is what it reads as while the script is switched off. */
    private val script: (T) -> Component?,
    private val translation: (T) -> String,
    private val onReorder: (from: Int, to: Int) -> Unit,
    private val onRemove: (index: Int) -> Unit,
    /** How many pages this desk can bind, or null for no limit. */
    private val capacity: () -> Int?,
) : AbstractScrollArea(
    bounds.x, bounds.y, bounds.width, bounds.height,
    Component.empty(), defaultSettings(SCROLL_RATE),
), Resized {

    override fun onResized(bounds: Rect) = place(bounds)

    private var pages: List<T> = emptyList()
    private var carrying: Int? = null
    private var dropAt: Int? = null
    private var moved = false

    fun show(values: List<T>) {
        pages = values
        if ((carrying ?: -1) >= values.size) release()
    }

    fun place(bounds: Rect) {
        setPosition(bounds.x, bounds.y)
        setSize(bounds.width, bounds.height)
    }

    val isFull: Boolean get() = capacity()?.let { pages.size >= it } == true

    // ---- geometry: stated once, read by drawing and hit-testing alike ----

    private val slotWidth: Int get() = (width - scrollbarWidth()) / columns

    private val rowHeight: Int get() = cellHeight + gutterHeight

    /** Pages, plus one empty slot to drop into — but never past what the desk could bind. */
    private fun slotCount(): Int {
        val cap = capacity() ?: return pages.size + 1
        return minOf(pages.size + 1, cap)
    }

    private fun slotFor(index: Int): PageSlot {
        val row = index / columns
        val column = index % columns
        val left = x + column * slotWidth
        val top = y + row * rowHeight - scrollAmount().toInt()
        return PageSlot(
            cell = Rect(left, top, slotWidth, cellHeight),
            gutter = Rect(left, top + cellHeight, slotWidth, gutterHeight),
        )
    }

    override fun contentHeight(): Int {
        val rows = (slotCount() + columns - 1) / columns
        return maxOf(1, rows) * rowHeight
    }

    private fun holdsPoint(pointX: Double, pointY: Double): Boolean =
        pointX >= x && pointX < right && pointY >= y && pointY < bottom

    /** Which page is under the cursor, or null over an empty slot or off the surface. */
    private fun pageAt(pointX: Double, pointY: Double): Int? {
        if (!holdsPoint(pointX, pointY)) return null
        return pages.indices.firstOrNull { slotFor(it).bounds.contains(pointX, pointY) }
    }

    /**
     * Where a page dropped here would be inserted, or null if the cursor is off the surface.
     *
     * Rounded to the nearest gap rather than the containing cell, which is what lets a page be dropped
     * *between* two others instead of only onto one.
     */
    fun insertionAt(pointX: Double, pointY: Double): Int? {
        if (!holdsPoint(pointX, pointY)) return null
        val row = ((pointY - y + scrollAmount()) / rowHeight).toInt().coerceAtLeast(0)
        val column = ((pointX - x + slotWidth / 2.0) / slotWidth).toInt().coerceIn(0, columns)
        return (row * columns + column).coerceIn(0, pages.size)
    }

    // ---- drawing ----

    override fun extractWidgetRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        a: Float,
    ) {
        scrollTowardsEdge(mouseY)
        graphics.fill(x, y, right, bottom, Palette.WELL_EDGE)

        graphics.enableScissor(x, y, right, bottom)
        repeat(slotCount()) { index -> drawSlot(graphics, index, mouseX, mouseY) }
        dropAt?.let { drawInsertionCaret(graphics, it) }
        graphics.disableScissor()

        extractScrollbar(graphics, mouseX, mouseY)
        drawCarried(graphics, mouseX, mouseY)
    }

    private fun drawSlot(graphics: GuiGraphicsExtractor, index: Int, mouseX: Int, mouseY: Int) {
        val slot = slotFor(index)
        if (slot.bounds.bottom < y || slot.bounds.y > bottom) return
        val page = pages.getOrNull(index)
        if (page == null) {
            slot.drawEmpty(graphics)
            return
        }
        // The page being carried leaves a hole where it came from, so the sequence reads as it will end up.
        slot.draw(graphics, PageWidget(script(page)), translation(page), dimmed = index == carrying)
        val name = translation(page)
        if (slot.gutter.contains(mouseX.toDouble(), mouseY.toDouble()) && slot.truncates(name)) {
            graphics.setTooltipForNextFrame(Component.literal(name), mouseX, mouseY)
        }
    }

    /** A bar in the gap a drop would open, because "between these two" is otherwise guesswork. */
    private fun drawInsertionCaret(graphics: GuiGraphicsExtractor, at: Int) {
        val onLastEdge = at >= slotCount()
        val slot = slotFor(if (onLastEdge) slotCount() - 1 else at)
        val edge = if (onLastEdge) slot.bounds.right else slot.bounds.x
        graphics.fill(edge - 1, slot.bounds.y, edge + 1, slot.bounds.bottom, Palette.HIGHLIGHT)
    }

    private fun drawCarried(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val page = carrying?.let(pages::getOrNull) ?: return
        if (!moved) return
        val at = Rect(mouseX - slotWidth / 2, mouseY - cellHeight / 2, slotWidth, cellHeight)
        PageWidget(script(page)).draw(graphics, at)
    }

    /** Held near an edge while dragging, the surface comes to the cursor. */
    private fun scrollTowardsEdge(mouseY: Int) {
        if (carrying == null || !moved) return
        if (mouseY < y + EDGE_ZONE) setScrollAmount(scrollAmount() - EDGE_SCROLL)
        else if (mouseY > bottom - EDGE_ZONE) setScrollAmount(scrollAmount() + EDGE_SCROLL)
    }

    // ---- input ----

    /** Right-click removes, so both buttons have to reach [onClick]. */
    override fun isValidClickButton(buttonInfo: MouseButtonInfo): Boolean =
        buttonInfo.button() == LEFT || buttonInfo.button() == RIGHT

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (!visible) return false
        // The scrollbar gets first refusal, but only from the button that drags it.
        if (event.button() == LEFT && updateScrolling(event)) return true
        return super.mouseClicked(event, doubleClick)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        val index = pageAt(event.x, event.y) ?: return
        if (event.button() == RIGHT) {
            onRemove(index)
            return
        }
        carrying = index
        dropAt = null
        moved = false
    }

    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        if (carrying == null) return
        moved = true
        dropAt = insertionAt(event.x, event.y)
    }

    override fun onRelease(event: MouseButtonEvent) {
        // Clears the scrollbar's own drag state; skipping it leaves the bar stuck to the cursor.
        super.onRelease(event)
        val from = carrying ?: return
        val target = dropAt
        release()
        if (!moved) return
        if (target == null) {
            onRemove(from)
            return
        }
        // Removing the page first shifts everything after it down, so a later target moves back by one.
        val to = if (target > from) target - 1 else target
        if (to != from) onReorder(from, to)
    }

    private fun release() {
        carrying = null
        dropAt = null
        moved = false
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, Component.literal(pages.joinToString(", ", transform = translation)))
    }

    private companion object {
        const val SCROLL_RATE = 12
        const val LEFT = 0
        const val RIGHT = 1

        /** How close to an edge counts as asking the surface to move, and how fast it does. */
        const val EDGE_ZONE = 12
        const val EDGE_SCROLL = 4.0
    }
}
