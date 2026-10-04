package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.portal.LinkingPortals
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult

/**
 * Copies a bound linking book into a blank one, leaving the original as it was (design §7.4).
 *
 * A cartography table's way of working: the block holds nothing, the books sit in the screen while it is
 * open and go back to the player when it shuts, and taking the copy is what spends the blank.
 */
class TranscriberBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        player.openMenu(
            SimpleMenuProvider(
                { containerId, inventory, _ -> TranscriberMenu(containerId, inventory, ContainerLevelAccess.create(level, pos)) },
                name,
            ),
        )
        return InteractionResult.CONSUME
    }
}

class TranscriberMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
) : AbstractContainerMenu(Transcriber.MENU, containerId) {

    /** The client's; the server's carries the block's position. */
    constructor(containerId: Int, playerInventory: Inventory) : this(containerId, playerInventory, ContainerLevelAccess.NULL)

    private val books: Container = object : SimpleContainer(INPUT_SLOTS) {
        override fun setChanged() {
            super.setChanged()
            slotsChanged(this)
        }
    }

    private val result = ResultContainer()

    private var lastSoundTime = 0L

    init {
        addSlot(object : Slot(books, ORIGINAL, TranscriberSlots.ORIGINAL_X, TranscriberSlots.ORIGINAL_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = LinkingPortals.isBoundLinkingBook(stack)
        })
        addSlot(object : Slot(books, BLANK, TranscriberSlots.BLANK_X, TranscriberSlots.BLANK_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = isBlankLinkingBook(stack)
        })
        addSlot(object : Slot(result, 0, TranscriberSlots.COPY_X, TranscriberSlots.COPY_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = false

            override fun onTake(player: Player, carried: ItemStack) {
                books.removeItem(BLANK, 1)
                playTranscribedSound()
                super.onTake(player, carried)
            }
        })
        addStandardInventorySlots(playerInventory, DeskSlots.INVENTORY_X, DeskSlots.WING_INVENTORY_Y)
    }

    /** Whether there is a copy waiting to be taken, which is what fills the arrow. */
    val hasCopy: Boolean get() = !slots[COPY].item.isEmpty

    override fun slotsChanged(container: Container) {
        super.slotsChanged(container)
        access.execute { _, _ -> offerCopy() }
    }

    private fun offerCopy() {
        val original = books.getItem(ORIGINAL)
        val canCopy = LinkingPortals.isBoundLinkingBook(original) && isBlankLinkingBook(books.getItem(BLANK))
        val copy = if (canCopy) original.copyWithCount(1) else ItemStack.EMPTY
        if (!ItemStack.matches(copy, result.getItem(0))) result.setItem(0, copy)
        broadcastChanges()
    }

    private fun playTranscribedSound() = access.execute { level, pos ->
        if (lastSoundTime == level.gameTime) return@execute
        level.playSound(null, pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1f, 1f)
        lastSoundTime = level.gameTime
    }

    override fun canTakeItemForPickAll(carried: ItemStack, target: Slot): Boolean =
        target.container !== result && super.canTakeItemForPickAll(carried, target)

    /** The copy and the books go to the inventory; a book from the inventory goes to the slot it fits. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        val isTheTranscribers = index < FIRST_PLAYER_SLOT
        val landed = when {
            isTheTranscribers -> moveItemStackTo(moved, FIRST_PLAYER_SLOT, slots.size, true)
            LinkingPortals.isBoundLinkingBook(moved) -> moveItemStackTo(moved, ORIGINAL, ORIGINAL + 1, false)
            isBlankLinkingBook(moved) -> moveItemStackTo(moved, BLANK, BLANK + 1, false)
            else -> false
        }
        if (!landed) return ItemStack.EMPTY
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        slot.onTake(player, moved)
        return original
    }

    override fun stillValid(player: Player): Boolean = stillValid(access, player, Transcriber.BLOCK)

    override fun removed(player: Player) {
        super.removed(player)
        result.removeItemNoUpdate(0)
        access.execute { _, _ -> clearContainer(player, books) }
    }

    private companion object {
        const val ORIGINAL = 0
        const val BLANK = 1
        const val INPUT_SLOTS = 2
        const val COPY = 2
        const val FIRST_PLAYER_SLOT = 3

        fun isBlankLinkingBook(stack: ItemStack): Boolean =
            stack.item === AgeContent.LINKING_BOOK && stack.get(AgeComponents.LINK_TARGET) == null
    }
}

/** Where the transcriber's slots and arrow sit — a furnace's places, stated once for the menu and the screen. */
object TranscriberSlots {
    const val ORIGINAL_X = 56
    const val ORIGINAL_Y = 17
    const val BLANK_X = 56
    const val BLANK_Y = 53
    const val ARROW_X = 79
    const val ARROW_Y = 34
    const val COPY_X = 116
    const val COPY_Y = 35
}

/** The transcriber's registrations, for [AgeContent]'s lists. */
object Transcriber {
    val ID: Identifier = "transcriber".location()

    val BLOCK: TranscriberBlock = TranscriberBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.WOOD)
            .strength(AgeContent.STUDY_STRENGTH)
            .sound(SoundType.WOOD)
            .ignitedByLava(),
    )

    val ITEM: Item = BlockItem(BLOCK, Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix())

    val MENU: MenuType<TranscriberMenu> = MenuType(
        { containerId, inventory -> TranscriberMenu(containerId, inventory) },
        FeatureFlags.VANILLA_SET,
    )
}
