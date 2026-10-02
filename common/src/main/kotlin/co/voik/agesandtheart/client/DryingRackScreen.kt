package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.ProgressArrow
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.station.DryingRackBlockEntity
import co.voik.agesandtheart.station.DryingRackMenu
import co.voik.agesandtheart.station.DryingRackSlots
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** The drying rack's screen: the inputs, a furnace's arrow filling as the item being dried dries, and the output. */
class DryingRackScreen(menu: DryingRackMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<DryingRackMenu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT) {

    override fun init() {
        super.init()
        inventoryLabelY = DeskSlots.WING_INVENTORY_LABEL_Y

        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT)
            },
        )
        for (slot in 0..<DryingRackBlockEntity.INPUT_SLOTS) {
            val column = slot / DryingRackSlots.INPUT_ROWS
            val row = slot % DryingRackSlots.INPUT_ROWS
            addRenderableWidget(
                SlotView(leftPos + DryingRackSlots.INPUT_X + column * SLOT_PITCH, topPos + DryingRackSlots.INPUT_Y + row * SLOT_PITCH),
            )
        }
        addRenderableWidget(ProgressArrow(leftPos + DryingRackSlots.ARROW_X, topPos + DryingRackSlots.ARROW_Y) { menu.dryness })
        addRenderableWidget(SlotView(leftPos + DryingRackSlots.OUTPUT_X, topPos + DryingRackSlots.OUTPUT_Y))
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + DeskSlots.WING_INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
    }

    private companion object {
        const val SLOT_PITCH = 18
    }
}
