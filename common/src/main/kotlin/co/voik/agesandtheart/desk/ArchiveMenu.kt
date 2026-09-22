package co.voik.agesandtheart.desk

import net.minecraft.util.Prediction
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.page.PageLearning
import co.voik.agesandtheart.platform.Services
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * An archive's screen: the player's inventory in real slots, the pages on [ArchiveSyncPayload].
 *
 * The pages have no slot shape — an unbounded count per word — so they travel as a payload, sent whenever
 * they differ from what this viewer was last sent. Checked on every broadcast rather than on each action,
 * because a hopper or a second writer changes an archive with nobody's screen having asked.
 */
class ArchiveMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
) : AbstractContainerMenu(AgeContent.ARCHIVE_MENU, containerId) {

    /** Set on the server when the menu is opened; the client's copy has nobody to send to. */
    var viewer: ServerPlayer? = null
        private set

    private var lastSent: PageArchive? = null

    init {
        for (row in 0 until INVENTORY_ROWS) {
            for (column in 0 until INVENTORY_COLUMNS) {
                addSlot(
                    Slot(
                        playerInventory,
                        column + row * INVENTORY_COLUMNS + INVENTORY_COLUMNS,
                        INVENTORY_X + column * SLOT,
                        INVENTORY_Y + row * SLOT,
                    ),
                )
            }
        }
        for (column in 0 until INVENTORY_COLUMNS) {
            addSlot(Slot(playerInventory, column, INVENTORY_X + column * SLOT, HOTBAR_Y))
        }
    }

    fun archiveOf(): ArchiveBlockEntity? =
        access.evaluate(
            { level, pos -> Optional.ofNullable(level.getBlockEntity(pos) as? ArchiveBlockEntity) },
            Optional.empty(),
        ).orElse(null)

    /** Shift-clicking a page or a notebook files it; anything else stays where it is. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem() || player !is ServerPlayer) return ItemStack.EMPTY
        val archive = archiveOf() ?: return ItemStack.EMPTY
        val moved = slot.item
        val result = ArchiveIntake.offer(archive, moved)
        if (!result.took) return ItemStack.EMPTY
        slot.setByPlayer(result.remainder)
        if (!result.returned.isEmpty) player.inventory.placeItemBackInInventory(result.returned, Prediction.SERVER_ONLY)
        // Nothing more to move: returning the stack would have vanilla ask again for the same slot.
        return ItemStack.EMPTY
    }

    override fun broadcastChanges() {
        super.broadcastChanges()
        val viewer = viewer ?: return
        val pages = archiveOf()?.pages ?: return
        if (pages == lastSent) return
        lastSent = pages
        // Opening the archive is reading it (design §4.5), and so is watching a page arrive in it.
        PageLearning.teach(viewer, pages.words)
        Services.NETWORK.sendToPlayer(viewer, ArchiveSyncPayload(pages))
    }

    override fun stillValid(player: Player): Boolean = stillValid(access, player, AgeContent.ARCHIVE_BLOCK)

    companion object {
        const val PANEL_WIDTH = 176
        const val PANEL_HEIGHT = 222
        const val SLOT = 18
        const val INVENTORY_COLUMNS = 9
        const val INVENTORY_ROWS = 3
        const val INVENTORY_X = 8

        /** The distance a chest leaves below its inventory's first row, so this reads like every container. */
        const val INVENTORY_Y = PANEL_HEIGHT - 83
        const val HOTBAR_DROP = INVENTORY_ROWS * SLOT + 4
        const val HOTBAR_Y = INVENTORY_Y + HOTBAR_DROP
        const val INVENTORY_LABEL_Y = INVENTORY_Y - 11

        fun open(player: ServerPlayer, archive: ArchiveBlockEntity) {
            val level = archive.level ?: return
            player.openMenu(
                SimpleMenuProvider(
                    { containerId, inventory, _ ->
                        ArchiveMenu(containerId, inventory, ContainerLevelAccess.create(level, archive.blockPos))
                            .also { it.viewer = player }
                    },
                    Component.translatable("container.agesandtheart.archive"),
                ),
            )
        }
    }
}
