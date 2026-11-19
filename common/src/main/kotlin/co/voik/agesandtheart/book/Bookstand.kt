package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.WoodKind
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
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
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.floor

/**
 * The places along a bookstand between its guards, counted from the left of whoever faces its spines, each as wide as a
 * linking book; a descriptive book is twice as thick and stands in two, the pair its first place is the even one of.
 */
object BookstandPlaces {
    const val COUNT = 6

    /** Where the first place begins and how wide each is, in pixels across the stand from its left end. */
    private const val FIRST_EDGE = 2.0
    private const val WIDTH = 2.0

    private const val PIXELS_A_BLOCK = 16.0
    private const val HALF = 0.5

    /** How many places [book] stands in. */
    fun widthOf(book: ItemStack): Int = if (BookBinding.of(book) == BookBinding.THIN) 1 else 2

    /** The place [across] pixels from the stand's left end is over, or the nearest one. */
    fun placeAt(across: Double): Int = floor((across - FIRST_EDGE) / WIDTH).toInt().coerceIn(0, COUNT - 1)

    /** The first place a book [width] places wide fills, put in at [place]. */
    fun firstOf(place: Int, width: Int): Int = place - place % width

    /** Pixels across from the stand's left end to the middle of a book [width] places wide from [first]. */
    fun middleOf(first: Int, width: Int): Double = FIRST_EDGE + WIDTH * (first + width / 2.0)

    /** How far across a stand whose spines face [facing] the point [inTheBlock] is, in pixels from its left end. */
    fun acrossOf(facing: Direction, inTheBlock: Vec3): Double {
        // Facing the spines, the stand's left end is clockwise of them, so the places count anticlockwise.
        val toTheRight = facing.counterClockWise
        val fromTheMiddle = (inTheBlock.x - HALF) * toTheRight.stepX + (inTheBlock.z - HALF) * toTheRight.stepZ
        return (fromTheMiddle + HALF) * PIXELS_A_BLOCK
    }
}

/**
 * A stand of books on a desk or a shelf (Jonah, 2026-11-19): a base with a guard at each end, built of any wood and
 * drawn in it, holding three descriptive books, six linking books, or as many of each as fit, upright with their spines
 * toward whoever set it down. Only books of ours stand in it. A book goes in at whichever place it is put to and comes
 * out of whichever place is used, in any order.
 */
class BookstandBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(WOOD, WoodKind.OAK))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, WOOD)
    }

    /** Its spines toward whoever places it; its wood is the item's, which [BlockItem] sets once it is placed. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack =
        Bookstands.inItsWood(super.getCloneItemStack(level, pos, state, includeData), state)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BookstandBlockEntity(pos, state)

    /** A book of ours stood in the place it is put to, where it fits; a book already there is taken instead. */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!LecternBooks.isOurs(stack)) return InteractionResult.TRY_WITH_EMPTY_HAND
        val stand = level.getBlockEntity(pos) as? BookstandBlockEntity ?: return InteractionResult.PASS
        val place = placeHit(state, pos, hitResult)
        if (stand.firstPlaceOfTheBookAt(place) != null) return InteractionResult.TRY_WITH_EMPTY_HAND
        val width = BookstandPlaces.widthOf(stack)
        val first = BookstandPlaces.firstOf(place, width)
        if (!stand.hasRoomAt(first, width)) return InteractionResult.FAIL
        if (!level.isClientSide) stand.put(first, stack.consumeAndReturn(1, player))
        return InteractionResult.SUCCESS
    }

    /** The book standing in the place used, taken. */
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val stand = level.getBlockEntity(pos) as? BookstandBlockEntity ?: return InteractionResult.PASS
        val first = stand.firstPlaceOfTheBookAt(placeHit(state, pos, hitResult)) ?: return InteractionResult.PASS
        if (!level.isClientSide) player.inventory.placeItemBackInInventory(stand.take(first), Prediction.SERVER_ONLY)
        return InteractionResult.SUCCESS
    }

    private fun placeHit(state: BlockState, pos: BlockPos, hitResult: BlockHitResult): Int {
        val inTheBlock = hitResult.location.subtract(Vec3.atLowerCornerOf(pos))
        return BookstandPlaces.placeAt(BookstandPlaces.acrossOf(state.getValue(FACING), inTheBlock))
    }

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val WOOD: EnumProperty<WoodKind> = WoodKind.PROPERTY

        /** The base, the guards, and the room between them a book stands in, facing north as the model does. */
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(
            Shapes.or(
                box(0.0, 0.0, 4.0, 16.0, 1.0, 12.0),
                box(1.0, 1.0, 6.0, 2.0, 7.0, 10.0),
                box(14.0, 1.0, 6.0, 15.0, 7.0, 10.0),
                box(2.0, 1.0, 4.0, 14.0, 9.0, 12.0),
            ),
        )
    }
}

