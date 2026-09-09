package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.reward.Footing
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.desk.SeismographMenu
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * What the seismograph says, on its own panel.
 *
 * **A headline and a list, not a sentence** (Jonah, 2026-09-07). The headline is the one line the asset
 * pass replaces — the block gets an animation per [Footing], so a writer who has learned the three shapes
 * reads the state off the instrument across the room and opens this only for the specifics. The specifics
 * are therefore terse and itemised: a reader scanning a list wants nouns, and prose would make the panel
 * something to read rather than something to check.
 */
class SeismographScreen(menu: SeismographMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<SeismographMenu>(menu, inventory, title, WIDTH, tallEnoughFor()) {

    private lateinit var readout: MultiLineTextWidget
    private lateinit var scrolling: ScrollableLayout

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

    /**
     * The headline, then one line per thing the instability bought.
     *
     * **The instrument is never idle**, so there is no "nothing to read" state to draw — but there are
     * four different calm things to say and they are not interchangeable (Jonah, 2026-09-09). A sentence
     * that buys nothing has no instability *yet*; a bare desk in the room has nothing to read at all and
     * saying the world is stable there answers a question nobody asked; an ordinary world was never
     * written; and a stable Age was written well, which is a compliment rather than a fact about physics.
     */
    private fun lines(): List<Component> {
        if (menu.source == SeismographMenu.AN_IDLE_DESK) return listOf(translated("desk_idle"))
        val state = Footing.entries.getOrNull(menu.footing) ?: Footing.STABLE
        if (state != Footing.STABLE) return listOf(translated("headline_${state.key}")) + bought()
        val calm = when (menu.source) {
            SeismographMenu.A_PLAIN_WORLD -> "world_stable"
            SeismographMenu.AN_AGE -> "age_stable"
            else -> "headline_stable"
        }
        return listOf(translated(calm))
    }

    private fun bought(): List<Component> =
        Manifestation.entries
            .filter { menu.bought and (1 shl it.ordinal) != 0 }
            .map { translated("bought", translated("manifest_${it.key}")) }

    /** The panel behind it all, at whatever size vanilla settled on — never a second opinion about it. */
    private fun panel(): AbstractWidget = DecorationWidget(PanelSurface.RAISED).also {
        it.setPosition(leftPos, topPos)
        it.setSize(imageWidth, imageHeight)
    }

    private fun translated(suffix: String, vararg arguments: Any): Component =
        Component.translatable("container.agesandtheart.seismograph.$suffix", *arguments)

    private fun stacked(lines: List<Component>): Component =
        lines.foldIndexed(Component.empty()) { index, built, line ->
            if (index > 0) built.append(Component.literal("\n"))
            built.append(line)
        }

    private companion object {
        const val WIDTH = 176

        /**
         * Room for the headline and every manifestation at once — **counted rather than written down**.
         *
         * It was ninety-six, which was three lines short of the seven manifestations there were then and
         * a whole line short again once instability could set an Age alight: a badly written Age ran its
         * own list off the bottom of the panel (Jonah, 2026-09-09). A scroll bar is the wrong answer for a
         * list that is bounded and this short — the panel should simply be as tall as the worst case, and
         * the worst case is a thing the code can count.
         *
         * The spare line is for a name long enough to wrap at this width; there is one already at
         * "Spreading spatial anomalies".
         */
        /**
         * **As tall as its own worst case, and never taller than the window will take.**
         *
         * Two things this has to be, and it used to be neither. It was a hand-written ninety-six carrying
         * a comment claiming it was room for the headline and every manifestation — three lines short of
         * the seven there were then, and a line short again once instability could set an Age alight, so a
         * badly written Age ran its own list off the bottom of the panel (Jonah, 2026-09-09).
         *
         * Counting the list fixes today and not tomorrow: **the list will go on growing** and a panel that
         * simply grew with it would one day be taller than the window, with nowhere to notice until it
         * was. So it is capped to the window less a little air above and below, and [ScrollableLayout]
         * carries whatever does not fit — a scroll bar nobody will see for a long time, and the reason to
         * put one in now is that the day it is needed is not a day anybody will be looking.
         *
         * Read at construction because `imageHeight` is `final`: a screen decides its size before vanilla
         * places it, and the window is already there to ask.
         */
        private fun tallEnoughFor(): Int =
            worstCase().coerceAtMost(Minecraft.getInstance().window.guiScaledHeight - PADDING * 2)

        /** Every line it could ever have to show at once, with room for one of them to wrap. */
        private fun worstCase(): Int =
            CONTENT_TOP + (Manifestation.entries.size + A_HEADLINE + A_SPARE_LINE) * A_LINE + MARGIN * 2

        /** Air above and below, so a capped panel is not pressed against the edges of the window. */
        private const val PADDING = 20

        const val MARGIN = 8

        /** Vanilla's font, which is what `MultiLineTextWidget` lays its lines out on. */
        private const val A_LINE = 9
        private const val A_HEADLINE = 1
        private const val A_SPARE_LINE = 1

        /** Clear of the title, as every container screen's contents are. */
        const val CONTENT_TOP = 20
    }
}
