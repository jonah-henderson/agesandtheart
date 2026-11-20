package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.WoodKind
import co.voik.agesandtheart.location
import net.minecraft.world.phys.Vec3
import net.minecraft.server.level.ServerPlayer
import com.mojang.serialization.Codec
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
import net.minecraft.tags.ItemTags
import net.minecraft.util.Prediction
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The three book wheels (design §7.8.3), each Jonah's model, built of any wood, its shelves on the lectern's boards, a
 * book to a shelf.
 *
 * - **Small:** one block, four shelves sliding on a cross of arms round an axle running across.
 * - **Large:** two blocks across, three up and three back, eight shelves hung on pins round an axle running across.
 * - **Carousel:** three blocks across and three back, eight shelves at the tips of arms round an upright post.
 */
enum class WheelForm(val id: String, val shelves: ModelledShelves, val cells: WheelCells?) {
    SMALL("book_wheel", WheelShelves.SMALL, null),
    LARGE("large_book_wheel", WheelShelves.LARGE, WheelCells.LARGE),
    CAROUSEL("book_carousel", CarouselShelves, WheelCells.CAROUSEL),
    ;

    val trays: Int get() = shelves.positions

    /** Which of the wheel's blocks a state is, for a wheel bigger than a block. */
    val cell: IntegerProperty? by lazy { cells?.let { IntegerProperty.create("cell", 0, it.count - 1) } }
}

/**
 * A book wheel (design §7.8.3): books on shelves round a wheel turned by hand (Jonah, 2026-11-19). A book on any shelf
 * is a lectern's ([BookStands]): opened, read and linked from where it lies, and taken with its screen's Take Book; one
 * left open stays open as the wheel turns. A book in hand goes onto whichever empty shelf a look meets, and a use landing
 * anywhere else pushes the wheel the way the look would turn it about its axle ([ModelledShelves.pushedBy]). Breaking
 * it drops its books, as a chest does.
 *
 * A wheel bigger than a block stands in its [WheelForm.cells], all of which go together; its model and its books are kept
 * in the block it is built round, and the others draw nothing but their part of its stand. Each block is a whole box, for
 * clicking; a look is followed through it to what of the wheel it meets.
 */
abstract class BookWheelBlock(properties: Properties) : BaseEntityBlock(properties) {

    /** A getter, never a field: [createBlockStateDefinition] reads it while the block is still being built. */
    abstract val form: WheelForm

