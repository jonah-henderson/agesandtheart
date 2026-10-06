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
import co.voik.agesandtheart.client.ui.Palette
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot

/**
 * The drying rack's screen: the inputs, a furnace's arrow, and the output. Each input dries on its own, so each
 * carries its own bar where a tool shows its durability, white rather than coloured.
 */
class DryingRackScreen(menu: DryingRackMenu, inventory: Inventory, title: Component) :
    RecipeListingScreen<DryingRackMenu>(menu, inventory, title) {

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
        addRenderableWidget(ProgressArrow(leftPos + DryingRackSlots.ARROW_X, topPos + DryingRackSlots.ARROW_Y) { NOTHING_ON_THE_ARROW })
        addRenderableWidget(SlotView(leftPos + DryingRackSlots.OUTPUT_X, topPos + DryingRackSlots.OUTPUT_Y))
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + DeskSlots.WING_INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
    }

    /** After the item, so the bar sits over it as a durability bar does; slots draw after every widget. */
    override fun extractSlot(graphics: GuiGraphicsExtractor, slot: Slot, mouseX: Int, mouseY: Int) {
        super.extractSlot(graphics, slot, mouseX, mouseY)
        // The menu adds the inputs first, so their menu indices are their rack indices.
        val isAnInput = slot.index < DryingRackBlockEntity.INPUT_SLOTS
        if (!isAnInput || !slot.hasItem()) return
        val dryness = menu.drynessOf(slot.index)
        if (dryness <= 0f) return
        val left = slot.x + BAR_INSET
        val top = slot.y + BAR_TOP
        graphics.fill(left, top, left + BAR_WIDTH, top + BAR_TRACK_HEIGHT, Palette.OUTLINE)
        graphics.fill(left, top, left + Math.round(BAR_WIDTH * dryness.coerceAtMost(1f)), top + 1, Palette.HIGHLIGHT)
    }

    private companion object {
        const val SLOT_PITCH = 18
        const val NOTHING_ON_THE_ARROW = 0f

        // Vanilla's durability bar: 13 wide, two high, two in from the item's left and thirteen down.
        const val BAR_INSET = 2
        const val BAR_TOP = 13
        const val BAR_WIDTH = 13
        const val BAR_TRACK_HEIGHT = 2
    }
}
