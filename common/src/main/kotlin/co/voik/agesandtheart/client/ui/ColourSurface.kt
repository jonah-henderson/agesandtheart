package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Flat colour.
 *
 * Small but load-bearing: it is what paints out an abutting panel's border so two panels read as one room,
 * and what a selection or hover wash is made of.
 */
class ColourSurface(private val colour: Int) : Decoration {
    override fun draw(graphics: GuiGraphicsExtractor, at: Rect) {
        graphics.fill(at.x, at.y, at.right, at.bottom, colour)
    }

    companion object {
        val PANEL = ColourSurface(Palette.PANEL)
    }
}
