package co.voik.agesandtheart.client.ui

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.components.AbstractTextAreaWidget
import net.minecraft.client.gui.components.MultilineTextField
import net.minecraft.client.gui.components.TextCursorUtils
import net.minecraft.client.gui.components.Whence
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Vanilla's text model, with its selection handed out as a plain index range.
 *
 * Its `StringView` is a protected record, so only a subclass can read one; this is that subclass and does
 * nothing else.
 */
private class SpannedField(font: Font) : MultilineTextField(font, UNWRAPPED) {
    fun selection(): IntRange = getSelected().let { it.beginIndex() until it.endIndex() }

    private companion object {
        /** The editor wraps its own rows, so the field is told never to. */
        const val UNWRAPPED = Int.MAX_VALUE / 2
    }
}

/**
 * Free text on two lines per row: what was typed, and beneath it the script [marks] sets there.
 *
 * **Vanilla does the editing and the editor does the layout.** `MultilineTextField` is the whole of what a
 * book-and-quill's text box knows — cursor, selection, clipboard, word-wise movement — but it wraps
 * privately and by the width of the typed glyphs alone. Here a word whose script is wider than it is
 * widened to match, so the two lines stay in step; the editor wraps its own rows around that, and takes
 * back everything that depends on them: drawing, clicks, and the up, down, home and end keys.
 */
