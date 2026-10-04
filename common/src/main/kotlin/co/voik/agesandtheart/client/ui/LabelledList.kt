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

/**
 * One row of a [LabelledList]: a name, optionally a count, and whatever the row offers to do.
 *
 * A name too long for the row wraps onto indented lines beneath it; the count and the buttons stay on the
 * first.
 *
 * Kept out of the list class so the self-referential bound `Entry<E>` resolves — an inner class cannot name
 * itself in its own supertype.
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
) : ObjectSelectionList.Entry<LabelledRow<T>>() {

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
        val washed = hovered || isFocused
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
            // Faint grey on the selection wash is close to unreadable, so a washed row states its count
            // in the same ink as its name.
            graphics.text(
                font, shown,
                countRight() - font.width(shown), baseline,
                if (washed) Palette.TEXT else Palette.FAINT,
                false,
            )
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
) : ObjectSelectionList<LabelledRow<T>>(minecraft, bounds.width, bounds.height, bounds.y, LabelledRow.FIRST_LINE_HEIGHT),
    Resized {

    /** The last [show], kept so a new width can wrap the same rows again. */
    private var reshow: () -> Unit = {}

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
    ) {
        reshow = { show(values, label, count, key, actions, tooltip) }
        val wasSelected = selected?.value?.let(key)
        val wasScrolledTo = scrollAmount()
        clearEntries()
        for (value in values) {
            val row = LabelledRow.wrapped(value, label(value), count(value), onSelect, actions, rowWidth, tooltip)
            addEntry(row, LabelledRow.heightOf(row))
        }
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
        const val SCROLLBAR_GUTTER = 8
    }
}
