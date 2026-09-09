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
import net.minecraft.client.gui.components.MultiLineTextWidget
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
    AbstractContainerScreen<SeismographMenu>(menu, inventory, title, WIDTH, HEIGHT) {

    private lateinit var readout: MultiLineTextWidget

    override fun init() {
        super.init()
        addRenderableWidget(panel())
        readout = MultiLineTextWidget(Component.empty(), font).setMaxWidth(WIDTH - MARGIN * 2)
        readout.setPosition(leftPos + MARGIN, topPos + CONTENT_TOP)
        addRenderableWidget(readout)
    }

    /** Title only: there is no inventory on this screen, so vanilla's second label would name nothing. */
    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, title, titleLabelX, titleLabelY, Palette.TEXT, false)
    }

    override fun containerTick() {
        super.containerTick()
        readout.message = stacked(lines())
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

    private fun panel(): AbstractWidget = DecorationWidget(PanelSurface.RAISED).also {
        it.setPosition(leftPos, topPos)
        it.setSize(WIDTH, HEIGHT)
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
        val HEIGHT = CONTENT_TOP + (Manifestation.entries.size + A_HEADLINE + A_SPARE_LINE) * A_LINE + MARGIN * 2

        const val MARGIN = 8

        /** Vanilla's font, which is what `MultiLineTextWidget` lays its lines out on. */
        private const val A_LINE = 9
        private const val A_HEADLINE = 1
        private const val A_SPARE_LINE = 1

        /** Clear of the title, as every container screen's contents are. */
        const val CONTENT_TOP = 20
    }
}
