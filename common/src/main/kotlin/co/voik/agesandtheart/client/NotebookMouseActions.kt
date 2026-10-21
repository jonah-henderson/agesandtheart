package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import co.voik.agesandtheart.content.NotebookSelectPayload
import net.minecraft.client.ScrollWheelHandler
import net.minecraft.client.gui.ItemSlotMouseAction
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Scrolling over a notebook picks the page a right click takes out, as vanilla's `BundleMouseActions` does a
 * bundle's — through **every** page where a bundle's stops at the last few it shows. Vanilla's own is tied to
 * `BundleItem`, whose contents and selection a notebook does not have.
 */
class NotebookMouseActions : ItemSlotMouseAction {
    private val scrollWheelHandler = ScrollWheelHandler()

    override fun matches(slot: Slot): Boolean = slot.item.`is`(AgeContent.NOTEBOOK)

    override fun onMouseScrolled(scrollX: Double, scrollY: Double, slotIndex: Int, itemStack: ItemStack): Boolean {
        val pages = NotebookItem.pagesIn(itemStack).size
        if (pages == 0) return false
        val wheel = scrollWheelHandler.onMouseScroll(scrollX, scrollY).let { if (it.y == 0) -it.x else it.y }
        if (wheel == 0) return true
        val current = NotebookItem.selectedIn(itemStack) ?: NotebookSelectPayload.NONE
        val next = ScrollWheelHandler.getNextScrollWheelSelection(wheel.toDouble(), current, pages)
        if (next != current) select(itemStack, slotIndex, next)
        return true
    }

    override fun onStopHovering(hoveredSlot: Slot) = select(hoveredSlot.item, hoveredSlot.index, NotebookSelectPayload.NONE)

    override fun onSlotClicked(slot: Slot, containerInput: ContainerInput) {
        val takesTheNotebookAway = containerInput == ContainerInput.QUICK_MOVE || containerInput == ContainerInput.SWAP
        if (takesTheNotebookAway) select(slot.item, slot.index, NotebookSelectPayload.NONE)
    }

    private fun select(notebook: ItemStack, slotIndex: Int, newestFirst: Int) {
        NotebookItem.select(notebook, newestFirst)
        sendToServer(NotebookSelectPayload(slotIndex, newestFirst))
    }
}
