package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.ProgressArrow
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.station.StationMenu
import co.voik.agesandtheart.station.StationSlots
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** A grinder's or a pulper's screen: an input, the arrow, the result, and the player's inventory. */
class StationScreen(menu: StationMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<StationMenu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT) {

    override fun init() {
        super.init()
        inventoryLabelY = DeskSlots.WING_INVENTORY_LABEL_Y

        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT)
            },
        )
        addRenderableWidget(SlotView(leftPos + StationSlots.INPUT_X, topPos + StationSlots.INPUT_Y))
        addRenderableWidget(SlotView(leftPos + StationSlots.OUTPUT_X, topPos + StationSlots.OUTPUT_Y))
        addRenderableWidget(ProgressArrow(leftPos + StationSlots.ARROW_X, topPos + StationSlots.ARROW_Y) { menu.share })
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + DeskSlots.WING_INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
    }
}
