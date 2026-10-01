package co.voik.agesandtheart.station

import co.voik.agesandtheart.desk.DeskSlots
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
 * The compounder's screen: four inputs in a square, an arrow, and the result, over the player's inventory.
 * What the inputs' recipe still lacks beside the machine travels on one data slot.
 */
class CompounderMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val compounder: Container,
    private val lacking: ContainerData,
) : AbstractContainerMenu(Compounder.MENU, containerId) {

    /** The client's, which vanilla's data syncing fills in. */
    constructor(containerId: Int, playerInventory: Inventory) :
        this(containerId, playerInventory, SimpleContainer(CompounderBlockEntity.SLOT_COUNT), SimpleContainerData(DATA_COUNT))

    init {
        for (slot in 0..<CompounderBlockEntity.INPUT_SLOTS) {
            val column = slot % CompounderSlots.INPUT_COLUMNS
            val row = slot / CompounderSlots.INPUT_COLUMNS
            addSlot(Slot(compounder, slot, CompounderSlots.INPUT_X + column * SLOT_PITCH, CompounderSlots.INPUT_Y + row * SLOT_PITCH))
        }
        addSlot(object : Slot(compounder, CompounderBlockEntity.RESULT, CompounderSlots.RESULT_X, CompounderSlots.RESULT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = false
        })
        addStandardInventorySlots(playerInventory, DeskSlots.INVENTORY_X, DeskSlots.WING_INVENTORY_Y)
        addDataSlots(lacking)
    }

    /** What the inputs' recipe lacks beside the machine; empty while the inputs make nothing. */
    val missingNeeds: List<CompounderNeed>
        get() = CompounderNeed.entries.filter { need -> lacking.get(MISSING_NEEDS) and (1 shl need.ordinal) != 0 }

    val hasAResult: Boolean get() = slots[CompounderBlockEntity.RESULT].hasItem()

    /**
     * The result goes to the inventory, as many times over as the inputs and the inventory allow — a
     * crafting table's shift-click. An input goes back to the inventory; anything else to the inputs.
     */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        if (index == CompounderBlockEntity.RESULT) return compoundIntoTheInventory(slot)
        val moved = slot.item
        val original = moved.copy()
        val isAnInput = index < CompounderBlockEntity.INPUT_SLOTS
        val landed = if (isAnInput) {
            moveItemStackTo(moved, FIRST_PLAYER_SLOT, slots.size, true)
        } else {
            moveItemStackTo(moved, 0, CompounderBlockEntity.INPUT_SLOTS, false)
        }
        if (!landed) return ItemStack.EMPTY
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    private fun compoundIntoTheInventory(result: Slot): ItemStack {
        var first = ItemStack.EMPTY
        repeat(MOST_AT_A_SHIFT_CLICK) {
            val next = result.item
            if (next.isEmpty || !inventoryHasRoomFor(next)) return first
            val made = result.remove(next.count)
            if (made.isEmpty) return first
            moveItemStackTo(made, FIRST_PLAYER_SLOT, slots.size, true)
            if (first.isEmpty) first = made.copy()
        }
        return first
    }

    private fun inventoryHasRoomFor(stack: ItemStack): Boolean =
        slots.subList(FIRST_PLAYER_SLOT, slots.size).any { slot ->
            val isEmpty = !slot.hasItem()
            val stacksOn = ItemStack.isSameItemSameComponents(slot.item, stack) && slot.item.count + stack.count <= slot.item.maxStackSize
            isEmpty || stacksOn
        }

    override fun stillValid(player: Player): Boolean = compounder.stillValid(player)

    companion object {
        const val MISSING_NEEDS = 0
        const val DATA_COUNT = 1

        private const val FIRST_PLAYER_SLOT = CompounderBlockEntity.SLOT_COUNT
        private const val SLOT_PITCH = 18

        /** Enough for a stack of anything, and a stop on a loop that could otherwise run on. */
        private const val MOST_AT_A_SHIFT_CLICK = 64
    }
}

/** Where the compounder's slots and arrow sit — stated once for the menu and the screen. */
object CompounderSlots {
    const val INPUT_COLUMNS = 2
    const val INPUT_X = 38
    const val INPUT_Y = 17
    const val ARROW_X = 83
    const val ARROW_Y = 25
    const val RESULT_X = 120
    const val RESULT_Y = 26

    /** Under the inputs and over the inventory's label, across the panel. */
    const val STATUS_Y = 60
}