/** A bookstand's books, each kept at the first of the places it stands in. */
class BookstandBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(Bookstands.ENTITY, pos, state) {

    private val books: NonNullList<ItemStack> = NonNullList.withSize(BookstandPlaces.COUNT, ItemStack.EMPTY)

    val facing: Direction get() = blockState.getValue(BookstandBlock.FACING)

    /** Each book standing, by the first place it stands in. */
    fun standing(): Map<Int, ItemStack> = books.withIndex().filter { !it.value.isEmpty }.associate { it.index to it.value }

    /** The first place of the book standing in [place], or null where it is empty. */
    fun firstPlaceOfTheBookAt(place: Int): Int? = standing().entries
        .firstOrNull { (first, book) -> place in first until first + BookstandPlaces.widthOf(book) }
        ?.key

    fun hasRoomAt(first: Int, width: Int): Boolean {
        val isOnTheStand = first >= 0 && first + width <= BookstandPlaces.COUNT
        return isOnTheStand && (first until first + width).all { firstPlaceOfTheBookAt(it) == null }
    }

    /** [book] stood from the place [first], which has room for it. */
    fun put(first: Int, book: ItemStack) {
        if (!hasRoomAt(first, BookstandPlaces.widthOf(book))) return
        books[first] = book
        play(SoundEvents.CHISELED_BOOKSHELF_INSERT)
        changed()
    }

    /** The book standing from the place [first], taken off the stand. */
    fun take(first: Int): ItemStack {
        val taken = books[first]
        books[first] = ItemStack.EMPTY
        play(SoundEvents.CHISELED_BOOKSHELF_PICKUP)
        changed()
        return taken
    }

    private fun play(sound: SoundEvent) {
        val level = level as? ServerLevel ?: return
        level.playSound(null, blockPos, sound, SoundSource.BLOCKS, 1.0f, 1.0f)
    }

    private fun changed() {
        setChanged()
        val level = level ?: return
        if (!level.isClientSide) level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        level?.let { Containers.dropContents(it, pos, books) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        books.clear()
        ContainerHelper.loadAllItems(input, books)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, books, true)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)
}

/** A bookstand in hand, named for its wood as a boat or a sign is; one with no wood kept is oak. */
class BookstandItem(block: Block, properties: Properties) : BlockItem(block, properties) {
    override fun getName(stack: ItemStack): Component =
        Bookstands.nameOf(stack.get(DataComponents.BLOCK_STATE)?.get(BookstandBlock.WOOD) ?: WoodKind.OAK)
}

/** The bookstand's registrations, for [co.voik.agesandtheart.content.AgeContent]'s lists. */
object Bookstands {
    val ID: Identifier = "bookstand".location()

    private const val STRENGTH = 1.5f

    val BLOCK: BookstandBlock = BookstandBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.WOOD)
            .strength(STRENGTH)
            .sound(SoundType.WOOD)
            .noOcclusion(),
    )

    val ITEM: Item = BookstandItem(BLOCK, Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix())

    val ENTITY: BlockEntityType<BookstandBlockEntity> = BlockEntityType({ pos, state -> BookstandBlockEntity(pos, state) }, setOf(BLOCK))

    /** "Dark Oak Bookstand". */
    fun nameOf(wood: WoodKind): Component = Component.translatable("block.agesandtheart.bookstand.${wood.serializedName}")

    /** A bookstand of [wood], as its recipe makes one. */
    fun stackOf(wood: WoodKind): ItemStack {
        val stack = ItemStack(ITEM)
        stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(BookstandBlock.WOOD, wood))
        return stack
    }

    /** [stack], marked with the wood of the bookstand in [state], as the loot table's `copy_state` marks a broken one. */
    fun inItsWood(stack: ItemStack, state: BlockState): ItemStack {
        val properties = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
        stack.set(DataComponents.BLOCK_STATE, properties.with(BookstandBlock.WOOD, state))
        return stack
    }
}
