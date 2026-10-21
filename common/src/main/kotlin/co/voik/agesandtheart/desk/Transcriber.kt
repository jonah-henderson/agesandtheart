package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.portal.LinkingPortals
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult

/**
 * Copies a bound linking book into another book, leaving the original as it was (design §7.4). The book written
 * over is spent: a blank or bound linking book, a plain book, or a notebook. It takes a furnace's way of working, one
 * copy at a time; hoppers feed books in from above and the sides and take the copies out from below.
 */
class TranscriberBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = TranscriberBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, Transcriber.ENTITY) { tickLevel, pos, _, transcriber ->
            (tickLevel as? ServerLevel)?.let { transcriber.transcribe(it, pos) }
        }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val transcriber = level.getBlockEntity(pos) as? TranscriberBlockEntity ?: return InteractionResult.FAIL
        player.openMenu(transcriber)
        return InteractionResult.CONSUME
    }
}

class TranscriberBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Transcriber.ENTITY, pos, state), WorldlyContainer, MenuProvider {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    /** How long the copy under way has been written for, in ticks watched. */
    private var written = 0

    fun transcribe(level: ServerLevel, pos: BlockPos) {
        if ((level.gameTime + pos.asLong()) % LOOKS_EVERY != 0L) return
        if (!canCopyNow()) return startAgain()
        written = (written + LOOKS_EVERY.toInt()).coerceAtMost(COPY_TIME)
        if (written >= COPY_TIME) finish(level, pos)
        setChanged()
    }

    private fun canCopyNow(): Boolean {
        val hasAnOriginal = LinkingPortals.isBoundLinkingBook(items[ORIGINAL])
        val hasABookToWriteOver = Transcriber.canBeWrittenOver(items[DESTINATION])
        return hasAnOriginal && hasABookToWriteOver && items[COPY].isEmpty
    }

    private fun finish(level: ServerLevel, pos: BlockPos) {
        items[COPY] = items[ORIGINAL].copyWithCount(1)
        items[DESTINATION].shrink(1)
        written = 0
        level.playSound(null, pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1f, 1f)
    }

    private fun startAgain() {
        if (written == 0) return
        written = 0
        setChanged()
    }

    override fun getContainerSize(): Int = SLOT_COUNT

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, count: Int): ItemStack =
        ContainerHelper.removeItem(items, slot, count).also { if (!it.isEmpty) setChanged() }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    /** Another original starts the copy again; the next book to write over does not. */
    override fun setItem(slot: Int, itemStack: ItemStack) {
        val isAnotherOriginal = slot == ORIGINAL && !ItemStack.isSameItemSameComponents(items[slot], itemStack)
        items[slot] = itemStack
        itemStack.limitSize(getMaxStackSize(itemStack))
        if (isAnotherOriginal) written = 0
        setChanged()
    }

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        TranscriberMenu(containerId, inventory, this, progress)

    /** Read live, so it needs no syncing of its own. */
    private val progress = object : ContainerData {
        override fun get(index: Int): Int = if (index == TranscriberMenu.TOTAL) COPY_TIME else written

        override fun set(index: Int, value: Int) = Unit

        override fun getCount(): Int = TranscriberMenu.DATA_COUNT
    }

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() {
        items.clear()
        written = 0
    }

    override fun canPlaceItem(slot: Int, itemStack: ItemStack): Boolean = when (slot) {
        ORIGINAL -> LinkingPortals.isBoundLinkingBook(itemStack)
        DESTINATION -> Transcriber.canBeWrittenOver(itemStack)
        else -> false
    }

    override fun getSlotsForFace(direction: Direction): IntArray = if (direction == Direction.DOWN) COPY_SLOTS else DESTINATION_SLOTS

    /** The original goes in by hand, so a hopper never takes one somebody meant to keep. */
    override fun canPlaceItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction?): Boolean =
        direction != Direction.DOWN && slot == DESTINATION && canPlaceItem(slot, itemStack)

    override fun canTakeItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction): Boolean =
        direction == Direction.DOWN && slot == COPY

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        written = input.getIntOr(WRITTEN_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putInt(WRITTEN_KEY, written)
    }

    companion object {
        const val ORIGINAL = 0
        const val DESTINATION = 1
        const val COPY = 2
        const val SLOT_COUNT = 3

        /** The drying rack's time, twice a furnace's 200 ticks an item (Jonah). */
        const val COPY_TIME = 400

        private val DESTINATION_SLOTS = intArrayOf(DESTINATION)
        private val COPY_SLOTS = intArrayOf(COPY)

        /** Often enough that the arrow moves smoothly. */
        private const val LOOKS_EVERY = 4L

        private const val WRITTEN_KEY = "written"
    }
}

class TranscriberMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val transcriber: Container,
    private val progress: ContainerData,
) : AbstractContainerMenu(Transcriber.MENU, containerId) {

    /** The client's, which vanilla's data syncing fills in. */
    constructor(containerId: Int, playerInventory: Inventory) :
        this(containerId, playerInventory, SimpleContainer(TranscriberBlockEntity.SLOT_COUNT), SimpleContainerData(DATA_COUNT))

    init {
        addSlot(transcriberSlot(TranscriberBlockEntity.ORIGINAL, TranscriberSlots.ORIGINAL_X, TranscriberSlots.ORIGINAL_Y))
        addSlot(transcriberSlot(TranscriberBlockEntity.DESTINATION, TranscriberSlots.DESTINATION_X, TranscriberSlots.DESTINATION_Y))
        addSlot(transcriberSlot(TranscriberBlockEntity.COPY, TranscriberSlots.COPY_X, TranscriberSlots.COPY_Y))
        addStandardInventorySlots(playerInventory, DeskSlots.INVENTORY_X, DeskSlots.WING_INVENTORY_Y)
        addDataSlots(progress)
    }

    private fun transcriberSlot(index: Int, x: Int, y: Int): Slot = object : Slot(transcriber, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = transcriber.canPlaceItem(index, stack)
    }

    val destinationSlot: Slot get() = slots[TranscriberBlockEntity.DESTINATION]

    /** How far the copy under way has got, from nothing to done. */
    val progressFraction: Float
        get() {
            val total = progress.get(TOTAL)
            return if (total <= 0) 0f else progress.get(WRITTEN).toFloat() / total
        }

    /**
     * The copy and the books go to the inventory. From the inventory, a bound book is the original where there is
     * none yet, and any book that can be written over goes under it.
     */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        val isTheTranscribers = index < FIRST_PLAYER_SLOT
        fun intoSlot(target: Int) = moveItemStackTo(moved, target, target + 1, false)
        val isAnOriginal = LinkingPortals.isBoundLinkingBook(moved)
        val landed = when {
            isTheTranscribers -> moveItemStackTo(moved, FIRST_PLAYER_SLOT, slots.size, true)
            isAnOriginal && !slots[TranscriberBlockEntity.ORIGINAL].hasItem() -> intoSlot(TranscriberBlockEntity.ORIGINAL)
            Transcriber.canBeWrittenOver(moved) -> intoSlot(TranscriberBlockEntity.DESTINATION)
            else -> false
        }
        if (!landed) return ItemStack.EMPTY
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = transcriber.stillValid(player)

    companion object {
        const val WRITTEN = 0
        const val TOTAL = 1
        const val DATA_COUNT = 2

        private const val FIRST_PLAYER_SLOT = TranscriberBlockEntity.SLOT_COUNT
    }
}

/** Where the transcriber's slots and arrow sit — a furnace's places, stated once for the menu and the screen. */
object TranscriberSlots {
    const val ORIGINAL_X = 56
    const val ORIGINAL_Y = 17
    const val DESTINATION_X = 56
    const val DESTINATION_Y = 53
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

    val ITEM: Item = DeskImplementItem(BLOCK, Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix())

    val ENTITY: BlockEntityType<TranscriberBlockEntity> =
        BlockEntityType({ pos, state -> TranscriberBlockEntity(pos, state) }, setOf(BLOCK))

    val MENU: MenuType<TranscriberMenu> = MenuType(
        { containerId, inventory -> TranscriberMenu(containerId, inventory) },
        FeatureFlags.VANILLA_SET,
    )

    /** What a copy may be written into, shown in turn in the empty slot. */
    val WRITTEN_OVER: List<Item> by lazy { listOf(AgeContent.LINKING_BOOK, Items.BOOK, AgeContent.NOTEBOOK) }

    /** A linking book, blank or bound, a plain book, or a notebook. */
    fun canBeWrittenOver(stack: ItemStack): Boolean = WRITTEN_OVER.any { stack.item === it }
}
