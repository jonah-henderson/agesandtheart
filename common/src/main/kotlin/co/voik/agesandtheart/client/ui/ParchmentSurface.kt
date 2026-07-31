package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Written-on paper: a warm off-white with a darker edge.
 *
 * The cell *is* the parchment; nothing draws a smaller page inside it.
 */
object ParchmentSurface : Decoration {
    val PARCHMENT = 0xFFE9DFC3.toInt()
    val EDGE = 0xFF8B7B55.toInt()
    val INK = 0xFF2B2118.toInt()

    override fun draw(graphics: GuiGraphicsExtractor, at: Rect) {
        graphics.fill(at.x, at.y, at.right, at.bottom, EDGE)
        graphics.fill(at.x + 1, at.y + 1, at.right - 1, at.bottom - 1, PARCHMENT)
    }
}
