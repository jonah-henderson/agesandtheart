package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.layouts.AbstractLayout
import net.minecraft.client.gui.layouts.LayoutElement
import java.util.function.Consumer

/**
 * A vertical stack of a known height, where some children take whatever the others leave.
 *
 * Children are laid out top to bottom, full width unless they are narrower and aligned. Filling children
 * split the leftover height evenly. Vanilla's own layouts size themselves to their contents and have no
 * equivalent.
 */
class FlexColumn(width: Int, height: Int) : AbstractLayout(0, 0, width, height) {

    private class Cell(
        val child: LayoutElement,
        val fills: Boolean,
        val alignment: Float,
        val forcedHeight: Int? = null,
    )

    private val cells = mutableListOf<Cell>()

    /**
     * Keeps its own height, or [height] if the column is telling it one.
     *
     * A height is worth giving where the same widget appears on more than one screen at different sizes —
     * without it the column would read whatever the last arrangement happened to leave.
     *
     * [alignment] is 0 for left, 0.5 centred, 1 right.
     */
    fun <T : LayoutElement> add(child: T, height: Int? = null, alignment: Float = LEFT): T {
        cells += Cell(child, fills = false, alignment = alignment, forcedHeight = height)
        return child
    }

    /** Takes a share of whatever height the fixed children leave. */
    fun <T : AbstractWidget> fill(child: T): T {
        cells += Cell(child, fills = true, alignment = LEFT)
        return child
    }

    fun gap(amount: Int) {
        cells += Cell(Gap(amount), fills = false, alignment = LEFT)
    }

    override fun visitChildren(visitor: Consumer<LayoutElement>) = cells.forEach { visitor.accept(it.child) }

    override fun arrangeElements() {
        // Nested layouts settle first, so a child's height is final before it is counted.
        super.arrangeElements()

        val fixed = cells.filterNot { it.fills }.sumOf { it.forcedHeight ?: it.child.height }
        val stretching = cells.count { it.fills }
        val leftover = (height - fixed).coerceAtLeast(0)
        val share = if (stretching == 0) 0 else leftover / stretching

        var top = y
        cells.forEach { cell ->
            val sized = cell.fills || cell.forcedHeight != null
            val childHeight = if (cell.fills) share else cell.forcedHeight ?: cell.child.height
            val childWidth = if (sized) width else cell.child.width
            val left = x + ((width - childWidth) * cell.alignment).toInt()

            cell.child.setPosition(left, top)
            if (sized && cell.child is AbstractWidget) cell.child.setSize(childWidth, childHeight)
            // Anything holding arranged contents of its own has to hear about it; setSize only stores.
            (cell.child as? Resized)?.onResized(Rect(left, top, childWidth, childHeight))

            top += childHeight
        }
    }

    /** Empty space. `SpacerElement` would do, but this keeps a gap from reading as a child. */
    private class Gap(private val amount: Int) : LayoutElement {
        private var atX = 0
        private var atY = 0

        override fun setX(x: Int) { atX = x }
        override fun setY(y: Int) { atY = y }
        override fun getX(): Int = atX
        override fun getY(): Int = atY
        override fun getWidth(): Int = 0
        override fun getHeight(): Int = amount
        override fun visitWidgets(visitor: Consumer<AbstractWidget>) = Unit
    }

    companion object {
        const val LEFT = 0f
        const val CENTRE = 0.5f
        const val RIGHT = 1f
    }
}
