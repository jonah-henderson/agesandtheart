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
 * The script is scaled to fit rather than drawn at a fixed size. Word lengths run from three glyphs to
 * fifteen, and a fixed scale either overflows the long ones or wastes the cell on the short ones.
 *
 * A null script leaves the parchment blank, which is what the page reads as while the script is switched
 * off — the slot's gutter is carrying the meaning either way.
 */
class PageWidget(private val script: Component?) {

    fun draw(graphics: GuiGraphicsExtractor, at: Rect, dimmed: Boolean = false) {
        ParchmentSurface.draw(graphics, at)
        val writing = script ?: return
        val font = Minecraft.getInstance().font
        val ordered = writing.visualOrderText
        val drawn = font.width(ordered).toFloat()
        if (drawn <= 0f) return

        val room = (at.width - MARGIN * 2).toFloat()
        val scale = minOf(MAX_SCALE, room / drawn)

        // Drawn at the origin and moved by the transform: text is placed by its top-left, and a scaled
        // coordinate would put the glyphs somewhere other than where the cell is.
        graphics.pose().pushMatrix()
        graphics.pose().translate(at.x + at.width / 2f, at.y + at.height / 2f)
        graphics.pose().scale(scale, scale)
        graphics.text(
            font, ordered,
            -font.width(ordered) / 2, -font.lineHeight / 2,
            if (dimmed) ParchmentSurface.EDGE else ParchmentSurface.INK,
            false,
        )
        graphics.pose().popMatrix()
    }

    private companion object {
        /** Clear space either side of the script, so it never touches the edge. */
        const val MARGIN = 4

        /** As large as a short word may go; longer ones shrink to fit. */
        const val MAX_SCALE = 2.0f
    }
}
