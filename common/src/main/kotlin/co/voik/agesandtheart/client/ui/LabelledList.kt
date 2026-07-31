package co.voik.agesandtheart.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * One row of a [LabelledList]: a name, and optionally a count sitting against the right edge.
 *
 * Kept out of the list class so the self-referential bound `Entry<E>` resolves — an inner class cannot name
 * itself in its own supertype.
 */
class LabelledRow<T : Any>(
    val value: T,
    private val label: String,
    private val count: Int?,
    private val onActivate: (T) -> Unit,
) : ObjectSelectionList.Entry<LabelledRow<T>>() {

    override fun getNarration(): Component = Component.literal(label)

    override fun extractContent(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        hovered: Boolean,
        a: Float,
    ) {
        val font = Minecraft.getInstance().font
        val washed = hovered || isFocused
        if (hovered) graphics.fill(x, y, x + width, y + height, Palette.HOVER)
        val baseline = y + (height - font.lineHeight) / 2
        graphics.text(font, label, x + TEXT_INSET, baseline, Palette.TEXT, false)
        if (count != null && count > 0) {
            val shown = "$count"
            // Faint grey on the selection wash is close to unreadable, so a washed row states its count
            // in the same ink as its name.
            graphics.text(
                font, shown,
                x + width - TEXT_INSET - font.width(shown), baseline,
                if (washed) Palette.TEXT else Palette.FAINT,
                false,
            )
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        onActivate(value)
        // True regardless, because the list reads it as "this row took the click" and selects accordingly.
        return true
    }

    private companion object {
        const val TEXT_INSET = 2
    }
}

/**
 * A scrolling, selectable list of named things.
 *
 * Vanilla's `ObjectSelectionList` with its menu-screen dressing removed, so it sits on a container panel
 * rather than on the dirt background. It brings a scrollbar, keyboard navigation and hover tracking, none of
 * which is worth hand-rolling.
 */
class LabelledList<T : Any>(
    minecraft: Minecraft,
    bounds: Rect,
    private val onSelect: (T) -> Unit,
) : ObjectSelectionList<LabelledRow<T>>(minecraft, bounds.width, bounds.height, bounds.y, ROW_HEIGHT), Resized {

    init {
        centerListVertically = false
        updateSizeAndPosition(bounds.width, bounds.height, bounds.x, bounds.y)
    }

    /** Entries are positioned when the list is, so a layout moving it has to say so. */
    override fun onResized(bounds: Rect) = place(bounds)

    /**
     * Replaces the contents, keeping the selection where [key] still identifies a row.
     *
     * Selection survives by key rather than by value because a row carries live state — a count that ticks
     * up is the same row, and losing the selection to it would be maddening.
     */
    fun show(
        values: List<T>,
        label: (T) -> String,
        count: (T) -> Int? = { null },
        key: (T) -> Any = { it },
    ) {
        val wasSelected = selected?.value?.let(key)
        val wasScrolledTo = scrollAmount()
        replaceEntries(values.map { LabelledRow(it, label(it), count(it), onSelect) })
        selected = children().firstOrNull { key(it.value) == wasSelected }
        // `setSelected` scrolls the selection back into view, and after a keyboard event it does so
        // unconditionally — which would drag the list away from wherever the reader had put it every time a
        // search keystroke or an inventory change rebuilt the rows. The reader's position wins.
        setScrollAmount(wasScrolledTo)
    }

    fun place(bounds: Rect) = updateSizeAndPosition(bounds.width, bounds.height, bounds.x, bounds.y)

    /** Back to the top, for when the contents change out from under the reader. */
    fun resetScroll() = setScrollAmount(0.0)

    /**
     * `AbstractContainerWidget` routes clicks and scrolls straight to its children without consulting its
     * own visibility, so a hidden list would still answer them. These three put that back.
     */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean =
        visible && super.mouseClicked(event, doubleClick)

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        visible && super.mouseDragged(event, dragX, dragY)

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        visible && super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

    /** Rows run the full width, less a gutter for the scrollbar. */
    override fun getRowWidth(): Int = width - SCROLLBAR_GUTTER

    override fun getRowLeft(): Int = x

    override fun scrollBarX(): Int = right - scrollbarWidth()

    /** A wash, not vanilla's white outline — this list sits on a panel, not on a menu background. */
    override fun extractSelection(graphics: GuiGraphicsExtractor, entry: LabelledRow<T>, outlineColor: Int) {
        graphics.fill(entry.x, entry.y, entry.x + entry.width, entry.y + entry.height, Palette.SELECTION)
    }

    /** Both suppressed: the panel behind is the background, and there is nothing to separate it from. */
    override fun extractListBackground(graphics: GuiGraphicsExtractor) = Unit

    override fun extractListSeparators(graphics: GuiGraphicsExtractor) = Unit

    private companion object {
        const val ROW_HEIGHT = 12
        const val SCROLLBAR_GUTTER = 8
    }
}