    init {
        var state = stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(WOOD, WoodKind.OAK)
            .setValue(PIECE, WheelPiece.STAND)
        val cells = form.cells
        val cell = form.cell
        if (cells != null && cell != null) state = state.setValue(cell, cells.holder)
        registerDefaultState(state)
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, WOOD, PIECE)
        form.cell?.let { builder.add(it) }
    }

    /** Its front toward whoever places it, where every block of it has room. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.horizontalDirection.opposite
        val placed = defaultBlockState().setValue(FACING, facing)
        val cells = form.cells ?: return placed
        val cell = form.cell ?: return placed
        val placedCell = cells.placedFor(facing, context.clickedFace)
        val holder = context.clickedPos.subtract(cells.offsetOf(placedCell, facing))
        val level = context.level
        fun hasRoomAt(index: Int): Boolean {
            val at = holder.offset(cells.offsetOf(index, facing))
            val isThePlacedOne = index == placedCell
            return level.isInsideBuildHeight(at) && (isThePlacedOne || level.getBlockState(at).canBeReplaced(context))
        }
        if (!(0 until cells.count).all(::hasRoomAt)) return null
        return placed.setValue(cell, placedCell)
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        val cells = form.cells ?: return
        val cell = form.cell ?: return
        val facing = state.getValue(FACING)
        val holder = holderPos(state, pos)
        val placedCell = state.getValue(cell)
        for (index in 0 until cells.count) {
            if (index == placedCell) continue
            level.setBlock(holder.offset(cells.offsetOf(index, facing)), state.setValue(cell, index), UPDATE_ALL)
        }
    }

    /** A wheel bigger than a block goes whole: any block of it lost takes the others with it. */
    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        val cells = form.cells
        val cell = form.cell
        if (cells == null || cell == null) {
            return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
        }
        val facing = state.getValue(FACING)
        val beside = cells.beside(state.getValue(cell), facing, directionToNeighbour) ?: return state
        val isTheWheel = neighbourState.`is`(this) &&
            neighbourState.getValue(FACING) == facing &&
            neighbourState.getValue(cell) == beside
        return if (isTheWheel) state else Blocks.AIR.defaultBlockState()
    }

    /** In creative, breaking any other block leaves nothing of the block holding it to drop, as a door's upper half does. */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide && player.preventsBlockDrops() && !isHolder(state)) {
            val holder = holderPos(state, pos)
            val holderState = level.getBlockState(holder)
            if (holderState.`is`(this) && isHolder(holderState)) {
                level.setBlock(holder, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (isHolder(state)) BookWheelBlockEntity(pos, state) else null

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    /** A whole block, for clicking, but at the carousel's corners, which stand only where its arms sweep. */
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        val cells = form.cells
        val cell = form.cell
        if (form.shelves != CarouselShelves || cells == null || cell == null) return Shapes.block()
        val fromTheHolder = cells.offsetOf(state.getValue(cell), state.getValue(FACING))
        return CarouselShelves.shapeOf(fromTheHolder.x, fromTheHolder.z) ?: Shapes.block()
    }

    /** Shades nothing beside it, as glass does: its whole-block box is for clicking, and the wheel is open. */
    override fun getShadeBrightness(state: BlockState, level: BlockGetter, pos: BlockPos): Float = UNSHADED

    /** Light passes through it as through air, so the wheel's blocks are lit where they stand. */
    override fun propagatesSkylightDown(state: BlockState): Boolean = true

    /** On a client the wheel's drawing catches up with it; on the server its open books are shut when left unwatched. */
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) {
            createTickerHelper(type, BookWheels.ENTITY) { _, _, _, wheel -> wheel.turnTheWheel() }
        } else {
            createTickerHelper(type, BookWheels.ENTITY) { _, _, _, wheel -> wheel.lookRound() }
        }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!stack.`is`(ItemTags.BOOKSHELF_BOOKS)) return InteractionResult.TRY_WITH_EMPTY_HAND
        return use(stack, state, level, pos, player, hitResult)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult =
        use(ItemStack.EMPTY, state, level, pos, player, hitResult)

    /** [player] using the wheel with [held] in hand: a book on a shelf, a book onto an empty one, or a push. */
    private fun use(held: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val wheel = level.getBlockEntity(holderPos(state, pos)) as? BookWheelBlockEntity ?: return InteractionResult.PASS
        return when (val used = usedAt(state, player, hitResult, wheel, held)) {
            WheelUse.None -> InteractionResult.PASS
            is WheelUse.Turn -> {
                if (!level.isClientSide) wheel.turn(used.by)
                InteractionResult.SUCCESS
            }
            is WheelUse.Book -> useTheBook(wheel, wheel.trayAt(used.position), level, player, hitResult)
            is WheelUse.Shelve -> {
                if (!level.isClientSide) wheel.shelve(wheel.trayAt(used.position), held.consumeAndReturn(1, player))
                InteractionResult.SUCCESS
            }
        }
    }

    /** A high-capacity lectern (Jonah): the book in [slot] is opened, read, linked from and shut where it lies. */
    private fun useTheBook(wheel: BookWheelBlockEntity, slot: Int, level: Level, player: Player, hitResult: BlockHitResult): InteractionResult =
        BookStands.use(
            level, wheel, slot, wheel.bookIn(slot), player, hitResult,
            open = { wheel.open(slot) },
            close = { wheel.close(slot) },
            take = { player.inventory.placeItemBackInInventory(wheel.take(slot), Prediction.SERVER_ONLY) },
        )

    /** What [player]'s use of the wheel at [hitResult] means, with [held] in hand. */
    private fun usedAt(state: BlockState, player: Player, hitResult: BlockHitResult, wheel: BookWheelBlockEntity, held: ItemStack): WheelUse {
        val corner = Vec3.atLowerCornerOf(wheel.blockPos)
        val facing = state.getValue(FACING)
        val eye = player.eyePosition.subtract(corner)
        val looked = form.shelves.lookedAt(facing, eye, hitResult.location.subtract(corner)) { position ->
            !wheel.bookIn(wheel.trayAt(position)).isEmpty
        }
        // A shelf met with a book on it was met from behind its book; a book in hand goes onto an empty one.
        val isShelving = looked is ShelfLook.AtShelf && !held.isEmpty && wheel.bookIn(wheel.trayAt(looked.position)).isEmpty
        val pushedBy = form.shelves.pushedBy(facing, eye, looked.point)
        return when {
            looked is ShelfLook.AtBook -> WheelUse.Book(looked.position)
            isShelving -> WheelUse.Shelve(looked.position)
            pushedBy == 0 -> WheelUse.None
            else -> WheelUse.Turn(pushedBy)
        }
    }

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack =
        BookWheels.inItsWood(super.getCloneItemStack(level, pos, state, includeData), state)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    /** Whether [state] is the block holding the wheel: the only block of a small one, or the one a bigger one is built round. */
    fun isHolder(state: BlockState): Boolean {
        val cells = form.cells ?: return true
        val cell = form.cell ?: return true
        return state.getValue(cell) == cells.holder
    }

    /** The block holding the wheel's books, for its block [state] at [pos]. */
    fun holderPos(state: BlockState, pos: BlockPos): BlockPos {
        val cells = form.cells ?: return pos
        val cell = form.cell ?: return pos
        return pos.subtract(cells.offsetOf(state.getValue(cell), state.getValue(FACING)))
    }

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val WOOD: EnumProperty<WoodKind> = WoodKind.PROPERTY
        val PIECE: EnumProperty<WheelPiece> = EnumProperty.create("piece", WheelPiece::class.java)
        private const val UNSHADED = 1.0f
    }
}

