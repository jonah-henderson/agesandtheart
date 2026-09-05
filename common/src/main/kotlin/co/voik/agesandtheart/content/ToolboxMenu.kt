package co.voik.agesandtheart.content

import net.minecraft.core.component.DataComponents
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * A chest's worth of compartments that will only take things that wear out.
 *
 * **A `ChestMenu` so vanilla's own `ContainerScreen` can draw it**, which is the whole of why there is no
 * screen of ours. It carries its own menu type rather than borrowing `GENERIC_9x3`: the client builds the
 * menu from the type, so borrowing vanilla's would give the client unrestricted slots and the server
 * restricted ones, and every refused item would flicker in before snapping back.
 *
 * **The grid is replaced in place rather than rebuilt.** `ChestMenu`'s own grid is private and made of
 * plain slots, which accept anything; swapping the objects at the same indices keeps the parallel lists
 * `addSlot` maintains exactly as long as they were, where clearing and re-adding would not.
 */
class ToolboxMenu(containerId: Int, inventory: Inventory, container: Container) :
    ChestMenu(AgeContent.TOOLBOX_MENU, containerId, inventory, container, ROWS) {

    init {
        for (index in 0..<ROWS * COLUMNS) {
            val stood = slots[index]
            slots[index] = OnlyWhatWearsOut(container, index, stood.x, stood.y).also { it.index = index }
        }
    }

    /**
     * A compartment that takes only what can wear out.
     *
     * **The fence that stops this being a cheap shulker box** (Jonah, 2026-09-05). Twenty-seven slots that
     * took anything would be exactly that, at a fraction of the cost and with none of the journey — so the
     * box holds what a box of spares holds, and hauling is somebody else's problem.
     */
    private class OnlyWhatWearsOut(container: Container, slot: Int, x: Int, y: Int) :
        Slot(container, slot, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = holdable(stack)
    }

    companion object {
        const val ROWS = 3
        const val COLUMNS = 9

        /** A chest's, as asked — the same room to keep a kit in. */
        const val COMPARTMENTS = ROWS * COLUMNS

        /**
         * Whether a toolbox may hold [stack] — **anything that carries durability**.
         *
         * Read off `MAX_DAMAGE` rather than off `isDamageableItem`, which also refuses anything marked
         * unbreakable: a tool that will never break is still a tool, and a box that turned one away would
         * be enforcing a rule nobody stated.
         */
        fun holdable(stack: ItemStack): Boolean = stack.isEmpty || stack.has(DataComponents.MAX_DAMAGE)

        /** The empty box's contents, for anything that needs a container before the block entity exists. */
        fun emptyContents(): SimpleContainer = SimpleContainer(COMPARTMENTS)
    }
}
