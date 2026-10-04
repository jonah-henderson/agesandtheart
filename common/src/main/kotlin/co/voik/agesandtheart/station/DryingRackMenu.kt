package co.voik.agesandtheart.station

import co.voik.agesandtheart.desk.DeskSlots
import net.minecraft.server.MinecraftServer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * The drying rack's screen: six inputs in two columns of three, a furnace's arrow, and the output, over the
 * player's inventory. How far the item being dried has got travels on two data slots.
 */
class DryingRackMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val rack: Container,
    private val progress: ContainerData,
) : AbstractContainerMenu(DryingRack.MENU, containerId), ListsItsRecipes {

    override var listed: List<ListedRecipe> = emptyList()

    override fun recipesOn(server: MinecraftServer): List<MachineRecipe> =
        MachineRecipeLists.of(server, Drying.TYPE) { recipe ->
            if (recipe.isAllowed) MachineRecipe(recipe.ingredient, recipe.result) else null
        }

    /** The client's, which vanilla's data syncing fills in. */
    constructor(containerId: Int, playerInventory: Inventory) :
        this(containerId, playerInventory, SimpleContainer(DryingRackBlockEntity.SLOT_COUNT), SimpleContainerData(DATA_COUNT))

    init {
        for (slot in 0..<DryingRackBlockEntity.INPUT_SLOTS) {
            val column = slot / DryingRackSlots.INPUT_ROWS
            val row = slot % DryingRackSlots.INPUT_ROWS
            addSlot(object : Slot(rack, slot, DryingRackSlots.INPUT_X + column * SLOT_PITCH, DryingRackSlots.INPUT_Y + row * SLOT_PITCH) {
                override fun mayPlace(stack: ItemStack): Boolean = rack.canPlaceItem(index, stack)
            })
        }
        addSlot(object : Slot(rack, DryingRackBlockEntity.OUTPUT, DryingRackSlots.OUTPUT_X, DryingRackSlots.OUTPUT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = false
        })
        addStandardInventorySlots(playerInventory, DeskSlots.INVENTORY_X, DeskSlots.WING_INVENTORY_Y)
        addDataSlots(progress)
    }

    /** How far the item being dried has got, from nothing to done. */
    val dryness: Float
        get() {
            val total = progress.get(TOTAL)
            return if (total <= 0) 0f else progress.get(DRIED).toFloat() / total
        }

    /** The output goes to the inventory; an input back to the inventory; anything else to the inputs. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        val isTheRacks = index < FIRST_PLAYER_SLOT
        val landed = if (isTheRacks) {
            moveItemStackTo(moved, FIRST_PLAYER_SLOT, slots.size, true)
        } else {
            moveItemStackTo(moved, 0, DryingRackBlockEntity.INPUT_SLOTS, false)
        }
        if (!landed) return ItemStack.EMPTY
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = rack.stillValid(player)

    companion object {
        const val DRIED = 0
        const val TOTAL = 1
        const val DATA_COUNT = 2

        private const val FIRST_PLAYER_SLOT = DryingRackBlockEntity.SLOT_COUNT
        private const val SLOT_PITCH = 18
    }
}

/** Where the rack's slots and arrow sit — stated once for the menu and the screen. */
object DryingRackSlots {
    const val INPUT_ROWS = 3
    const val INPUT_X = 38
    const val INPUT_Y = 17
    const val ARROW_X = 83
    const val ARROW_Y = 35
    const val OUTPUT_X = 120
    const val OUTPUT_Y = 35
}