class TemplateEditor(
    private val font: Font,
    width: Int,
    height: Int,
    private val placeholder: Component,
    private val marks: () -> MarkedText?,
) : AbstractTextAreaWidget(0, 0, width, height, Component.empty(), AbstractScrollArea.defaultSettings(ROW / 2)),
    Resized {

    private val text = SpannedField(font)
    private var focusedAt = Util.getMillis()

    /** The last reading, kept so an edit can carry it until the desk reads again. */
    private var lastReading: MarkedText? = null

    private var laidOut: Layout? = null

    private data class Layout(val typed: String, val pads: Map<Int, Int>, val limit: Int, val rows: List<DisplayRow>)

    init {
        text.setCursorListener(::scrollToCursor)
    }

    var value: String
        get() = text.value()
        set(replacement) {
            text.setValue(replacement)
        }

    fun setCharacterLimit(limit: Int) = text.setCharacterLimit(limit)

    fun onChange(listener: (String) -> Unit) = text.setValueListener(listener)

    /**
     * Puts [word] at the cursor as a word of its own, with a space either side where the neighbours would
     * otherwise run into it.
     */
    fun insertWord(word: String) {
        val before = value.getOrNull(text.cursor() - 1)
        val after = value.getOrNull(text.cursor())
        val leading = if (before != null && !before.isWhitespace()) " " else ""
        val trailing = if (after == null || !after.isWhitespace()) " " else ""
        text.insertText(leading + word + trailing)
    }

    override fun onResized(bounds: Rect) {
        setPosition(bounds.x, bounds.y)
        setSize(bounds.width, bounds.height)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor) =
        ParchmentSurface.draw(graphics, Rect(x, y, width, height))

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        if (doubleClick) {
            text.selectWordAtCursor()
        } else {
            text.setSelecting(event.hasShiftDown())
            text.seekCursor(Whence.ABSOLUTE, indexAt(event.x(), event.y()))
        }
    }

    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        text.setSelecting(true)
        text.seekCursor(Whence.ABSOLUTE, indexAt(event.x(), event.y()))
        text.setSelecting(event.hasShiftDown())
    }

    /** The keys that move by row are the editor's, since the rows are; everything else is the field's. */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.hasControlDownWithQuirk()) return text.keyPressed(event)
        val layout = layout()
        val row = rowOf(layout, text.cursor())
        val target = when (event.key()) {
            InputConstants.KEY_UP -> layout.rows.getOrNull(row - 1)?.let { nearest(layout, it, caretX(layout, row, text.cursor())) }
            InputConstants.KEY_DOWN -> layout.rows.getOrNull(row + 1)?.let { nearest(layout, it, caretX(layout, row, text.cursor())) }
            InputConstants.KEY_HOME -> layout.rows[row].begin
            InputConstants.KEY_END -> layout.rows[row].end
            else -> return text.keyPressed(event)
        }
        text.setSelecting(event.hasShiftDown())
        target?.let { text.seekCursor(Whence.ABSOLUTE, it) }
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!visible || !isFocused || !event.isAllowedChatCharacter) return false
        text.insertText(event.codepointAsString())
        return true
    }

    override fun setFocused(focused: Boolean) {
        super.setFocused(focused)
        if (focused) focusedAt = Util.getMillis()
        Minecraft.getInstance().onTextInputFocusChange(this, focused)
    }

    override fun getInnerHeight(): Int = ROW * layout().rows.size

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.editBox", placeholder, value))
    }

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val typed = value
        if (typed.isEmpty() && !isFocused) {
            graphics.textWithWordWrap(font, placeholder, innerLeft, innerTop, width - totalInnerPadding(), ParchmentSurface.FAINT_INK)
            return
        }
        val shown = currentMarks()
        val layout = layout(shown)
        val pointerY = mouseY + scrollAmount().toInt()
        layout.rows.forEachIndexed { row, line ->
            val top = innerTop + row * ROW
            if (!withinContentAreaTopBottom(top, top + ROW)) return@forEachIndexed
            extractRow(graphics, layout, row, top, shown)
            hoveredMark(layout, row, top, shown, mouseX, pointerY)
                ?.tooltip?.let { graphics.setTooltipForNextFrame(it, mouseX, mouseY) }
        }
        extractSelection(graphics, layout)
        extractCursor(graphics, layout)
    }

    /** The reading of the text now in the box: the latest one, carried through whatever was typed since. */
    private fun currentMarks(): List<TextMark> {
        marks()?.let { lastReading = it }
        val reading = lastReading ?: return emptyList()
        return carriedThrough(reading.marks, reading.text, value)
    }

    /** One row: the typed text in segments coloured by the marks over them, then each mark's script beneath. */
    private fun extractRow(graphics: GuiGraphicsExtractor, layout: Layout, row: Int, top: Int, shown: List<TextMark>) {
        val line = layout.rows[row]
        val cuts = (listOf(line.begin, line.end) + shown.flatMap { listOf(it.start, it.end) })
            .filter { it in line.begin..line.end }
            .distinct()
            .sorted()
        for ((from, to) in cuts.zipWithNext()) {
            val over = shown.firstOrNull { it.start <= from && to <= it.end }
            val colour = over?.colour ?: ParchmentSurface.INK
            graphics.text(font, layout.typed.substring(from, to), glyphX(layout, row, from), top, colour, false)
        }
        for (mark in shown) {
            val from = maxOf(mark.start, line.begin)
            val to = minOf(mark.end, line.end)
            if (from >= to) continue
            val left = glyphX(layout, row, from)
            val right = caretX(layout, row, to)
            mark.underline?.let { graphics.fill(left, top + font.lineHeight, right, top + font.lineHeight + 1, it) }
            // The script goes under the span's start only, so a word wrapped across two rows is spelled once.
            if (mark.start >= line.begin) scriptFor(layout, mark)?.let { extractScript(graphics, it, left, top) }
        }
    }

    private fun extractScript(graphics: GuiGraphicsExtractor, script: Component, left: Int, top: Int) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(left.toFloat(), (top + font.lineHeight + SCRIPT_GAP).toFloat())
        graphics.pose().scale(SCRIPT_SCALE, SCRIPT_SCALE)
        graphics.text(font, script, 0, 0, ParchmentSurface.FAINT_INK, false)
        graphics.pose().popMatrix()
    }

    /** What goes under [mark]: its script, or for a word not yet learned, `--- ??? ---` as wide as the word. */
    private fun scriptFor(layout: Layout, mark: TextMark): Component? {
        if (!mark.scriptUnknown) return mark.script
        val room = font.width(layout.typed.substring(mark.start, mark.end)) / SCRIPT_SCALE
        val core = font.width(UNKNOWN_CORE) + 2 * font.width(" ")
        val dashesEachSide = ((room - core) / (2 * font.width(UNKNOWN_DASH))).toInt()
        if (dashesEachSide <= 0) return Component.literal(UNKNOWN_CORE)
        val side = UNKNOWN_DASH.repeat(dashesEachSide)
        return Component.literal("$side $UNKNOWN_CORE $side")
    }

    private fun hoveredMark(
        layout: Layout,
        row: Int,
        top: Int,
        shown: List<TextMark>,
        mouseX: Int,
        pointerY: Int,
    ): TextMark? {
        if (pointerY < top || pointerY >= top + ROW) return null
        val line = layout.rows[row]
        return shown.firstOrNull { mark ->
            val from = maxOf(mark.start, line.begin)
            val to = minOf(mark.end, line.end)
            from < to && mouseX >= glyphX(layout, row, from) && mouseX < caretX(layout, row, to)
        }
    }

    private fun extractSelection(graphics: GuiGraphicsExtractor, layout: Layout) {
        if (!text.hasSelection()) return
        val selection = text.selection()
        layout.rows.forEachIndexed { row, line ->
            val top = innerTop + row * ROW
            val from = maxOf(selection.first, line.begin)
            val to = minOf(selection.last + 1, line.end)
            if (from < to && withinContentAreaTopBottom(top, top + ROW)) {
                graphics.textHighlight(caretX(layout, row, from), top, caretX(layout, row, to), top + font.lineHeight, true)
            }
        }
    }

    private fun extractCursor(graphics: GuiGraphicsExtractor, layout: Layout) {
        val blinkingOn = isFocused && TextCursorUtils.isCursorVisible(Util.getMillis() - focusedAt)
        if (!blinkingOn) return
        val row = rowOf(layout, text.cursor())
        TextCursorUtils.extractInsertCursor(
            graphics, caretX(layout, row, text.cursor()), innerTop + row * ROW, ParchmentSurface.INK, font.lineHeight + 1,
        )
    }

    /** The rows for the text now in the box, recomputed only when it, its padding or the width changes. */
    private fun layout(shown: List<TextMark> = currentMarks()): Layout {
        val typed = value
        val pads = padsFor(typed, shown)
        val limit = width - totalInnerPadding()
        laidOut?.let { if (it.typed == typed && it.pads == pads && it.limit == limit) return it }
        val rows = wrapRows(typed, limit) { index -> font.width(typed[index].toString()) + (pads[index + 1] ?: 0) }
        return Layout(typed, pads, limit, rows).also { laidOut = it }
    }

    /** How much wider each marked word must be to sit over its script, keyed by where the word ends. */
    private fun padsFor(typed: String, shown: List<TextMark>): Map<Int, Int> =
        shown.mapNotNull { mark ->
            val script = mark.script ?: return@mapNotNull null
            val scriptWidth = ceil(font.width(script) * SCRIPT_SCALE).toInt()
            val wordWidth = font.width(typed.substring(mark.start, mark.end))
            (scriptWidth - wordWidth).takeIf { it > 0 }?.let { mark.end to it }
        }.toMap()

    /** Where the glyph at [index] is drawn, after any padding that ends there. */
    private fun glyphX(layout: Layout, row: Int, index: Int): Int {
        val begin = layout.rows[row].begin
        val padding = layout.pads.entries.sumOf { (end, pad) -> if (end > begin && end <= index) pad else 0 }
        return innerLeft + font.width(layout.typed.substring(begin, index)) + padding
    }

    /** Where a caret at [index] stands: against the glyph before it, not after that word's padding. */
    private fun caretX(layout: Layout, row: Int, index: Int): Int {
        val padEndingHere = if (index > layout.rows[row].begin) layout.pads[index] ?: 0 else 0
        return glyphX(layout, row, index) - padEndingHere
    }

    private fun rowOf(layout: Layout, index: Int): Int =
        layout.rows.indexOfFirst { index in it.begin..it.end }.takeIf { it >= 0 } ?: layout.rows.lastIndex

    /** The index on [line] whose caret stands closest to [screenX]. */
    private fun nearest(layout: Layout, line: DisplayRow, screenX: Int): Int {
        val row = layout.rows.indexOf(line)
        return (line.begin..line.end).minBy { abs(caretX(layout, row, it) - screenX) }
    }

    private fun indexAt(screenX: Double, screenY: Double): Int {
        val layout = layout()
        val inside = screenY - y - innerPadding() + scrollAmount()
        val row = (inside / ROW).toInt().coerceIn(0, layout.rows.lastIndex)
        return nearest(layout, layout.rows[row], screenX.toInt())
    }

    private fun scrollToCursor() {
        val cursorTop = rowOf(layout(), text.cursor()) * ROW
        val above = cursorTop < scrollAmount()
        val below = cursorTop + ROW > scrollAmount() + height - totalInnerPadding()
        when {
            above -> setScrollAmount(cursorTop.toDouble())
            below -> setScrollAmount((cursorTop + ROW - height + totalInnerPadding()).toDouble())
        }
    }

    private companion object {
        /** A line of text, a line of script, and a hair between rows. */
        const val ROW = 19
        const val SCRIPT_GAP = 2

        /**
         * The script's typeface drawn at this size has glyphs about as wide and tall as the text's own —
         * which is as near as two unrelated alphabets come to lining up.
         */
        const val SCRIPT_SCALE = 0.7f

        const val UNKNOWN_CORE = "???"
        const val UNKNOWN_DASH = "-"
    }
}
