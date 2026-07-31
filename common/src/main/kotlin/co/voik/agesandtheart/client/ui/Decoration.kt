package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Something drawn into a rectangle — a panel, a recess, a wash of colour.
 *
 * This is the one concept vanilla's GUI does not have. Its layouts arrange elements and its widgets draw
 * themselves, but nothing describes *what is behind a thing*, so every screen re-implements its own
 * background inline. A decoration is that missing half, and it is deliberately ignorant: it knows a
 * rectangle and a colour scheme, never what occupies it.
 *
 * Put one behind a child with [DecoratedBox], or on its own with [DecorationWidget].
 */
fun interface Decoration {
    fun draw(graphics: GuiGraphicsExtractor, at: Rect)

    /** This decoration, then [next] drawn over it. */
    fun then(next: Decoration): Decoration = Decoration { graphics, at ->
        this.draw(graphics, at)
        next.draw(graphics, at)
    }
}
