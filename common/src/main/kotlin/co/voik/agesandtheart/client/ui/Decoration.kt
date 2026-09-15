package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Something drawn into a rectangle — a panel, a recess, a wash of colour.
 *
 * Knows a rectangle and a colour scheme, never what occupies it. Put one behind a child with
 * [DecoratedBox], or on its own with [DecorationWidget].
 */
fun interface Decoration {
    fun draw(graphics: GuiGraphicsExtractor, at: Rect)
}
