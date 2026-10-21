package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.ProgressArrow
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.desk.Transcriber
import co.voik.agesandtheart.desk.TranscriberMenu
import co.voik.agesandtheart.desk.TranscriberSlots
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * The transcriber's screen: the bound book over the book it is copied into, a furnace's arrow filling as the copy is
 * written, and the copy. The empty lower slot shows what may go in it, in turn, as a ghost.
 */
class TranscriberScreen(menu: TranscriberMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<TranscriberMenu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT) {

    private val ghosts: List<ItemStack> = Transcriber.WRITTEN_OVER.map(::ItemStack)

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
        addRenderableWidget(SlotView(leftPos + TranscriberSlots.DESTINATION_X, topPos + TranscriberSlots.DESTINATION_Y))
        addRenderableWidget(ProgressArrow(leftPos + TranscriberSlots.ARROW_X, topPos + TranscriberSlots.ARROW_Y) { menu.progressFraction })
        addRenderableWidget(SlotView(leftPos + TranscriberSlots.COPY_X, topPos + TranscriberSlots.COPY_Y))
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + DeskSlots.WING_INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
    }

    /** Vanilla's ghost recipe: the item drawn flat, then washed over. */
    override fun extractSlot(graphics: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int) {
        super.extractSlot(graphics, slot, mouseX, mouseY)
        if (slot !== menu.destinationSlot || slot.hasItem()) return
        val ghost = ghosts[((Util.getMillis() / MILLIS_PER_GHOST) % ghosts.size).toInt()]
        graphics.fakeItem(ghost, slot.x, slot.y)
        graphics.fill(slot.x, slot.y, slot.x + ITEM_SIZE, slot.y + ITEM_SIZE, GHOST_WASH)
    }

    private companion object {
        const val MILLIS_PER_GHOST = 1000L
        const val ITEM_SIZE = 16

        /** Vanilla's own wash over a ghost item. */
        const val GHOST_WASH = 0x30FFFFFF
    }
}
