package co.voik.agesandtheart.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Something a row offers to do to the thing it names — the buttons at its right edge.
 *
 * **Drawn by the row rather than parented to it**, which is not an economy: `AbstractContainerWidget` routes
 * clicks and scrolls to its children *without consulting its own `visible` flag*, so real button widgets in
 * a list that hides with its tab would still answer clicks meant for whatever replaced it. A row already
 * receives the click and already knows its own rectangle, so it can say which button was hit without any of
 * that.
 *
 * [enabled] answers per value, and a disabled button still takes the hover so [tooltip] can say why — which
 * is the whole point of disabling rather than hiding it.
 */
class RowAction<T : Any>(
    val glyph: String,
    val tooltip: (T) -> Component,
    val enabled: (T) -> Boolean = { true },
    val act: (T) -> Unit,
)

/**
 * [label] broken into lines no wider than [width], the second and later ones [indent] pixels in.
 *
 * Breaks between words where it can, and inside a word only when the word alone is too wide for a line.
 */
fun wrapLabel(label: String, width: Int, indent: Int, measure: (String) -> Int): List<String> {
    val lines = mutableListOf<String>()
    var line = ""
    fun widthFor(lineIndex: Int) = if (lineIndex == 0) width else width - indent
    for (word in label.split(' ')) {
        val joined = if (line.isEmpty()) word else "$line $word"
        if (measure(joined) <= widthFor(lines.size)) {
            line = joined
            continue
        }
        if (line.isNotEmpty()) lines += line
        line = word
        while (measure(line) > widthFor(lines.size)) {
            val fits = generateSequence(line.length - 1) { it - 1 }
                .takeWhile { it > 1 }
                .firstOrNull { measure(line.take(it)) <= widthFor(lines.size) }
                ?: 1
            lines += line.take(fits)
            line = line.drop(fits)
        }
    }
    lines += line
    return lines
}

/** A run of a [LabelledList]'s values under one heading, or under none. */
data class ListGroup<T : Any>(val heading: String?, val values: List<T>)

/**
 * One line of a [LabelledList]: a row naming one of its values, or a heading over a run of them.
 *
 * Kept out of the list class so the self-referential bound `Entry<E>` resolves — an inner class cannot name
 * itself in its own supertype.
 */
sealed class ListLine<T : Any> : ObjectSelectionList.Entry<ListLine<T>>()

/** A heading over a run of a [LabelledList]'s rows, which a click folds away or opens out. */
class HeadingLine<T : Any>(private val text: String, private val isCollapsed: Boolean, private val onToggle: () -> Unit) : ListLine<T>() {

    override fun getNarration(): Component = Component.literal(text)

    override fun extractContent(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, a: Float) {
        val font = Minecraft.getInstance().font
        if (hovered) graphics.fill(x, y, x + width, y + height, Palette.HOVER)
        val baseline = y + (LabelledRow.FIRST_LINE_HEIGHT - font.lineHeight) / 2 + 1
        val marker = if (isCollapsed) COLLAPSED else EXPANDED
        graphics.text(font, "$marker $text", x + HEADING_INSET, baseline, Palette.FAINT, false)
        graphics.fill(x + HEADING_INSET, y + height - 1, x + width - HEADING_INSET, y + height, Palette.FAINT)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        onToggle()
        return true
    }

    private companion object {
        const val HEADING_INSET = 1
        const val COLLAPSED = "[+]"
        const val EXPANDED = "[-]"
    }
}

/**
 * One row of a [LabelledList]: a name, optionally a count, and whatever the row offers to do.
 *
 * A name too long for the row wraps onto indented lines beneath it; the count and the buttons stay on the
 * first.
 */
