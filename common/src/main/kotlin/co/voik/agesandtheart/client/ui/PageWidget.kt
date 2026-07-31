package co.voik.agesandtheart.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * A written page, small and inert.
 *
 * Parchment with the word on it in the script, sized to whatever cell it is given. Nothing here reacts to
 * anything — a page is an object that a work surface moves around, and drawing is all it does. Nor is it a
 * widget: a work surface owns many of these and scrolls them behind a scissor, so they are drawn on demand
 * at a rectangle rather than registered and positioned individually.
 *
 * **One line per part**, because the alternative is scaling a long word until it is a smudge: a derived
 * word is a block id, and `polished_deepslate` set on one line is a third the size of `sea`. Stacking the
 * parts spends the cell's height, which is otherwise mostly empty, and keeps both readable.
 *
 * No lines at all leaves the parchment blank.
 */
class PageWidget(private val lines: List<Component>) {

    fun draw(graphics: GuiGraphicsExtractor, at: Rect, dimmed: Boolean = false) {
        ParchmentSurface.draw(graphics, at)
        if (lines.isEmpty()) return

        val font = Minecraft.getInstance().font
        val ordered = lines.map { it.visualOrderText }
        val widest = ordered.maxOf(font::width)
        if (widest <= 0) return
        val stacked = ordered.size * font.lineHeight

        // One size for every page, not one that fits each. Scaling each word to its cell made a short word
        // three times the size of a long one, and a row of pages at three sizes reads as a mistake. The
        // fit is a *ceiling*, so an unusually long part still shrinks rather than running over the edge.
        val scale = minOf(
            SCRIPT_SCALE,
            (at.width - MARGIN * 2).toFloat() / widest,
            (at.height - MARGIN * 2).toFloat() / stacked,
        )

        // Drawn about the origin and moved by the transform: text is placed by its top-left, and a scaled
        // coordinate would put the glyphs somewhere other than where the cell is.
        graphics.pose().pushMatrix()
        graphics.pose().translate(at.x + at.width / 2f, at.y + at.height / 2f)
        graphics.pose().scale(scale, scale)
        val colour = if (dimmed) ParchmentSurface.EDGE else ParchmentSurface.INK
        ordered.forEachIndexed { index, line ->
            graphics.text(
                font, line,
                -font.width(line) / 2,
                -stacked / 2 + index * font.lineHeight,
                colour,
                false,
            )
        }
        graphics.pose().popMatrix()
    }

    private companion object {
        /** Clear space around the script, so it never touches the edge. */
        const val MARGIN = 4

        /**
         * The size the script is written at, everywhere.
         *
         * Taken from what a ten-glyph word needed to fit a cell — the size the majority were landing at
         * anyway — so uniformity costs the short words their bulk rather than costing the long ones
         * legibility.
         */
        const val SCRIPT_SCALE = 0.61f
    }
}
