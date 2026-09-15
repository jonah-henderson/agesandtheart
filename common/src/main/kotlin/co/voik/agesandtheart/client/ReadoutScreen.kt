package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * An instrument's readout on its own panel: a headline, then a list, scrolling where the window is too
 * short for it.
 *
 * A subclass says what the lines are ([lines]) and how long the list under the headline can run
 * ([mostListedLines]), which is what the panel is sized for.
 */
abstract class ReadoutScreen<M : AbstractContainerMenu>(
    menu: M,
    inventory: Inventory,
    title: Component,
    mostListedLines: Int,
    /** The start of every translation key this screen reads, such as `container.agesandtheart.seismograph`. */
    private val translationPrefix: String,
) : AbstractContainerScreen<M>(menu, inventory, title, WIDTH, tallEnoughFor(mostListedLines)) {

    private lateinit var readout: MultiLineTextWidget
    private lateinit var scrolling: ScrollableLayout

    /** The headline, then one line per item of the list. */
    protected abstract fun lines(): List<Component>

    /**
     * The panel is sized at construction because `imageHeight` is final — see [tallEnoughFor] — so what is
     * left here is fitting the readout inside whatever that came to.
     */
    override fun init() {
        super.init()
        addRenderableWidget(panel())
        readout = MultiLineTextWidget(Component.empty(), font).setMaxWidth(WIDTH - MARGIN * 2)
        // **Vanilla owns the arrangement and the scrolling both.** `ScrollableLayout` is what every dialog
        // that might overflow already uses; it wants a `Layout` rather than a widget, which is the only
        // reason the column exists.
        val column = LinearLayout.vertical()
        column.addChild(readout)
        val room = minOf(imageHeight, height - PADDING * 2) - CONTENT_TOP - MARGIN
        scrolling = ScrollableLayout(minecraft, column, room)
        scrolling.setMinWidth(WIDTH - MARGIN * 2)
        scrolling.setPosition(leftPos + MARGIN, topPos + CONTENT_TOP)
        scrolling.arrangeElements()
        scrolling.visitWidgets(::addRenderableWidget)
    }

    /** Title only: there is no inventory on this screen, so vanilla's second label would name nothing. */
    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, title, titleLabelX, titleLabelY, Palette.TEXT, false)
    }

    override fun containerTick() {
        super.containerTick()
        val said = stacked(lines())
        if (said == readout.message) return
        // Only when it actually changed: laying out again is what makes the scroll fit the new text, and
        // doing it every tick would drag the view back to the top while somebody was reading it.
        readout.message = said
        scrolling.arrangeElements()
    }

    /** The panel behind it all, at whatever size vanilla settled on — never a second opinion about it. */
    private fun panel(): AbstractWidget = DecorationWidget(PanelSurface.RAISED).also {
        it.setPosition(leftPos, topPos)
        it.setSize(imageWidth, imageHeight)
    }

    protected fun translated(suffix: String, vararg arguments: Any): Component =
        Component.translatable("$translationPrefix.$suffix", *arguments)

    /** [lines] as one component, which is what a `MultiLineTextWidget` reads. */
    private fun stacked(lines: List<Component>): Component =
        lines.foldIndexed(Component.empty()) { index, built, line ->
            if (index > 0) built.append(Component.literal("\n"))
            built.append(line)
        }

    private companion object {
        const val WIDTH = 176

        /**
         * **As tall as its own worst case, and never taller than the window will take.**
         *
         * The list will go on growing, and a panel that simply grew with it would one day be taller than
         * the window. So it is capped to the window less a little air above and below, and
         * [ScrollableLayout] carries whatever does not fit.
         *
         * Read at construction because `imageHeight` is `final`: a screen decides its size before vanilla
         * places it, and the window is already there to ask.
         */
        fun tallEnoughFor(mostListedLines: Int): Int =
            worstCase(mostListedLines).coerceAtMost(Minecraft.getInstance().window.guiScaledHeight - PADDING * 2)

        /** Every line it could ever have to show at once, with room for one of them to wrap. */
        fun worstCase(mostListedLines: Int): Int =
            CONTENT_TOP + (mostListedLines + A_HEADLINE + A_SPARE_LINE) * A_LINE + MARGIN * 2

        /** Air above and below, so a capped panel is not pressed against the edges of the window. */
        const val PADDING = 20

        const val MARGIN = 8

        /** Vanilla's font, which is what `MultiLineTextWidget` lays its lines out on. */
        const val A_LINE = 9
        const val A_HEADLINE = 1
        const val A_SPARE_LINE = 1

        /** Clear of the title, as every container screen's contents are. */
        const val CONTENT_TOP = 20
    }
}
