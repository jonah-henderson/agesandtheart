package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.layouts.AbstractLayout
import net.minecraft.client.gui.layouts.LayoutElement
import java.util.function.Consumer

/**
 * A surface with something on it: vanilla's layouts arrange, this decorates.
 *
 * Compose it like any other layout — give it a child (usually a `LinearLayout` or `GridLayout`) and it
 * sizes itself around it, or fix its size with [sized] and the child is placed inside the padding.
 *
 * **The surface is visited before the child.** Widgets render in the order they were added, so a screen
 * calling `visitWidgets(::addRenderableWidget)` gets the decoration behind its contents.
 */
class DecoratedBox(
    decoration: Decoration,
    private val padding: Insets = Insets.NONE,
) : AbstractLayout(0, 0, 0, 0) {

    private val surface = DecorationWidget(decoration)
    private var child: LayoutElement? = null
    private var sizedToChild = true

    /** Sets the child, and returns it so the caller keeps the typed reference. */
    fun <T : LayoutElement> holding(element: T): T {
        child = element
        return element
    }

    /** Fixes the size instead of taking it from the child. */
    fun sized(width: Int, height: Int): DecoratedBox {
        this.width = width
        this.height = height
        sizedToChild = false
        return this
    }

    /** Surface first, so it renders behind whatever it decorates. */
    override fun visitChildren(visitor: Consumer<LayoutElement>) {
        visitor.accept(surface)
        child?.let(visitor::accept)
    }

    /** The surface stays: it is what this box *is*, where the child is what it was given to hold. */
    override fun removeChildren() {
        child = null
    }

    override fun arrangeElements() {
        // Nested layouts settle their own contents first, so a child's size is final before it is read.
        super.arrangeElements()
        val inner = child
        if (sizedToChild) {
            width = (inner?.width ?: 0) + padding.left + padding.right
            height = (inner?.height ?: 0) + padding.top + padding.bottom
        }
        surface.setPosition(x, y)
        surface.setSize(width, height)
        inner?.setPosition(x + padding.left, y + padding.top)
    }
}
