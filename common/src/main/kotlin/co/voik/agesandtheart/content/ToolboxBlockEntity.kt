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
     * **Nothing is scattered when the box is broken**, which is the shulker box's own override and the one
     * line that makes a container luggage rather than furniture.
     *
     * `BlockEntity.preRemoveSideEffects` drops the contents of *any* `Container` by default, so a box that
     * says nothing here empties itself onto the floor however carefully its loot table copies the contents
     * onto the item — which is exactly what a toolbox must not do. `ShulkerBoxBlockEntity` overrides it to
     * do nothing for the same reason, and the loot table's `copy_components` is then what carries the
     * spares away in your hand.
     */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) = Unit
}