class LabelledRow<T : Any>(
    val value: T,
    private val label: String,
    private val lines: List<String>,
    private val count: Int?,
    private val onActivate: (T) -> Unit,
    private val actions: List<RowAction<T>> = emptyList(),
    /** Shown while the row is hovered away from its buttons; nothing when empty. */
    private val tooltip: (T) -> List<Component> = { emptyList() },
) : ListLine<T>() {

    /** Where each action's button sits, left to right against the row's right edge. */
    private fun buttonAt(index: Int): Int = x + width - (actions.size - index) * BUTTON_PITCH

    override fun getNarration(): Component = Component.literal(label)

    override fun extractContent(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        hovered: Boolean,
        a: Float,
    ) {
        val font = Minecraft.getInstance().font
        if (hovered) graphics.fill(x, y, x + width, y + height, Palette.HOVER)
        val baseline = y + (FIRST_LINE_HEIGHT - font.lineHeight) / 2
        lines.forEachIndexed { index, line ->
            val indent = if (index == 0) 0 else WRAP_INDENT
            graphics.text(font, line, x + TEXT_INSET + indent, baseline + index * font.lineHeight, Palette.TEXT, false)
        }
        if (hovered) {
            // Set before the buttons, so a hovered button's own tooltip replaces it.
            val lines = tooltip(value)
            if (lines.isNotEmpty()) graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY)
        }

        for ((index, action) in actions.withIndex()) {
            val left = buttonAt(index)
            val onTheFirstLine = mouseY >= y && mouseY < y + FIRST_LINE_HEIGHT
            val over = mouseX >= left && mouseX < left + BUTTON_WIDTH && onTheFirstLine
            val live = action.enabled(value)
            if (over) {
                graphics.fill(left, y, left + BUTTON_WIDTH, y + FIRST_LINE_HEIGHT, Palette.HOVER)
                graphics.setTooltipForNextFrame(action.tooltip(value), mouseX, mouseY)
            }
            val ink = if (live) Palette.TEXT else Palette.FAINT
            graphics.text(font, action.glyph, left + (BUTTON_WIDTH - font.width(action.glyph)) / 2, baseline, ink, false)
        }

        if (count != null && count > 0) {
            val shown = "$count"
            graphics.text(font, shown, countRight() - font.width(shown), baseline, Palette.TEXT, false)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val clickX = event.x()
        val onTheFirstLine = event.y() < y + FIRST_LINE_HEIGHT
        for ((index, action) in actions.withIndex()) {
            if (!onTheFirstLine) break
            val left = buttonAt(index)
            if (clickX < left || clickX >= left + BUTTON_WIDTH) continue
            // A disabled button still swallows the click: it is a button that is not ready, not a gap in
            // the row, and letting the press fall through to "select this word" would read as a misfire.
            if (action.enabled(value)) action.act(value)
            return true
        }
        onActivate(value)
        // True regardless, because the list reads it as "this row took the click" and selects accordingly.
        return true
    }

    /** Where the count has to stop, so it cannot run under the buttons. */
    private fun countRight(): Int =
        if (actions.isEmpty()) x + width - TEXT_INSET else buttonAt(0) - TEXT_INSET

    companion object {
        private const val TEXT_INSET = 2
        private const val WRAP_INDENT = 6

        /** A glyph and a little air, which is as much as a twelve-pixel row has to give. */
        private const val BUTTON_WIDTH = 11
        private const val BUTTON_PITCH = 12

        const val FIRST_LINE_HEIGHT = 12

        /** A row of [rowWidth] holding [label], its [count] and [actionCount] buttons, wrapped to fit. */
        fun <T : Any> wrapped(
            value: T,
            label: String,
            count: Int?,
            onActivate: (T) -> Unit,
            actions: List<RowAction<T>>,
            rowWidth: Int,
            tooltip: (T) -> List<Component>,
        ): LabelledRow<T> {
            val font = Minecraft.getInstance().font
            val countWidth = if (count != null && count > 0) font.width("$count") + TEXT_INSET else 0
            val labelWidth = rowWidth - TEXT_INSET * 2 - countWidth - actions.size * BUTTON_PITCH
            val lines = wrapLabel(label, labelWidth.coerceAtLeast(MINIMUM_LABEL_WIDTH), WRAP_INDENT, font::width)
            return LabelledRow(value, label, lines, count, onActivate, actions, tooltip)
        }

        fun heightOf(row: LabelledRow<*>): Int =
            FIRST_LINE_HEIGHT + (row.lines.size - 1) * Minecraft.getInstance().font.lineHeight

        /** Before the list has been laid out it has no width, and nothing should wrap a letter a line. */
        private const val MINIMUM_LABEL_WIDTH = 24
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
) : ObjectSelectionList<ListLine<T>>(minecraft, bounds.width, bounds.height, bounds.y, LabelledRow.FIRST_LINE_HEIGHT),
    Resized {

    /** The last [show], kept so a new width can wrap the same rows again. */
    private var reshow: () -> Unit = {}

    /** The headings a click has turned from how their groups start, open or folded. */
    private val toggled = mutableSetOf<String>()

    /** Whether the groups shown last started folded, so a list that starts the other way forgets the clicks. */
    private var startedCollapsed = false

    private val scrollbar = ContainerScrollbar()

    init {
        centerListVertically = false
        updateSizeAndPosition(bounds.width, bounds.height, bounds.x, bounds.y)
    }

    /** Entries are positioned when the list is, so a layout moving it has to say so — and rewrapped. */
    override fun onResized(bounds: Rect) {
        place(bounds)
        reshow()
    }

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
        actions: List<RowAction<T>> = emptyList(),
        tooltip: (T) -> List<Component> = { emptyList() },
    ) = showGrouped(listOf(ListGroup(null, values)), label, count, key, actions, tooltip)

    /**
     * [groups] in order, each under its heading — a value may stand in more than one. A heading folds its group away
     * or opens it out when clicked; [startsCollapsed] says which way every group starts, and a folded group's rows are
     * never built, which is what keeps a list of thousands under its headings quick.
     */
    fun showGrouped(
        groups: List<ListGroup<T>>,
        label: (T) -> String,
        count: (T) -> Int? = { null },
        key: (T) -> Any = { it },
        actions: List<RowAction<T>> = emptyList(),
        tooltip: (T) -> List<Component> = { emptyList() },
        startsCollapsed: Boolean = false,
    ) {
        reshow = { showGrouped(groups, label, count, key, actions, tooltip, startsCollapsed) }
        if (startsCollapsed != startedCollapsed) toggled.clear()
        startedCollapsed = startsCollapsed
        val wasSelected = (selected as? LabelledRow<T>)?.value?.let(key)
        val wasScrolledTo = scrollAmount()
        clearEntries()
        for (group in groups) {
            val heading = group.heading
            val isCollapsed = heading != null && startsCollapsed != (heading in toggled)
            heading?.let { addEntry(HeadingLine(it, isCollapsed) { toggle(it) }, HEADING_HEIGHT) }
            if (isCollapsed) continue
            for (value in group.values) {
                val row = LabelledRow.wrapped(value, label(value), count(value), onSelect, actions, rowWidth, tooltip)
                addEntry(row, LabelledRow.heightOf(row))
            }
        }
        selected = children().filterIsInstance<LabelledRow<T>>().firstOrNull { key(it.value) == wasSelected }
        // `setSelected` scrolls the selection back into view, and after a keyboard event it does so
        // unconditionally — which would drag the list away from wherever the reader had put it every time a
        // search keystroke or an inventory change rebuilt the rows. The reader's position wins.
        setScrollAmount(wasScrolledTo)
    }

    fun place(bounds: Rect) = updateSizeAndPosition(bounds.width, bounds.height, bounds.x, bounds.y)

    private fun toggle(heading: String) {
        if (!toggled.remove(heading)) toggled += heading
        reshow()
    }

    /** Back to the top, for when the contents change out from under the reader. */
    fun resetScroll() = setScrollAmount(0.0)

    /**
     * `AbstractContainerWidget` routes clicks and scrolls straight to its children without consulting its
     * own visibility, so a hidden list would still answer them. These put that back, and take the scrollbar's
     * presses and drags for [ContainerScrollbar].
     */
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (!visible) return false
        val onTheScrollbar = scrollable() && isOverScrollbar(event.x(), event.y())
        if (!onTheScrollbar) return super.mouseClicked(event, doubleClick)
        scrollTo(scrollbar.press(track(), scrolledFraction(), event.y()))
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (!visible) return false
        val held = scrollbar.drag(track(), event.y()) ?: return super.mouseDragged(event, dragX, dragY)
        scrollTo(held)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        scrollbar.release()
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        visible && super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

    /** Rows run the full width, less a gutter for the scrollbar. */
    override fun getRowWidth(): Int = width - SCROLLBAR_GUTTER

    override fun getRowLeft(): Int = x

    override fun scrollBarX(): Int = right - scrollbarWidth()

    override fun scrollbarWidth(): Int = ContainerScrollbar.WIDTH

    override fun extractScrollbar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (scrollable()) scrollbar.draw(graphics, track(), scrolledFraction())
    }

    private fun track() = Rect(scrollBarX(), y, scrollbarWidth(), height)

    private fun scrolledFraction(): Double = if (maxScrollAmount() > 0) scrollAmount() / maxScrollAmount() else 0.0

    private fun scrollTo(fraction: Double) = setScrollAmount(fraction * maxScrollAmount())

    /** Nothing: a row is acted on, never held selected, and a wash made the grey text on it unreadable. */
    override fun extractSelection(graphics: GuiGraphicsExtractor, entry: ListLine<T>, outlineColor: Int) = Unit

    /** Both suppressed: the panel behind is the background, and there is nothing to separate it from. */
    override fun extractListBackground(graphics: GuiGraphicsExtractor) = Unit

    override fun extractListSeparators(graphics: GuiGraphicsExtractor) = Unit

    private companion object {
        /** A heading's line, a little taller than a row's so a run reads as beginning under it. */
        const val HEADING_HEIGHT = LabelledRow.FIRST_LINE_HEIGHT + 2

        /** The scrollbar, and two pixels clear of it. */
        const val SCROLLBAR_GUTTER = ContainerScrollbar.WIDTH + 2
    }
}
