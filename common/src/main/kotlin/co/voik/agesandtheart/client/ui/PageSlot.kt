package co.voik.agesandtheart.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * One place a page can be: the cell that holds it, and the gutter beneath that says what it means.
 *
 * The cell shows the word as it is *written*, the gutter as it is *read*.
 *
 * Drawn on demand rather than being a widget: a work surface owns a shifting number of them behind a
 * scissor and positions them itself.
 */
class PageSlot(val cell: Rect, val gutter: Rect) {

    /** The whole slot, cell and gutter together. */
    val bounds = Rect(cell.x, cell.y, cell.width, gutter.bottom - cell.y)

    fun drawEmpty(graphics: GuiGraphicsExtractor) {
        graphics.fill(cell.x, cell.y, cell.right, cell.bottom, Palette.WELL_EDGE)
        drawGutterRule(graphics)
    }

    fun draw(graphics: GuiGraphicsExtractor, page: PageWidget, translation: String, dimmed: Boolean = false) {
        page.draw(graphics, cell, dimmed)
        drawGutterRule(graphics)
        val font = Minecraft.getInstance().font
        val fits = font.plainSubstrByWidth(translation, gutter.width - TEXT_PAD * 2)
        val shown = if (fits.length < translation.length) {
            font.plainSubstrByWidth(translation, gutter.width - TEXT_PAD * 2 - font.width(ELLIPSIS)) + ELLIPSIS
        } else {
            translation
        }
        graphics.text(
            font, shown,
            gutter.x + (gutter.width - font.width(shown)) / 2,
            gutter.y + (gutter.height - font.lineHeight) / 2,
            Palette.HIGHLIGHT,
            false,
        )
    }

    /**
     * The gutter's own ground, and the lighter rules that bound it.
     *
     * One line between cell and gutter, one down the right edge and one along the bottom: without them a
     * surface of same-coloured cells gives the eye nothing to line up on, and a wrapped row stops reading
     * as rows.
     */
    private fun drawGutterRule(graphics: GuiGraphicsExtractor) {
        graphics.fill(gutter.x, gutter.y, gutter.right, gutter.bottom, Palette.WELL_EDGE)
        graphics.fill(gutter.x, gutter.y, gutter.right, gutter.y + 1, Palette.SHADOW)
        graphics.fill(bounds.right - 1, bounds.y, bounds.right, bounds.bottom, Palette.SHADOW)
        graphics.fill(bounds.x, bounds.bottom - 1, bounds.right, bounds.bottom, Palette.SHADOW)
    }

    /** Whether the full name had to be cut, so the caller knows a tooltip is worth offering. */
    fun truncates(translation: String): Boolean =
        Minecraft.getInstance().font.width(translation) > gutter.width - TEXT_PAD * 2

    private companion object {
        const val TEXT_PAD = 2
        const val ELLIPSIS = "…"
    }
}
