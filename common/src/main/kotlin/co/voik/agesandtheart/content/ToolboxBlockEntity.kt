package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.network.chat.Component
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * What a toolbox holds: nine compartments of spares.
 *
 * **A chest's twenty-seven, drawn by vanilla's own container screen** — so there is no screen of ours. It
 * is not a cheap shulker box for all that room, because [ToolboxMenu] takes only what carries durability:
 * a box of spares rather than luggage.
 *
 * **The contents travel with the block**, on the shulker's own mechanism — [BaseContainerBlockEntity]
 * already reads and writes `DataComponents.CONTAINER` for us, and the loot table copies it onto the item.
 * That is the whole of why a toolbox is worth carrying rather than worth placing.
 */
class ToolboxBlockEntity(pos: BlockPos, state: BlockState) :
    BaseContainerBlockEntity(AgeContent.TOOLBOX_ENTITY, pos, state) {

    private var items: NonNullList<ItemStack> = NonNullList.withSize(ToolboxMenu.COMPARTMENTS, ItemStack.EMPTY)

    override fun getContainerSize(): Int = ToolboxMenu.COMPARTMENTS

    /** And the same fence for a hopper, which never goes through a slot. */
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = ToolboxMenu.holdable(stack)

    override fun getItems(): NonNullList<ItemStack> = items

    override fun setItems(items: NonNullList<ItemStack>) {
        this.items = items
    }

    override fun getDefaultName(): Component = Component.translatable("block.agesandtheart.toolbox")

    override fun createMenu(containerId: Int, inventory: Inventory): AbstractContainerMenu =
        ToolboxMenu(containerId, inventory, this)

    /**
     * The first spare that is exactly [wanted], taken out — or nothing, where the box has none.
     *
     * **Exactly the same item, never merely a similar one.** A box that handed back a stone pickaxe when a
     * diamond one broke would be helping in a way nobody asked for, and the case that matters — a silk
     * touch pick replaced by a plain one — is a genuine loss. Carrying identical spares is the player's
     * side of the bargain, and it is what a real toolbox holds anyway.
     */
    fun takeSpare(wanted: net.minecraft.world.item.Item): ItemStack {
        val slot = items.indexOfFirst { !it.isEmpty && it.item === wanted }
        if (slot < 0) return ItemStack.EMPTY
        val taken = ContainerHelper.takeItem(items, slot)
        setChanged()
        return taken
    }

    /** Whether any compartment holds one, asked without taking it. */
    fun holdsSpare(wanted: net.minecraft.world.item.Item): Boolean =
        items.any { !it.isEmpty && it.item === wanted }

}