/** What a use of a wheel means: the book on the shelf at a position round from the front, a book in hand put onto the empty one there, a turn by [Turn.by] shelves, or nothing. */
sealed interface WheelUse {
    data object None : WheelUse
    data class Book(val position: Int) : WheelUse
    data class Shelve(val position: Int) : WheelUse
    data class Turn(val by: Int) : WheelUse
}

/** Which of a wheel's models a state of it is: the stand, in the world, or the moving parts its renderer draws. */
enum class WheelPiece(private val serializedName: String) : StringRepresentable {
    STAND("stand"),
    SHELF("shelf"),
    WHEEL("wheel"),
    ;

    override fun getSerializedName(): String = serializedName
}

class SmallBookWheelBlock(properties: Properties) : BookWheelBlock(properties) {
    override val form: WheelForm get() = WheelForm.SMALL
}

class LargeBookWheelBlock(properties: Properties) : BookWheelBlock(properties) {
    override val form: WheelForm get() = WheelForm.LARGE
}

class BookCarouselBlock(properties: Properties) : BookWheelBlock(properties) {
    override val form: WheelForm get() = WheelForm.CAROUSEL
}

/** A wheel in hand, named for its wood as a boat or a sign is; one with no wood kept is oak. */
class BookWheelItem(private val wheel: BookWheelBlock, properties: Properties) : BlockItem(wheel, properties) {
    override fun getName(stack: ItemStack): Component =
        BookWheels.nameOf(wheel, stack.get(DataComponents.BLOCK_STATE)?.get(BookWheelBlock.WOOD) ?: WoodKind.OAK)
}

/**
 * A wheel's books, a slot to each shelf in order round, and which shelf is at the front. Only the server turns it; every
 * client turns its own drawing after ([WheelTurning]).
 */
class BookWheelBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(BookWheels.ENTITY, pos, state), BookStand {

    val form: WheelForm = (state.block as BookWheelBlock).form

    private val books: NonNullList<ItemStack> = NonNullList.withSize(form.trays, ItemStack.EMPTY)

    /** The tray at the front. */
    var frontTray = 0
        private set

