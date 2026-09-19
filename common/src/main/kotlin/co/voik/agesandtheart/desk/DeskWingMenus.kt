package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * A wing of the desk, opened on its own — the ink case above one bookshelf, the supply bin above the other.
 *
 * **Their own menus rather than tabs of the desk's** (Jonah, 2026-08-05). The screen ran out of room because
 * everything wanted the centre; the desk is already three blocks wide and its wings are real blocks with
 * their own click target, so the cheapest room available was the room already there. A wing answers one
 * question, which is what makes it a small class rather than a fourth tab of a large one.
 *
 * **Both wings take anything the desk understands.** They differ in what they *show*, never in what they
 * accept: the stores behind them are one set, and refusing paper at the ink case would be pedantry a player
 * has to learn rather than a rule that protects anything.
 */
abstract class DeskWingMenu(
    type: MenuType<*>,
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
) : AbstractContainerMenu(type, containerId) {

    private val owner: Player = playerInventory.player

    /** The doorway. One slot, because [DeskIntake] decides where what lands in it belongs. */
    private val intake: Container = SimpleContainer(1)

    init {
        addSlot(object : Slot(intake, 0, DeskSlots.WING_INTAKE_X, DeskSlots.WING_INTAKE_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = DeskIntake.accepts(stack)
        })
        for (row in 0 until DeskSlots.INVENTORY_ROWS) {
            for (column in 0 until DeskSlots.INVENTORY_COLUMNS) {
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
        for (column in 0 until DeskSlots.INVENTORY_COLUMNS) {
            addSlot(
                Slot(playerInventory, column, DeskSlots.INVENTORY_X + column * SLOT_PITCH, DeskSlots.WING_HOTBAR_Y),
            )
        }
    }

    /**
     * Anything left in the doorway is swallowed here, for the reason `WritersDeskMenu` records:
     * `SimpleContainer.setChanged()` is empty, so `slotsChanged` never fires for a container the menu was
     * not explicitly wired into. `broadcastChanges` runs every tick, so the doorway empties within one.
     */
    override fun broadcastChanges() {
        val player = owner as? ServerPlayer
        if (player != null && !intake.getItem(0).isEmpty) {
            val desk = deskOf(player)
            if (desk != null) {
                val result = DeskIntake.offer(desk, intake.getItem(0))
                if (result.took) {
                    intake.setItem(0, result.remainder)
                    if (!result.returned.isEmpty && !player.inventory.add(result.returned)) {
                        player.drop(result.returned, false)
                    }
                    DeskCommands.sync(player, desk)
                }
            }
        }
        super.broadcastChanges()
    }

    /** Shift-click hands straight to the stores, so a full doorway is never a reason nothing happens. */
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
        val serverPlayer = player as? ServerPlayer ?: return ItemStack.EMPTY
        val desk = deskOf(serverPlayer) ?: return ItemStack.EMPTY
        val result = DeskIntake.offer(desk, moved)
        if (!result.took) return ItemStack.EMPTY
        slot.setByPlayer(result.remainder)
        if (!result.returned.isEmpty && !player.inventory.add(result.returned)) {
            player.drop(result.returned, false)
        }
        DeskCommands.sync(serverPlayer, desk)
        return original
    }

    override fun stillValid(player: Player): Boolean =
        access.evaluate({ level, pos -> level.getBlockState(pos).block is WritersDeskBlock }, true)

    /** Closing hands the doorway back, so a screen shut mid-transfer can never eat a stack. */
    override fun removed(player: Player) {
        super.removed(player)
        access.execute { _, _ ->
            val held = intake.removeItemNoUpdate(0)
            if (!held.isEmpty && !player.inventory.add(held)) player.drop(held, false)
        }
    }

    fun deskOf(player: ServerPlayer): WritersDeskBlockEntity? =
        access.evaluate(
            { level, pos -> Optional.ofNullable(WritersDeskBlock.entityAt(level, pos)) },
            Optional.empty(),
        ).orElse(null)

    companion object {
        private const val SLOT_PITCH = 18
        private const val INTAKE_SLOT = 0

        /** Our one slot comes first, so everything from here is the player's. */
        private const val FIRST_PLAYER_SLOT = 1
    }
}

/** The ink: three tanks by grade, and a doorway to pour into them. */
class InkCaseMenu(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess) :
    DeskWingMenu(AgeContent.INK_CASE_MENU, containerId, playerInventory, access) {

    companion object {
        fun open(player: ServerPlayer, centre: BlockPos) {
            player.openMenu(WingMenuProvider(centre, WingKind.INK_CASE))
        }
    }
}

/** The paper and the binding material: what a book is made of, as opposed to what it says. */
class SupplyBinMenu(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess) :
    DeskWingMenu(AgeContent.SUPPLY_BIN_MENU, containerId, playerInventory, access) {

    companion object {
        fun open(player: ServerPlayer, centre: BlockPos) {
            player.openMenu(WingMenuProvider(centre, WingKind.SUPPLY_BIN))
        }
    }
}

/** Which wing a provider opens — the only thing the two have that differs on the server. */
enum class WingKind {
    INK_CASE,
    SUPPLY_BIN,
}

/**
 * Opens a wing against the **centre**, never against the wing's own position.
 *
 * The stores live on the centre's block entity, so a menu anchored at the wing would fail `stillValid` the
 * moment it looked for one — and `ContainerLevelAccess` is also what a broken desk closes the screen through.
 */
private class WingMenuProvider(private val centre: BlockPos, private val kind: WingKind) : MenuProvider {
    override fun getDisplayName(): Component = Component.translatable(
        when (kind) {
            WingKind.INK_CASE -> "container.agesandtheart.ink_case"
            WingKind.SUPPLY_BIN -> "container.agesandtheart.supply_bin"
        },
    )

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu {
        val access = ContainerLevelAccess.create(player.level(), centre)
        return when (kind) {
            WingKind.INK_CASE -> InkCaseMenu(containerId, inventory, access)
            WingKind.SUPPLY_BIN -> SupplyBinMenu(containerId, inventory, access)
        }
    }
}
