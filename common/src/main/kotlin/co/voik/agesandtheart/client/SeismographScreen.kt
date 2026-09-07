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
     * **The instrument is never idle**, so there is no "nothing to read" state to draw: with no sentence
     * in reach it reads the world it stands in, and the only thing that changes is how the calm case is
     * worded — a world that is quiet is *perfectly stable*, where a sentence that buys nothing merely has
     * no instability in it yet.
     */
    private fun lines(): List<Component> {
        val state = Footing.entries.getOrNull(menu.footing) ?: Footing.STABLE
        val quiet = state == Footing.STABLE && menu.readsTheWorld
        val headline = if (quiet) translated("world_stable") else translated("headline_${state.key}")
        return listOf(headline) + bought()
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

        /** Room for the headline and every manifestation at once, which is the tallest it can ever be. */
        const val HEIGHT = 96
        const val MARGIN = 8

        /** Clear of the title, as every container screen's contents are. */
        const val CONTENT_TOP = 20
    }
}
