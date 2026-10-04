package co.voik.agesandtheart.station

import co.voik.agesandtheart.content.AgeContent
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
 * A station's screen, laid out as a furnace's without the fuel: an input, an arrow, a result.
 *
 * The slots are the block entity's own container, so a hopper and a hand see one set of stacks. How far
 * the run is travels on two data slots, as a furnace's does.
 */
class StationMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val station: Container,
    private val progress: ContainerData,
) : AbstractContainerMenu(AgeContent.STATION_MENU, containerId), ListsItsRecipes {

    override var listed: List<ListedRecipe> = emptyList()

    override fun recipesOn(server: MinecraftServer): List<MachineRecipe> {
        val machine = (station as? StationBlockEntity)?.station ?: return emptyList()
        return MachineRecipeLists.of(server, StationRecipes.typeFor(machine)) { MachineRecipe(it.input(), it.shownResult()) }
    }

    /** The client's, which vanilla's data syncing fills in. */
    constructor(containerId: Int, playerInventory: Inventory) :
        this(containerId, playerInventory, SimpleContainer(StationBlockEntity.SLOT_COUNT), SimpleContainerData(DATA_COUNT))

    init {
        addSlot(object : Slot(station, StationBlockEntity.INPUT, StationSlots.INPUT_X, StationSlots.INPUT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = station.canPlaceItem(StationBlockEntity.INPUT, stack)
        })
        addSlot(object : Slot(station, StationBlockEntity.OUTPUT, StationSlots.OUTPUT_X, StationSlots.OUTPUT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = false
        })
        addStandardInventorySlots(playerInventory, DeskSlots.INVENTORY_X, DeskSlots.WING_INVENTORY_Y)
        addDataSlots(progress)
    }

    /** How far through its run the station is, from nothing to done. */
    val share: Float get() {
        val total = progress.get(WORK_TICKS)
        return if (total <= 0) 0f else progress.get(PROGRESS).toFloat() / total
    }

    /** The result or the input goes to the inventory; anything from the inventory goes to the input. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        val isTheStationsOwn = index < FIRST_PLAYER_SLOT
        val landed = if (isTheStationsOwn) {
            moveItemStackTo(moved, FIRST_PLAYER_SLOT, slots.size, true)
        } else {
            moveItemStackTo(moved, StationBlockEntity.INPUT, StationBlockEntity.INPUT + 1, false)
        }
        if (!landed) return ItemStack.EMPTY
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = station.stillValid(player)

    companion object {
        const val PROGRESS = 0
        const val WORK_TICKS = 1
        const val DATA_COUNT = 2

        private const val FIRST_PLAYER_SLOT = StationBlockEntity.SLOT_COUNT
    }
}

/** Where a station's slots and arrow sit — a furnace's places, stated once for the menu and the screen. */
object StationSlots {
    const val INPUT_X = 56
    const val INPUT_Y = 35
    const val OUTPUT_X = 116
    const val OUTPUT_Y = 35
    const val ARROW_X = 79
    const val ARROW_Y = 34
}
