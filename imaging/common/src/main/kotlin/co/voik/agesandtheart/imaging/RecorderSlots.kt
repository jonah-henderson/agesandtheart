package co.voik.agesandtheart.imaging

import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents

/**
 * An image recorder's two slots, the same whether it is set down ([ImageRecorderBlockEntity]) or carried, where they are
 * the item's `minecraft:container`: blank plates in, and the recorded image out. Where each stands on its screen is
 * here too, since the menu and the screen both need it.
 */
object RecorderSlots {
    const val PLATES = 0
    const val PICTURE = 1
    const val COUNT = 2

    const val PANEL_WIDTH = 176
    const val PANEL_HEIGHT = 166
    const val PLATES_X = 56
    const val PICTURE_X = 116
    const val SLOT_Y = 20
    const val ARROW_X = 80
    const val ARROW_Y = 20

    /** The Adjust button, centred under the slots and clear of the inventory's label. */
    const val ADJUST_WIDTH = 60
    const val ADJUST_HEIGHT = 20
    const val ADJUST_X = (PANEL_WIDTH - ADJUST_WIDTH) / 2
    const val ADJUST_Y = 46
    const val INVENTORY_X = 8
    const val INVENTORY_Y = 84
    const val HOTBAR_DROP = 58
    const val INVENTORY_LABEL_Y = INVENTORY_Y - 11

    fun isAPlate(stack: ItemStack): Boolean = stack.`is`(ImagingContent.PHOTOGRAPHIC_PLATE)

    fun isAPicture(stack: ItemStack): Boolean = stack.`is`(ImagingContent.RECORDED_IMAGE)

    /** Plates in the first slot and a recorded image in the second, to whatever asks before putting a thing in. */
    fun belongsIn(slot: Int, stack: ItemStack): Boolean = when (slot) {
        PLATES -> isAPlate(stack)
        PICTURE -> isAPicture(stack)
        else -> false
    }

    /** The slots of a recorder carried in a hand, read from and written back to the stack. */
    class Held(private val recorder: ItemStack) {
        private val items: NonNullList<ItemStack> = NonNullList.withSize(COUNT, ItemStack.EMPTY).also { items ->
            recorder.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items)
        }

        val hasAPlate: Boolean get() = isAPlate(items[PLATES])

        /** One of its plates spent, answering whether it had one. */
        fun spendAPlate(): Boolean {
            if (!hasAPlate) return false
            items[PLATES].shrink(1)
            save()
            return true
        }

        /** [picture] kept in its output, answering whether that was empty to take it. */
        fun keep(picture: ItemStack): Boolean {
            if (!items[PICTURE].isEmpty) return false
            items[PICTURE] = picture
            save()
            return true
        }

        private fun save() = recorder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items))
    }
}
