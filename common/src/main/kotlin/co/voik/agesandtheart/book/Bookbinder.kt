package co.voik.agesandtheart.book

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.util.StringRepresentable
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** How far a bookbinder's clamps stand apart: wide for a descriptive book or none, narrow for a linking book. */
enum class BinderClamps(private val key: String) : StringRepresentable {
    WIDE("wide"),
    NARROW("narrow"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        fun around(book: ItemStack): BinderClamps =
            if (!book.isEmpty && BookBinding.of(book) == BookBinding.THIN) NARROW else WIDE
    }
}

/**
 * The bookbinder: a press holding one descriptive or linking book, spine up between its clamps, where its cover is
 * put on, taken off and laid out, and its name given. Its screen is a loom's; the book stays in the press when the
 * screen is shut, so how it looks can be judged on the press itself.
 */
class BookbinderBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CLAMPS, BinderClamps.WIDE))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, CLAMPS)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BookbinderBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val binder = level.getBlockEntity(pos) as? BookbinderBlockEntity ?: return InteractionResult.FAIL
        player.openMenu(binder)
        return InteractionResult.CONSUME
    }

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val CLAMPS: EnumProperty<BinderClamps> = EnumProperty.create("clamps", BinderClamps::class.java)

        /** The board, and the clamps and rails standing on it; the book above them is let through. */
        private val SHAPE: VoxelShape = Shapes.or(box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0), box(1.0, 2.0, 1.0, 15.0, 7.0, 15.0))
    }
}

/**
 * The book in a bookbinder. Every change to it — put in, taken out, covered, laid out, named — is told to the clients
 * near, so the press shows the book as it now is, and moves the clamps to fit it.
 */
class BookbinderBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Bookbinder.ENTITY, pos, state), Container, MenuProvider {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    val book: ItemStack get() = items[BOOK]

    override fun setChanged() {
        super.setChanged()
        val level = level ?: return
        if (level.isClientSide) return
        val clamped = blockState.setValue(BookbinderBlock.CLAMPS, BinderClamps.around(book))
        if (clamped != blockState) level.setBlock(blockPos, clamped, Block.UPDATE_ALL)
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun getContainerSize(): Int = SLOT_COUNT

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, count: Int): ItemStack =
        ContainerHelper.removeItem(items, slot, count).also { if (!it.isEmpty) setChanged() }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, itemStack: ItemStack) {
        items[slot] = itemStack
        itemStack.limitSize(getMaxStackSize(itemStack))
        setChanged()
    }

    override fun getMaxStackSize(): Int = 1

    override fun canPlaceItem(slot: Int, itemStack: ItemStack): Boolean = LecternBooks.isOurs(itemStack)

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() {
        items.clear()
    }

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        BookbinderMenu(containerId, inventory, this)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
    }

    companion object {
        const val BOOK = 0
        const val SLOT_COUNT = 1
    }
}

/** The bookbinder's registrations, for [co.voik.agesandtheart.content.AgeContent]'s lists. */
object Bookbinder {
    val ID: Identifier = "bookbinder".location()

    private const val STRENGTH = 2.5f

    val BLOCK: BookbinderBlock = BookbinderBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.WOOD)
            .strength(STRENGTH)
            .sound(SoundType.WOOD)
            .noOcclusion()
            .ignitedByLava(),
    )

    val ITEM: Item = BlockItem(BLOCK, Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix())

    val ENTITY: BlockEntityType<BookbinderBlockEntity> =
        BlockEntityType({ pos, state -> BookbinderBlockEntity(pos, state) }, setOf(BLOCK))

    val MENU: MenuType<BookbinderMenu> = MenuType(
        { containerId, inventory -> BookbinderMenu(containerId, inventory) },
        FeatureFlags.VANILLA_SET,
    )
}
