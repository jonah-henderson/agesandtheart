package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.ProgressBar
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.desk.GrammarStudyMenu
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** An empty grammar guide: its one slot, "Studying...", how far it is studied, and the player's inventory. */
class GrammarStudyScreen(menu: GrammarStudyMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<GrammarStudyMenu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT) {

    override fun init() {
        super.init()
        inventoryLabelY = DeskSlots.WING_INVENTORY_LABEL_Y
        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(imageWidth, imageHeight)
            },
        )
        addRenderableWidget(SlotView(leftPos + DeskSlots.WING_INTAKE_X, topPos + DeskSlots.WING_INTAKE_Y))
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + DeskSlots.WING_INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
        val contentWidth = DeskSlots.WING_INTAKE_X - DeskSlots.INVENTORY_X - CLEAR_OF_THE_SLOT
        addRenderableWidget(
            ProgressBar(contentWidth, BAR_HEIGHT, reading = { menu.studied }, colour = BAR_COLOUR, tooltip = ::progressTooltip).also {
                it.setPosition(leftPos + DeskSlots.INVENTORY_X, topPos + BAR_Y)
            },
        )
    }

    /** "Studying..." in the labels' own colour, beside the title and the inventory's. */
    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        super.extractLabels(graphics, mouseX, mouseY)
        graphics.text(font, Component.translatable(STUDYING), DeskSlots.INVENTORY_X, LABEL_Y, Palette.TEXT, false)
    }

    private fun progressTooltip(): Component =
        Component.translatable(PROGRESS, (menu.studied * PERCENT).toInt().coerceIn(0, PERCENT.toInt()))

    private companion object {
        const val STUDYING = "container.agesandtheart.empty_grammar_guide.studying"
        const val PROGRESS = "container.agesandtheart.empty_grammar_guide.progress"
        const val CLEAR_OF_THE_SLOT = 10
        const val LABEL_Y = 24
        const val BAR_Y = 36
        const val BAR_HEIGHT = 10
        const val PERCENT = 100f

        /** The enchanting table's purple, for a book coming to know its language. */
        val BAR_COLOUR = 0xFF8A5CC9.toInt()
    }
}
