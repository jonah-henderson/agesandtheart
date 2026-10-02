package co.voik.agesandtheart.station

import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.StackedItemContents
import net.minecraft.world.inventory.RecipeBookMenu.PostPlaceAction
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * A compounding laid out of the player's inventory into the compounder's inputs, as the recipe book asks:
 * vanilla's `ServerPlaceRecipe` with counts, so an ingredient wanting sixteen takes sixteen into its slot.
 *
 * The same rules as vanilla's: a click places one compounding's worth, another click on a recipe already
 * laid out adds one more, shift lays as many as the inventory and the stacks allow, and a recipe the
 * inventory cannot supply clears the inputs and shows its ghost.
 *
 * It leans on [CompoundingRecipe.placementInfo] listing each ingredient once per item it takes, which is
 * what makes `StackedItemContents` count.
 */
internal class CompoundingPlacement(private val inputs: List<Slot>, private val inventory: Inventory) {

    fun place(recipe: CompoundingRecipe, useMaxItems: Boolean, allowDroppingItemsToClear: Boolean): PostPlaceAction {
        if (!recipe.isAllowed) return PostPlaceAction.NOTHING
        if (!allowDroppingItemsToClear && !inputsFitBackIntoTheInventory()) return PostPlaceAction.NOTHING
        val available = StackedItemContents()
        inventory.fillStackedContents(available)
        inputs.forEach { available.accountSimpleStack(it.item) }
        if (!available.canCraft(recipe, null)) {
            clearInputs()
            inventory.setChanged()
            return PostPlaceAction.PLACE_GHOST_RECIPE
        }
        laidOut(recipe, available, useMaxItems)
        inventory.setChanged()
        return PostPlaceAction.NOTHING
    }

    private fun laidOut(recipe: CompoundingRecipe, available: StackedItemContents, useMaxItems: Boolean) {
        val biggest = available.getBiggestCraftableStack(recipe, null)
        val heldAlready = compoundingsHeld(recipe)
        val wanted = when {
            useMaxItems -> biggest
            heldAlready != null -> heldAlready + 1
            else -> 1
        }.coerceAtMost(biggest)

        val used = mutableListOf<Holder<Item>>()
        if (!available.canCraft(recipe, wanted, used::add)) return
        val fitting = clampedToStacks(recipe, wanted, used)
        val nothingMoreFits = heldAlready != null && fitting <= heldAlready
        if (fitting < 1 || nothingMoreFits) return
        if (fitting != wanted) {
            used.clear()
            if (!available.canCraft(recipe, fitting, used::add)) return
        }

        clearInputs()
        for ((index, ingredient) in recipe.ingredients.withIndex()) {
            moveIntoSlot(inputs[index], used[firstEntryOf(recipe, index)], ingredient.count * fitting)
        }
    }

    /** How many compoundings the inputs hold already, or null where they are not laid out for [recipe]. */
    private fun compoundingsHeld(recipe: CompoundingRecipe): Int? {
        val slots = recipe.slotsFor(CompounderInput(inputs.map(Slot::getItem))) ?: return null
        return recipe.ingredients.zip(slots).minOf { (ingredient, slot) -> inputs[slot].item.count / ingredient.count }
    }

    /** Where ingredient [index]'s entries begin in the placement list, which holds one per item taken. */
    private fun firstEntryOf(recipe: CompoundingRecipe, index: Int): Int =
        recipe.ingredients.take(index).sumOf { it.count }

    /** [wanted] compoundings, or as many fewer as keep every slot to one stack of what it holds. */
    private fun clampedToStacks(recipe: CompoundingRecipe, wanted: Int, used: List<Holder<Item>>): Int =
        recipe.ingredients.withIndex().minOf { (index, ingredient) ->
            val item = used[firstEntryOf(recipe, index)]
            val stack = item.components().getOrDefault(DataComponents.MAX_STACK_SIZE, 1)
            minOf(wanted, stack / ingredient.count)
        }

    private fun moveIntoSlot(slot: Slot, item: Holder<Item>, count: Int) {
        var remaining = count
        while (remaining > 0) {
            val from = inventory.findSlotMatchingCraftingIngredient(item, slot.item)
            if (from == NOT_FOUND) return
            val there = inventory.getItem(from)
            val taken = if (remaining < there.count) inventory.removeItem(from, remaining) else inventory.removeItemNoUpdate(from)
            if (slot.item.isEmpty) slot.set(taken) else slot.item.grow(taken.count)
            remaining -= taken.count
        }
    }

    private fun clearInputs() {
        for (slot in inputs) {
            inventory.placeItemBackInInventory(slot.item.copy(), false, Prediction.SERVER_ONLY)
            slot.set(ItemStack.EMPTY)
        }
    }

    /** Vanilla's `testClearGrid`: whether what is in the inputs could go back without dropping any. */
    private fun inputsFitBackIntoTheInventory(): Boolean {
        val freeSlots = inventory.nonEquipmentItems.count { it.isEmpty }
        val takingFreeSlots = mutableListOf<ItemStack>()
        for (slot in inputs) {
            val held = slot.item.copy()
            if (held.isEmpty) continue
            if (inventory.getSlotWithRemainingSpace(held) != NOT_FOUND) continue
            val joins = takingFreeSlots.firstOrNull { taking ->
                val hasRoom = taking.count + held.count <= taking.maxStackSize
                ItemStack.isSameItem(taking, held) && hasRoom
            }
            if (joins != null) {
                joins.grow(held.count)
                continue
            }
            if (takingFreeSlots.size >= freeSlots) return false
            takingFreeSlots += held
        }
        return true
    }

    private companion object {
        const val NOT_FOUND = -1
    }
}
