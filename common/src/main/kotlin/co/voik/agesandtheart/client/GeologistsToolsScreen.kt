package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.reward.EarlyGameRareMaterial
import co.voik.agesandtheart.age.reward.Yield
import co.voik.agesandtheart.content.RimeColour
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.desk.GeologistsToolsMenu
import co.voik.agesandtheart.desk.materialsIn
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * What the geologist's tools say, on their own panel — the shape [SeismographScreen] set on 2026-09-07.
 *
 * **Quantities and names, and never a forecast of what makes them** (design §7.7). Saying what the danger
 * *was* would be a preview of the Age; saying what comes out of the ground is an outcome, and it survives
 * an evocative word the writer themselves cannot unpack. The lesson — that the Ages surveying well are the
 * ones with hazards written into them — is left to be noticed rather than told.
 */
class GeologistsToolsScreen(menu: GeologistsToolsMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<GeologistsToolsMenu>(menu, inventory, title, WIDTH, tallEnoughFor()) {

    private lateinit var readout: MultiLineTextWidget
    private lateinit var scrolling: ScrollableLayout

    override fun init() {
        super.init()
        addRenderableWidget(panel())
        readout = MultiLineTextWidget(Component.empty(), font).setMaxWidth(WIDTH - MARGIN * 2)
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
        // Only when it actually changed, or laying out again would drag the view back to the top while
        // somebody was reading it.
        readout.message = said
        scrolling.arrangeElements()
    }

    /**
     * The deposit, then one line per early material the Age would grow.
     *
     * **Like the seismograph, it is never idle**, and the calm cases are not interchangeable: a bare desk
     * has nothing to read at all, a plain world was never written and has no recipe to survey, and an Age
     * that holds nothing is a true answer about somewhere real.
     */
    private fun lines(): List<Component> {
        if (menu.source == GeologistsToolsMenu.AN_IDLE_DESK) return listOf(translated("desk_idle"))
        if (menu.source == GeologistsToolsMenu.A_PLAIN_WORLD) return listOf(translated("plain_world"))
        val deposit = Yield.entries.getOrNull(menu.deposit) ?: Yield.NONE
        val headline = translated(
            "deposit",
            Component.translatable(AgeContent.PITCHSTONE.descriptionId),
            translated(deposit.key),
        )
        return listOf(headline) + materialsIn(menu.materials).map { translated("grows", nameOf(it)) }
    }

    /**
     * What an early material is called, **taken from the block itself** so the survey can never name it
     * something other than what the player ends up holding.
     */
    private fun nameOf(material: EarlyGameRareMaterial): Component = when (material) {
        EarlyGameRareMaterial.RIME -> AgeContent.RIME_CRYSTAL_BLOCKS.getValue(RimeColour.CYAN).name
        EarlyGameRareMaterial.TEMPERSTONE -> AgeContent.TEMPERSTONE_BLOCK.name
        EarlyGameRareMaterial.ARC_CRYSTAL -> AgeContent.ARC_CRYSTAL_CLUSTER.name
    }

    /** The panel behind it all, at whatever size vanilla settled on — never a second opinion about it. */
    private fun panel(): AbstractWidget = DecorationWidget(PanelSurface.RAISED).also {
        it.setPosition(leftPos, topPos)
        it.setSize(imageWidth, imageHeight)
    }

    private fun translated(suffix: String, vararg arguments: Any): Component =
        Component.translatable("container.agesandtheart.geologists_tools.$suffix", *arguments)

    private fun stacked(lines: List<Component>): Component =
        lines.foldIndexed(Component.empty()) { index, built, line ->
            if (index > 0) built.append(Component.literal("\n"))
            built.append(line)
        }

    private companion object {
        const val WIDTH = 176

        /**
         * As tall as its own worst case and never taller than the window, which is the rule
         * [SeismographScreen] arrived at after running a list off the bottom of a panel.
         *
         * The worst case here is the deposit line and every early material at once, with a spare line for
         * one of them to wrap at this width.
         */
        private fun tallEnoughFor(): Int =
            worstCase().coerceAtMost(Minecraft.getInstance().window.guiScaledHeight - PADDING * 2)

        private fun worstCase(): Int =
            CONTENT_TOP + (EarlyGameRareMaterial.entries.size + A_HEADLINE + A_SPARE_LINE) * A_LINE + MARGIN * 2

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
