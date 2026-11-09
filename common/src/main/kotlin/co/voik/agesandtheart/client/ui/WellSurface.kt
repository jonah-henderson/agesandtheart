package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A slot's recess at any size — a pixel of well edge above and left, a pixel of white below and right, the well
 * between — for a slot larger than vanilla's 18-pixel sprite, which stretches its borders rather than keeping them
 * a pixel wide: the loom's result slot is one.
 */
object WellSurface : Decoration {
    override fun draw(graphics: GuiGraphicsExtractor, at: Rect) {
        graphics.fill(at.x, at.y, at.right, at.bottom, Palette.HIGHLIGHT)
        graphics.fill(at.x, at.y, at.right - 1, at.bottom - 1, Palette.WELL_EDGE)
        graphics.fill(at.x + 1, at.y + 1, at.right - 1, at.bottom - 1, Palette.WELL)
    }
}
