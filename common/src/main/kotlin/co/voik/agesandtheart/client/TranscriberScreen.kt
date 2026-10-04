package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.ProgressArrow
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.desk.TranscriberMenu
import co.voik.agesandtheart.desk.TranscriberSlots
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** The transcriber's screen: the bound book over the blank, an arrow that fills when a copy is ready, and the copy. */
class TranscriberScreen(menu: TranscriberMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<TranscriberMenu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT) {

    override fun init() {
        super.init()
        inventoryLabelY = DeskSlots.WING_INVENTORY_LABEL_Y

        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT)
            },
        )
        addRenderableWidget(SlotView(leftPos + TranscriberSlots.ORIGINAL_X, topPos + TranscriberSlots.ORIGINAL_Y))
        addRenderableWidget(SlotView(leftPos + TranscriberSlots.BLANK_X, topPos + TranscriberSlots.BLANK_Y))
        addRenderableWidget(
            ProgressArrow(leftPos + TranscriberSlots.ARROW_X, topPos + TranscriberSlots.ARROW_Y) { if (menu.hasCopy) 1f else 0f },
        )
        addRenderableWidget(SlotView(leftPos + TranscriberSlots.COPY_X, topPos + TranscriberSlots.COPY_Y))
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + DeskSlots.WING_INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
    }
}