    /** Which way it last turned, on (1) or back (-1), so a client turns a half turn the same way. */
    var lastTurn = 1
        private set

    /** How the wheel stands on this client; never saved or sent. */
    val turning = WheelTurning()

    /** The slots whose books lie open. */
    private val opened = HashSet<Int>()

    fun isOpen(slot: Int): Boolean = slot in opened

    override fun openBookAt(place: Int): ItemStack? = books.getOrNull(place)?.takeIf { place in opened && !it.isEmpty }

    override fun openPlaces(): Collection<Int> = opened.toList()

    /** Lying on its shelf where the shelf rests, read from outside the wheel; every shelf's is read where it is (Jonah). */
    override fun openBookPose(place: Int): OpenBookPose {
        val surface = form.shelves.bookSurface(facing, Math.floorMod(place - frontTray, form.trays))
        val top = Vec3.atLowerCornerOf(blockPos).add(surface.top)
        return OpenBookPose(facing, top, surface.copy(top = top))
    }

    override fun answer(player: ServerPlayer, place: Int, asked: StandAsked) {
        when (asked) {
            StandAsked.TAKE -> if (!books[place].isEmpty) player.inventory.placeItemBackInInventory(take(place), Prediction.SERVER_ONLY)
            StandAsked.CLOSE -> close(place)
        }
    }

    val facing: Direction get() = blockState.getValue(BookWheelBlock.FACING)

    fun open(slot: Int) {
        if (opened.add(slot)) changed()
    }

    fun close(slot: Int) {
        if (!opened.remove(slot)) return
        play(SoundEvents.BOOK_PAGE_TURN)
        changed()
    }

    /** Its open books shut where nobody is near to read them, as a lectern's are. */
    fun lookRound() {
        val level = level as? ServerLevel ?: return
        if (opened.isEmpty() || !BookStands.isTimeToLookRound(level) || BookStands.anybodyNear(level, blockPos)) return
        opened.clear()
        changed()
    }

    fun bookIn(slot: Int): ItemStack = books[slot]

    /** The shelf [position] round from the front. */
    fun trayAt(position: Int): Int = Math.floorMod(frontTray + position, form.trays)

    fun turnTheWheel() = turning.tick(frontTray, lastTurn, form.trays)

    /** [book] onto [tray], which is empty. */
    fun shelve(tray: Int, book: ItemStack) {
        if (!books[tray].isEmpty) return
        books[tray] = book
        val enchanted = book.`is`(Items.ENCHANTED_BOOK)
        play(if (enchanted) SoundEvents.CHISELED_BOOKSHELF_INSERT_ENCHANTED else SoundEvents.CHISELED_BOOKSHELF_INSERT)
        changed()
    }

    /** The book in [slot], taken off its tray, and shut. */
    fun take(slot: Int): ItemStack {
        val taken = books[slot]
        books[slot] = ItemStack.EMPTY
        opened.remove(slot)
        val enchanted = taken.`is`(Items.ENCHANTED_BOOK)
        play(if (enchanted) SoundEvents.CHISELED_BOOKSHELF_PICKUP_ENCHANTED else SoundEvents.CHISELED_BOOKSHELF_PICKUP)
        changed()
        return taken
    }

    /** On by [by] shelves, bringing the shelf [by] round from the front to it. */
    fun turn(by: Int) {
        frontTray = Math.floorMod(frontTray + by, form.trays)
        lastTurn = if (by < 0) -1 else 1
        play(SoundEvents.ITEM_FRAME_ROTATE_ITEM)
        changed()
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
        frontTray = Math.floorMod(input.getIntOr(FRONT_KEY, 0), form.trays)
        opened.clear()
        input.read(OPEN_KEY, Codec.INT.listOf()).ifPresent { slots -> opened += slots.filter { it in 0 until form.trays } }
        lastTurn = if (input.getIntOr(LAST_TURN_KEY, 1) < 0) -1 else 1
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, books, true)
        output.putInt(FRONT_KEY, frontTray)
        output.putInt(LAST_TURN_KEY, lastTurn)
        output.store(OPEN_KEY, Codec.INT.listOf(), opened.sorted())
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    private companion object {
        const val FRONT_KEY = "front"
        const val LAST_TURN_KEY = "last_turn"
        const val OPEN_KEY = "open"
    }
}

