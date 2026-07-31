package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Vanilla's raised panel, drawn rather than blitted: a pixel of black, two of white above and left, two of
 * grey below and right, `C6` between.
 *
 * Drawing it rather than using `generic_54.png` means a panel can be any size, and that nothing has to be
 * painted back out afterwards — the texture carries a slot grid we would only have to hide.
 *
 * An [openOn] edge keeps its fills running to the rectangle's limit instead of stopping short for a border,
 * so a panel abutting another reads as one shape rather than growing a seam.
 */
class PanelSurface(private val openOn: Edge? = null) : Decoration {

    override fun draw(graphics: GuiGraphicsExtractor, at: Rect) {
        fun border(side: Edge, amount: Int) = if (openOn == side) 0 else amount

        val outlineLeft = border(Edge.LEFT, 1)
        val outlineTop = border(Edge.TOP, 1)
        val outlineRight = border(Edge.RIGHT, 1)
        val outlineBottom = border(Edge.BOTTOM, 1)
        val bevelLeft = border(Edge.LEFT, Palette.BORDER)
        val bevelTop = border(Edge.TOP, Palette.BORDER)
        val bevelRight = border(Edge.RIGHT, Palette.BORDER)
        val bevelBottom = border(Edge.BOTTOM, Palette.BORDER)

        graphics.fill(at.x, at.y, at.right, at.bottom, Palette.OUTLINE)
        graphics.fill(
            at.x + outlineLeft, at.y + outlineTop,
            at.right - outlineRight, at.bottom - outlineBottom, Palette.HIGHLIGHT,
        )
        graphics.fill(
            at.x + bevelLeft, at.y + bevelTop,
            at.right - outlineRight, at.bottom - outlineBottom, Palette.SHADOW,
        )
        graphics.fill(
            at.x + bevelLeft, at.y + bevelTop,
            at.right - bevelRight, at.bottom - bevelBottom, Palette.PANEL,
        )
    }

    companion object {
        val RAISED = PanelSurface()
    }
}
