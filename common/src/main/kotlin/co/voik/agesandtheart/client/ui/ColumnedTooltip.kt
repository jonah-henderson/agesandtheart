package co.voik.agesandtheart.client.ui

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.tooltip.TooltipComponent

/**
 * A tooltip of many short entries under headings, laid out to fit the screen whatever their number (Jonah,
 * 2026-11-20): a few [MOST_PER_LINE] to a line, so no line runs off the side, and the lines flowing into columns side
 * by side where there are too many for the screen's height, so none runs off the bottom. One column is simply the
 * case where they fit, drawn the same way.
 *
 * [title] spans the top and [footer] the bottom; each of [sections] is a heading, where it has one, over its entries
 * in [entryColour], and a heading is never left at the foot of a column without a line of its own beneath it.
 */
data class ColumnedTooltip(
    val title: List<Component>,
    val sections: List<Section>,
    val footer: List<Component> = emptyList(),
    val entryColour: ChatFormatting = ChatFormatting.BLUE,
) : TooltipComponent {

    data class Section(val heading: Component?, val entries: List<String>)
}

/** Draws a [ColumnedTooltip], laid out once for the screen it is shown on. */
class ClientColumnedTooltip(private val tooltip: ColumnedTooltip) : ClientTooltipComponent {

    /** One line in a column: a heading, or entries joined, indented under a heading. */
    private data class Line(val text: Component, val indented: Boolean, val isAHeading: Boolean = false)

    private class Layout(val columns: List<List<Line>>, val columnWidths: List<Int>, val width: Int, val height: Int)

    private var laid: Layout? = null

    private fun layout(font: Font): Layout = laid ?: layOut(font).also { laid = it }

    override fun getWidth(font: Font): Int = layout(font).width

    override fun getHeight(font: Font): Int = layout(font).height

    override fun extractText(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val layout = layout(font)
        var top = y
        for (line in tooltip.title) {
            graphics.text(font, line, x, top, WHITE, true)
            top += LINE_HEIGHT
        }
        var left = x
        for ((index, column) in layout.columns.withIndex()) {
            column.forEachIndexed { row, line ->
                val indent = if (line.indented) font.width(" ") else 0
                graphics.text(font, line.text, left + indent, top + row * LINE_HEIGHT, WHITE, true)
            }
            left += layout.columnWidths[index] + COLUMN_GAP
        }
        top += (layout.columns.maxOfOrNull { it.size } ?: 0) * LINE_HEIGHT
        for (line in tooltip.footer) {
            graphics.text(font, line, x, top, WHITE, true)
            top += LINE_HEIGHT
        }
    }

    /**
     * The fewest columns that fit the screen's height, with up to [MOST_PER_LINE] entries a line — fewer a line where
     * that is still wider than the screen, down to one.
     */
    private fun layOut(font: Font): Layout {
        val window = Minecraft.getInstance().window
        val roomAcross = window.guiScaledWidth - SCREEN_MARGIN * 2
        val roomDown = window.guiScaledHeight - SCREEN_MARGIN * 2 - (tooltip.title.size + tooltip.footer.size) * LINE_HEIGHT
        val rowsPerColumn = (roomDown / LINE_HEIGHT).coerceAtLeast(FEWEST_ROWS)
        var tried: Layout? = null
        for (perLine in MOST_PER_LINE downTo 1) {
            tried = columnsOf(lines(perLine), rowsPerColumn, font)
            if (tried.width <= roomAcross) return tried
        }
        return requireNotNull(tried)
    }

    /** Every section as lines: its heading, then its entries [perLine] to a line. */
    private fun lines(perLine: Int): List<Line> = tooltip.sections.flatMap { section ->
        val heading = section.heading
            ?.let { listOf(Line(it.copy().withStyle(ChatFormatting.GRAY), indented = false, isAHeading = true)) }
            .orEmpty()
        val entries = section.entries.chunked(perLine).map { chunk ->
            Line(Component.literal(chunk.joinToString(SEPARATOR)).withStyle(tooltip.entryColour), indented = section.heading != null)
        }
        heading + entries
    }

    /** [lines] down columns of [rows], a heading carried to the next column rather than left last in one. */
    private fun columnsOf(lines: List<Line>, rows: Int, font: Font): Layout {
        val columns = mutableListOf<MutableList<Line>>(mutableListOf())
        for (line in lines) {
            val column = columns.last()
            val roomLeft = rows - column.size
            val needs = if (line.isAHeading) 2 else 1
            if (column.isNotEmpty() && roomLeft < needs) columns += mutableListOf<Line>()
            columns.last() += line
        }
        val widths = columns.map { column -> column.maxOfOrNull { lineWidth(font, it) } ?: 0 }
        val across = widths.sum() + COLUMN_GAP * (widths.size - 1).coerceAtLeast(0)
        val ends = (tooltip.title + tooltip.footer).maxOfOrNull(font::width) ?: 0
        val down = (tooltip.title.size + tooltip.footer.size + (columns.maxOfOrNull { it.size } ?: 0)) * LINE_HEIGHT
        return Layout(columns, widths, maxOf(across, ends), down)
    }

    private fun lineWidth(font: Font, line: Line): Int = font.width(line.text) + if (line.indented) font.width(" ") else 0

    private companion object {
        /** Vanilla's text tooltip line, so this sits beside one without a seam. */
        const val LINE_HEIGHT = 10

        /** How many entries go on a line at most, so a list of forty is a block rather than one line off the screen. */
        const val MOST_PER_LINE = 5

        const val COLUMN_GAP = 12

        /** What the tooltip's frame and the positioner's own clearance take at each edge. */
        const val SCREEN_MARGIN = 8

        /** A column of at least this many however short the screen, so a tiny window still reads as columns. */
        const val FEWEST_ROWS = 4

        const val SEPARATOR = ", "
        const val WHITE = -1
    }
}