/**
 * How far round a wheel stands on a client, catching up with the tray the server has at the front: a tray at a time
 * the short way round, or a half turn the way it last turned. Counted in trays and never wrapped, so the drawing turns
 * on through a full turn rather than spinning back.
 */
class WheelTurning {
    private var isStarted = false
    private var target = 0
    private var at = 0.0f
    private var before = 0.0f

    fun tick(frontTray: Int, lastTurn: Int, trays: Int) {
        if (!isStarted) {
            isStarted = true
            target = frontTray
            at = frontTray.toFloat()
            before = at
            return
        }
        val ahead = Math.floorMod(frontTray - target, trays)
        target += when {
            ahead == 0 -> 0
            ahead * 2 == trays -> if (lastTurn < 0) -ahead else ahead
            ahead * 2 < trays -> ahead
            else -> ahead - trays
        }
        before = at
        at = if (at < target) minOf(at + TRAYS_A_TICK, target.toFloat()) else maxOf(at - TRAYS_A_TICK, target.toFloat())
    }

    /** The tray at the front, fractional while it turns, [partialTick] of the way into the next tick. */
    fun shownAt(partialTick: Float, frontTray: Int): Float =
        if (!isStarted) frontTray.toFloat() else before + (at - before) * partialTick

    private companion object {
        /** A tray round in half a second. */
        const val TRAYS_A_TICK = 0.1f
    }
}

/** The book wheels' registrations, for [co.voik.agesandtheart.content.AgeContent]'s lists. */
object BookWheels {
    private const val STRENGTH = 2.5f

    private fun properties(form: WheelForm): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .setId(ResourceKey.create(Registries.BLOCK, form.id.location()))
        .mapColor(MapColor.WOOD)
        .strength(STRENGTH)
        .sound(SoundType.CHISELED_BOOKSHELF)
        .noOcclusion()
        .ignitedByLava()

    val SMALL: BookWheelBlock = SmallBookWheelBlock(properties(WheelForm.SMALL))
    val LARGE: BookWheelBlock = LargeBookWheelBlock(properties(WheelForm.LARGE))
    val CAROUSEL: BookWheelBlock = BookCarouselBlock(properties(WheelForm.CAROUSEL))

    val BLOCKS: List<BookWheelBlock> = listOf(SMALL, LARGE, CAROUSEL)

    val ITEMS: Map<BookWheelBlock, Item> = BLOCKS.associateWith { block ->
        BookWheelItem(block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, block.form.id.location())).useBlockDescriptionPrefix())
    }

    /** The wheel [item] places, or null. */
    fun wheelOf(item: Item): BookWheelBlock? = BLOCKS.firstOrNull { ITEMS[it] == item }

    /** "Dark Oak Large Book Wheel". */
    fun nameOf(wheel: BookWheelBlock, wood: WoodKind): Component =
        Component.translatable("block.agesandtheart.${wheel.form.id}.${wood.serializedName}")

    /** A wheel [wheel] of [wood]. */
    fun stackOf(wheel: BookWheelBlock, wood: WoodKind): ItemStack {
        val stack = ItemStack(ITEMS.getValue(wheel))
        stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(BookWheelBlock.WOOD, wood))
        return stack
    }

    /** [stack], marked with the wood of the wheel in [state], as the loot table's `copy_state` marks a broken one. */
    fun inItsWood(stack: ItemStack, state: BlockState): ItemStack {
        val properties = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
        stack.set(DataComponents.BLOCK_STATE, properties.with(BookWheelBlock.WOOD, state))
        return stack
    }

    val ENTITY_ID: Identifier = "book_wheel".location()

    val ENTITY: BlockEntityType<BookWheelBlockEntity> =
        BlockEntityType({ pos, state -> BookWheelBlockEntity(pos, state) }, BLOCKS.toSet())
}
