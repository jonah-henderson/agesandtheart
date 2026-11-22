package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Prediction
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * An empty grammar guide's screen: one slot whose contents are studied at once, and how far the guide is studied,
 * synced as two data slots (points studied, points to fill it).
 */
class GrammarStudyMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
    private val progress: ContainerData,
) : AbstractContainerMenu(AgeContent.GRAMMAR_STUDY_MENU, containerId) {

    constructor(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess) :
        this(containerId, playerInventory, access, SimpleContainerData(PROGRESS_SLOTS))

    private val intake: Container = SimpleContainer(1)

    /** How far the guide is studied, from 0 to 1. */
    val studied: Float get() = progress.get(STUDIED).toFloat() / progress.get(FULL).coerceAtLeast(1)

    val pointsStudied: Int get() = progress.get(STUDIED)

    val pointsToFill: Int get() = progress.get(FULL)

    init {
        addSlot(object : Slot(intake, 0, DeskSlots.WING_INTAKE_X, DeskSlots.WING_INTAKE_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = mayStudy(stack)
        })
        for (row in 0..<DeskSlots.INVENTORY_ROWS) {
            for (column in 0..<DeskSlots.INVENTORY_COLUMNS) {
                addSlot(
                    Slot(
                        playerInventory,
                        column + row * DeskSlots.INVENTORY_COLUMNS + DeskSlots.INVENTORY_COLUMNS,
                        DeskSlots.INVENTORY_X + column * SLOT_PITCH,
                        DeskSlots.WING_INVENTORY_Y + row * SLOT_PITCH,
                    ),
                )
            }
        }
        for (column in 0..<DeskSlots.INVENTORY_COLUMNS) {
            addSlot(Slot(playerInventory, column, DeskSlots.INVENTORY_X + column * SLOT_PITCH, DeskSlots.WING_HOTBAR_Y))
        }
        addDataSlots(progress)
    }

    /** What is in the slot is studied within a tick: `SimpleContainer` tells nobody it changed (see `DeskWingMenu`). */
    override fun broadcastChanges() {
        val held = intake.getItem(0)
        if (!held.isEmpty) intake.setItem(0, guide()?.study(held) ?: held)
        super.broadcastChanges()
    }

    /** Shift-click studies straight from the inventory. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        if (index == INTAKE_SLOT) {
            if (!moveItemStackTo(moved, FIRST_PLAYER_SLOT, slots.size, true)) return ItemStack.EMPTY
            if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
            return original
        }
        if (!mayStudy(moved)) return ItemStack.EMPTY
        val guide = guide() ?: return ItemStack.EMPTY
        val left = guide.study(moved)
        if (left.count == moved.count) return ItemStack.EMPTY
        slot.setByPlayer(left)
        return ItemStack.EMPTY
    }

    /** Studied full, the block is the grammar guide and this screen closes. */
    override fun stillValid(player: Player): Boolean =
        access.evaluate({ level, pos -> level.getBlockState(pos).block is EmptyGrammarGuideBlock }, true)

    /** Closing hands back what the slot still holds, as when the guide filled with some of a stack left over. */
    override fun removed(player: Player) {
        super.removed(player)
        access.execute { _, _ ->
            val held = intake.removeItemNoUpdate(0)
            if (!held.isEmpty) player.inventory.placeItemBackInInventory(held, Prediction.SERVER_ONLY)
        }
    }

    private fun guide(): EmptyGrammarGuideBlockEntity? = access.evaluate(
        { level, pos -> Optional.ofNullable(level.getBlockEntity(pos) as? EmptyGrammarGuideBlockEntity) },
        Optional.empty(),
    ).orElse(null)

    companion object {
        private const val SLOT_PITCH = 18
        private const val INTAKE_SLOT = 0
        private const val FIRST_PLAYER_SLOT = 1
        private const val PROGRESS_SLOTS = 2
        private const val STUDIED = 0
        private const val FULL = 1

        /** Pages and survey reports; what each is worth is the server's (`GrammarStudy`). */
        fun mayStudy(stack: ItemStack): Boolean = NotebookItem.isPage(stack) || stack.item in AgeContent.SURVEY_REPORTS.values

        /** The server's menu, reading the guide at [access] live. */
        fun serving(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess): GrammarStudyMenu {
            val live = object : ContainerData {
                override fun get(index: Int): Int = access.evaluate({ level, pos ->
                    when (index) {
                        STUDIED -> (level.getBlockEntity(pos) as? EmptyGrammarGuideBlockEntity)?.studied ?: 0
                        else -> (level as? ServerLevel)?.let { GrammarStudy.of(it.server).full } ?: 0
                    }
                }, 0)

                override fun set(index: Int, value: Int) = Unit

                override fun getCount(): Int = PROGRESS_SLOTS
            }
            return GrammarStudyMenu(containerId, playerInventory, access, live)
        }
    }
}
