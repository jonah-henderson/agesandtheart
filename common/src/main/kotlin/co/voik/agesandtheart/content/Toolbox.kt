package co.voik.agesandtheart.content

import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents

/**
 * A tool breaking in your hands, and the spare that replaces it.
 *
 * **The one thing a toolbox is for.** You keep spares in it; when a haft snaps you reach in without
 * stopping. It is a common quality-of-life mod feature and it is also simply what the object is, which is
 * the whole argument for building it into a block rather than a config option.
 *
 * **Narrow on purpose.** Only what broke, only from a toolbox you are carrying, and only into the slot it
 * broke out of. It is not a second inventory, it does not sort, it does not reach a chest across the room.
 *
 * **Where it is called from is `LivingEntityMixin`**, and that is a mixin because neither loader offers the
 * event: NeoForge has `PlayerDestroyItemEvent` and Fabric API has nothing of the kind, so there is no
 * shared seam to prefer. `LivingEntity.onEquippedItemBroken` is one public method that already fires for
 * exactly this, on both sides.
 */
object Toolbox {

    /**
     * Put a spare for [broken] back into [slot], if [entity] is a player carrying one.
     *
     * Called when the stack in that slot has already been emptied, so the slot is free to fill.
     */
    @JvmStatic
    fun replaceBroken(entity: LivingEntity, broken: Item, slot: EquipmentSlot) {
        if (entity.level().isClientSide) return
        val player = entity as? Player ?: return
        // **Armour too, which is the less usual half and the more useful one** (Jonah, 2026-09-05): a suit
        // that fails in a hostile Age is exactly when you have neither the time nor the standing room to
        // rummage. The one slot left out is the body, which no player wears.
        if (slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) return
        val spare = drawSpare(player, broken)
        if (spare.isEmpty) return
        player.setItemSlot(slot, spare)
    }

    /**
     * The first spare for [broken] in any toolbox the player is carrying, taken out of it.
     *
     * **Read off the item's own component rather than a block entity**, because the box being carried is
     * the case that matters — a placed toolbox is furniture, and a carried one is the feature. The two
     * stay in step because `BaseContainerBlockEntity` writes the same component when the block is broken.
     */
    private fun drawSpare(player: Player, broken: Item): ItemStack {
        for (slot in 0..<player.inventory.containerSize) {
            val carried = player.inventory.getItem(slot)
            if (carried.item !== AgeContent.TOOLBOX) continue
            val held = carried.get(DataComponents.CONTAINER) ?: continue
            // Every compartment including the empty ones, so removing one does not shuffle the rest.
            val compartments = held.allItemsCopyStream().toList()
            val found = compartments.indexOfFirst { !it.isEmpty && it.item === broken }
            if (found < 0) continue
            val spare = compartments[found]
            val left = compartments.toMutableList().apply { this[found] = ItemStack.EMPTY }
            carried.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(left))
            return spare
        }
        return ItemStack.EMPTY
    }
}
