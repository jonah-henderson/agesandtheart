package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.ProgressArrow
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.imaging.ImageRecorderMenu
import co.voik.agesandtheart.imaging.ImagingContent
import co.voik.agesandtheart.imaging.RecorderSlots
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * A set-down recorder's screen: its plates, an arrow to the picture it took, and the picture. Each empty slot shows what
 * belongs there as a ghost, as the transcriber's does.
 */
class ImageRecorderScreen(menu: ImageRecorderMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<ImageRecorderMenu>(menu, inventory, title, RecorderSlots.PANEL_WIDTH, RecorderSlots.PANEL_HEIGHT) {

    private val plateGhost = ItemStack(ImagingContent.PHOTOGRAPHIC_PLATE)
    private val pictureGhost = ItemStack(ImagingContent.RECORDED_IMAGE)

    override fun init() {
        super.init()
        inventoryLabelY = RecorderSlots.INVENTORY_LABEL_Y
        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(RecorderSlots.PANEL_WIDTH, RecorderSlots.PANEL_HEIGHT)
            },
        )
        addRenderableWidget(SlotView(leftPos + RecorderSlots.PLATES_X, topPos + RecorderSlots.SLOT_Y))
        addRenderableWidget(ProgressArrow(leftPos + RecorderSlots.ARROW_X, topPos + RecorderSlots.ARROW_Y) { NO_PROGRESS })
        addRenderableWidget(SlotView(leftPos + RecorderSlots.PICTURE_X, topPos + RecorderSlots.SLOT_Y))
        addRenderableWidget(PlayerInventoryView(leftPos + RecorderSlots.INVENTORY_X, topPos + RecorderSlots.INVENTORY_Y, RecorderSlots.HOTBAR_DROP))
    }

    /** Vanilla's ghost recipe: the item drawn flat, then washed over. */
    override fun extractSlot(graphics: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int) {
        super.extractSlot(graphics, slot, mouseX, mouseY)
        if (slot.hasItem()) return
        val ghost = when (slot) {
            menu.platesSlot -> plateGhost
            menu.pictureSlot -> pictureGhost
            else -> return
        }
        graphics.fakeItem(ghost, slot.x, slot.y)
        graphics.fill(slot.x, slot.y, slot.x + ITEM_SIZE, slot.y + ITEM_SIZE, GHOST_WASH)
    }

    private companion object {
        const val NO_PROGRESS = 0.0f
        const val ITEM_SIZE = 16

        /** Vanilla's own wash over a ghost item. */
        const val GHOST_WASH = 0x30FFFFFF
    }
}
