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
 * The parts run on and wrap only when the next will not fit. No parts at all leaves the parchment blank.
 */
class PageWidget(private val parts: List<Component>) {

    fun draw(graphics: GuiGraphicsExtractor, at: Rect, dimmed: Boolean = false) {
        ParchmentSurface.draw(graphics, at)
        if (parts.isEmpty()) return

        val font = Minecraft.getInstance().font
        val ordered = wrapped(font, at).map { it.visualOrderText }
        val widest = ordered.maxOf(font::width)
        if (widest <= 0) return
        val step = font.lineHeight + LINE_GAP
        val stacked = ordered.size * step - LINE_GAP

        // One size for every page; the fit is a ceiling so an over-long part still shrinks to its cell.
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
                -stacked / 2 + index * step,
                colour,
                false,
            )
        }
        graphics.pose().popMatrix()
    }

    /**
     * The parts packed into lines that fit [at] at [SCRIPT_SCALE].
     *
     * Measured in unscaled units, since that is what the font reports and the scale is applied afterwards.
     */
    private fun wrapped(font: net.minecraft.client.gui.Font, at: Rect): List<Component> {
        val room = (at.width - MARGIN * 2) / SCRIPT_SCALE
        val lines = mutableListOf<Component>()
        var line: Component? = null
        parts.forEach { part ->
            val joined = line?.copy()?.append(" ")?.append(part)
            if (joined != null && font.width(joined) <= room) {
                line = joined
            } else {
                line?.let(lines::add)
                line = part
            }
        }
        line?.let(lines::add)
        return lines
    }

    private companion object {
        /** Clear space around the script, so it never touches the edge. */
        const val MARGIN = 4

        /** Breathing room between stacked lines. */
        const val LINE_GAP = 2

        /** The size the script is written at, everywhere. */
        const val SCRIPT_SCALE = 0.61f
    }
}
