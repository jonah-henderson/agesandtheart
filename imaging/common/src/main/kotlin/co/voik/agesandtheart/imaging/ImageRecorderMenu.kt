package co.voik.agesandtheart.imaging

import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/** A set-down recorder's two slots and the player's inventory: plates go in by hand, and the picture comes out. */
class ImageRecorderMenu(containerId: Int, playerInventory: Inventory, private val recorder: Container) :
    AbstractContainerMenu(ImagingContent.IMAGE_RECORDER_MENU, containerId) {

    /** The client's, which vanilla's slot syncing fills in. */
    constructor(containerId: Int, playerInventory: Inventory) : this(containerId, playerInventory, SimpleContainer(RecorderSlots.COUNT))

    init {
        checkContainerSize(recorder, RecorderSlots.COUNT)
        addSlot(object : Slot(recorder, RecorderSlots.PLATES, RecorderSlots.PLATES_X, RecorderSlots.SLOT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = RecorderSlots.isAPlate(stack)
        })
        addSlot(object : Slot(recorder, RecorderSlots.PICTURE, RecorderSlots.PICTURE_X, RecorderSlots.SLOT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = false
        })
        addStandardInventorySlots(playerInventory, RecorderSlots.INVENTORY_X, RecorderSlots.INVENTORY_Y)
    }

    val platesSlot: Slot get() = slots[RecorderSlots.PLATES]

    val pictureSlot: Slot get() = slots[RecorderSlots.PICTURE]

    /** The recorder's slots go to the inventory; from the inventory, only plates go anywhere, into the recorder. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        val isTheRecorders = index < RecorderSlots.COUNT
        val landed = when {
            isTheRecorders -> moveItemStackTo(moved, RecorderSlots.COUNT, slots.size, true)
            RecorderSlots.isAPlate(moved) -> moveItemStackTo(moved, RecorderSlots.PLATES, RecorderSlots.PLATES + 1, false)
            else -> false
        }
        if (!landed) return ItemStack.EMPTY
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = recorder.stillValid(player)
}
