package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.WoodKind
import co.voik.agesandtheart.location
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.floor
import net.minecraft.world.phys.AABB
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
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult

/**
 * Which way a wheel's axle runs, and so which face of it shows which tray. Trays are counted round from the front: an
 * axle running **across** (left to right, as Ramelli's does) carries them front, over the top, back and under; an
 * **upright** one, a carousel's, carries them front, to the right, back and to the left. The faces the axle comes out
 * of show no tray, and turn the wheel by hand.
 */
enum class WheelAxle {
    ACROSS,
    UPRIGHT,
    ;

    /** The tray position [face] shows, counted round from the front, or null where it is an end of the axle. */
    fun positionShownBy(face: WheelFace): Int? = when (this) {
        ACROSS -> when (face) {
            WheelFace.FRONT -> 0
            WheelFace.TOP -> 1
            WheelFace.BACK -> 2
            WheelFace.BOTTOM -> 3
            WheelFace.LEFT, WheelFace.RIGHT -> null
        }
        UPRIGHT -> when (face) {
            WheelFace.FRONT -> 0
            WheelFace.RIGHT -> 1
            WheelFace.BACK -> 2
            WheelFace.LEFT -> 3
            WheelFace.TOP, WheelFace.BOTTOM -> null
        }
    }
}

/** A face of a wheel as whoever stands at its front sees it. */
enum class WheelFace {
    FRONT, BACK, LEFT, RIGHT, TOP, BOTTOM;

    companion object {
        /** [side] of a wheel whose front faces [facing]. */
        fun of(side: Direction, facing: Direction): WheelFace = when (side) {
            Direction.UP -> TOP
            Direction.DOWN -> BOTTOM
            facing -> FRONT
            facing.opposite -> BACK
            // Standing at the front, looking back at it, a quarter clockwise from the front is on their left.
            facing.clockWise -> LEFT
            else -> RIGHT
        }
    }
}

/**
 * The three book wheels, each a wheel one block across with four trays (design §7.8.3).
 *
 * - **Small:** one block, a book to a tray.
 * - **Large:** Ramelli's wheel (1588), its axle across: two blocks side by side along the axle, two books to a tray.
 * - **Carousel:** its axle upright: two blocks one over the other, a tier of trays in each, which turn together.
 */
enum class WheelForm(
    val id: String,
    val axle: WheelAxle,
    val booksPerTray: Int,
    /** The two parts of a two-block wheel, the first first, or null for a wheel of one block. */
    val part: EnumProperty<PairPart>?,
) {
    SMALL("book_wheel", WheelAxle.ACROSS, 1, null),
    LARGE("large_book_wheel", WheelAxle.ACROSS, 2, EnumProperty.create("part", PairPart::class.java, PairPart.LEFT, PairPart.RIGHT)),
    CAROUSEL("book_carousel", WheelAxle.UPRIGHT, 2, EnumProperty.create("part", PairPart::class.java, PairPart.LOWER, PairPart.UPPER)),
    ;

    val books: Int get() = TRAYS * booksPerTray

    /** From the first part toward the second, for a wheel whose front faces [facing]: to the right, or up. */
    fun towardTheSecond(facing: Direction): Direction? = when (this) {
        SMALL -> null
        LARGE -> facing.counterClockWise
        CAROUSEL -> Direction.UP
    }

    companion object {
        const val TRAYS = 4
    }
}

/**
 * A book wheel (design §7.8.3): books on trays round a wheel turned by hand, and taken and shelved only at
 * the front, which is what makes it a wheel rather than a round chiselled shelf.
 *
 * Used on its front with a book in hand, it shelves the book on the front tray's empty place; a book there is a
 * lectern's ([BookStands]): opened, read and linked from where it lies, and taken with its screen's Take Book. A book
 * left open stays open as the wheel turns, carried round level, as on Ramelli's. Used on a face that shows another tray,
 * it turns that tray round to the front the short way; used on an end of its axle, it turns a tray on, or back when
 * sneaking. Breaking it drops its books, as a chest does.
 *
 * Each block is a whole box, so the face a click lands on is the face of the wheel it means; the art may draw less.
 */
abstract class BookWheelBlock(properties: Properties) : BaseEntityBlock(properties) {

    /** A getter, never a field: [createBlockStateDefinition] reads it while the block is still being built. */
    abstract val form: WheelForm

    init {
        var state = stateDefinition.any().setValue(FACING, Direction.NORTH)
        form.part?.let { part -> state = state.setValue(part, part.possibleValues.first { it.isFirst }) }
        registerDefaultState(state)
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
        form.part?.let { builder.add(it) }
    }

    /** Its front toward whoever places it, where there is room for its second block. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.horizontalDirection.opposite
        val toward = form.towardTheSecond(facing) ?: return defaultBlockState().setValue(FACING, facing)
        val second = context.clickedPos.relative(toward)
        val level = context.level
        val hasRoom = level.isInsideBuildHeight(second) && level.getBlockState(second).canBeReplaced(context)
        return if (hasRoom) defaultBlockState().setValue(FACING, facing) else null
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        val part = form.part ?: return
        val toward = form.towardTheSecond(state.getValue(FACING)) ?: return
        level.setBlock(pos.relative(toward), state.setValue(part, part.possibleValues.first { !it.isFirst }), UPDATE_ALL)
    }

    /** A two-block wheel goes whole: either block lost takes the other with it. */
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
        val part = form.part
        if (part == null || directionToNeighbour != towardTheOther(state)) {
            return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
        }
        val otherStands = neighbourState.`is`(this) &&
            neighbourState.getValue(FACING) == state.getValue(FACING) &&
            neighbourState.getValue(part) != state.getValue(part)
        return if (otherStands) state else Blocks.AIR.defaultBlockState()
    }

    /** In creative, breaking the second block leaves nothing of the first to drop, as a door's upper half does. */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val part = form.part
        if (!level.isClientSide && player.preventsBlockDrops() && part != null && !state.getValue(part).isFirst) {
            val first = firstPos(state, pos)
            val firstState = level.getBlockState(first)
            if (firstState.`is`(this) && firstState.getValue(part).isFirst) {
                level.setBlock(first, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (isFirst(state)) BookWheelBlockEntity(pos, state) else null

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    /** The wheel turns on the client, catching up with the tray the server has at the front ([WheelTurning]). */
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

    /**
     * [player] using the wheel with [held] in hand: at the front, shelving it where the tray's place is empty and
     * taking the book there where it is not; elsewhere, turning.
     */
    private fun use(held: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val wheel = level.getBlockEntity(firstPos(state, pos)) as? BookWheelBlockEntity ?: return InteractionResult.PASS
        val along = form.part?.let { state.getValue(it).along } ?: 0
        return when (val used = usedAt(state, pos, player, hitResult, wheel, held)) {
            WheelUse.None -> InteractionResult.PASS
            is WheelUse.Turn -> {
                if (!level.isClientSide) wheel.turn(used.by)
                InteractionResult.SUCCESS
            }
            is WheelUse.Book -> useTheBook(wheel, wheel.slotOf(wheel.trayAt(used.position), along), level, player, hitResult)
            is WheelUse.Shelve -> {
                if (!level.isClientSide) wheel.shelve(wheel.trayAt(used.position), along, held.consumeAndReturn(1, player))
                InteractionResult.SUCCESS
            }
            WheelUse.Front -> {
                val slot = wheel.slotOf(wheel.frontTray, along)
                when {
                    !wheel.bookIn(slot).isEmpty -> useTheBook(wheel, slot, level, player, hitResult)
                    held.isEmpty -> InteractionResult.PASS
                    else -> {
                        if (!level.isClientSide) wheel.shelve(wheel.frontTray, along, held.consumeAndReturn(1, player))
                        InteractionResult.SUCCESS
                    }
                }
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

    /** What [player]'s use of the wheel at [hitResult] means: the face hit means a tray, an end of the axle a turn. */
    protected open fun usedAt(
        state: BlockState,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
        wheel: BookWheelBlockEntity,
        held: ItemStack,
    ): WheelUse {
        val face = WheelFace.of(hitResult.direction, state.getValue(FACING))
        val position = form.axle.positionShownBy(face)
        return when {
            position == 0 -> WheelUse.Front
            position == null -> WheelUse.Turn(if (player.isSecondaryUseActive) -1 else 1)
            position * 2 > WheelForm.TRAYS -> WheelUse.Turn(position - WheelForm.TRAYS)
            else -> WheelUse.Turn(position)
        }
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    private fun isFirst(state: BlockState): Boolean = form.part?.let { state.getValue(it).isFirst } ?: true

    /** From this block toward the wheel's other block. */
    private fun towardTheOther(state: BlockState): Direction? {
        val toward = form.towardTheSecond(state.getValue(FACING)) ?: return null
        return if (isFirst(state)) toward else toward.opposite
    }

    /** The block holding the wheel's books. */
    fun firstPos(state: BlockState, pos: BlockPos): BlockPos {
        if (isFirst(state)) return pos
        val toward = form.towardTheSecond(state.getValue(FACING)) ?: return pos
        return pos.relative(toward.opposite)
    }

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
    }
}

/**
 * What a use of a wheel means: the front tray, its book or its empty place; the book on the tray at [Book.position]
 * round from the front, or a book in hand put onto the empty one at [Shelve.position]; a turn by [Turn.by] trays; or
 * nothing.
 */
sealed interface WheelUse {
    data object Front : WheelUse
    data object None : WheelUse
    data class Book(val position: Int) : WheelUse
    data class Shelve(val position: Int) : WheelUse
    data class Turn(val by: Int) : WheelUse
}

/** Which of the small wheel's models a state of it is: the stand, in the world, or the moving parts its renderer draws. */
enum class WheelPiece(private val serializedName: String) : StringRepresentable {
    STAND("stand"),
    SHELF("shelf"),
    ARMS("arms"),
    ;

    override fun getSerializedName(): String = serializedName
}

/**
 * Jonah's small wheel, built of any wood and drawn in it, its shelves on the lectern's boards (Jonah, 2026-11-19). A
 * book on any shelf is used where it lies, as a lectern's is, and a book in hand goes onto whichever empty shelf it meets.
 * A use landing anywhere else pushes the wheel away from whoever uses it, by the half it lands in: above the axle from
 * the front, the front shelf rises to the top; below it, the front shelf sinks beneath; from behind, the other way.
 */
class SmallBookWheelBlock(properties: Properties) : BookWheelBlock(properties) {
    override val form: WheelForm get() = WheelForm.SMALL

    init {
        registerDefaultState(defaultBlockState().setValue(WOOD, WoodKind.OAK).setValue(PIECE, WheelPiece.STAND))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(WOOD, PIECE)
    }

    override fun usedAt(
        state: BlockState,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
        wheel: BookWheelBlockEntity,
        held: ItemStack,
    ): WheelUse {
        val corner = Vec3.atLowerCornerOf(pos)
        val facing = state.getValue(FACING)
        val eye = player.eyePosition.subtract(corner)
        val looked = SmallWheelShelves.lookedAt(facing, eye, hitResult.location.subtract(corner)) { position ->
            !wheel.bookIn(wheel.trayAt(position)).isEmpty
        }
        // A shelf met with a book on it was met from beneath; a book in hand goes onto an empty one.
        val isShelving = looked is SmallWheelShelves.Look.AtShelf && !held.isEmpty &&
            wheel.bookIn(wheel.trayAt(looked.position)).isEmpty
        return when {
            looked is SmallWheelShelves.Look.AtBook -> WheelUse.Book(looked.position)
            isShelving -> WheelUse.Shelve(looked.position)
            else -> WheelUse.Turn(SmallWheelShelves.pushedBy(facing, eye, looked.point))
        }
    }

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack =
        BookWheels.inItsWood(super.getCloneItemStack(level, pos, state, includeData), state)

    companion object {
        val WOOD: EnumProperty<WoodKind> = WoodKind.PROPERTY
        val PIECE: EnumProperty<WheelPiece> = EnumProperty.create("piece", WheelPiece::class.java)
    }
}

/** A small book wheel in hand, named for its wood as a boat or a sign is; one with no wood kept is oak. */
class SmallBookWheelItem(block: Block, properties: Properties) : BlockItem(block, properties) {
    override fun getName(stack: ItemStack): Component =
        BookWheels.nameOf(stack.get(DataComponents.BLOCK_STATE)?.get(SmallBookWheelBlock.WOOD) ?: WoodKind.OAK)
}

class LargeBookWheelBlock(properties: Properties) : BookWheelBlock(properties) {
    override val form: WheelForm get() = WheelForm.LARGE
}

class BookCarouselBlock(properties: Properties) : BookWheelBlock(properties) {
    override val form: WheelForm get() = WheelForm.CAROUSEL
}

/**
 * A wheel's books, by slot, a tray's books side by side ([WheelForm.booksPerTray]) and the trays in order round; and
 * which tray is at the front. Only the server turns it; every client turns its own drawing after ([WheelTurning]).
 */
class BookWheelBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(BookWheels.ENTITY, pos, state), BookStand {

    val form: WheelForm = (state.block as BookWheelBlock).form

    private val books: NonNullList<ItemStack> = NonNullList.withSize(form.books, ItemStack.EMPTY)

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

    /**
     * Only the books at the front, where they are read; and of a carousel's two tiers only the upper one, since its model
     * will have one (Jonah, 2026-11-19).
     */
    override fun panelCandidates(): Collection<Int> {
        val places = (0 until form.booksPerTray).map { slotOf(frontTray, it) }
        val read = when {
            // Every shelf of the small wheel is read where it is (Jonah, 2026-11-19).
            form == WheelForm.SMALL -> (0 until form.books).toList()
            form.axle == WheelAxle.UPRIGHT -> places.takeLast(1)
            else -> places
        }
        return read.filter { it in opened }
    }

    /** Lying on its tray where the tray rests, as a lectern would hold it, read from the front. */
    override fun openBookPose(place: Int): OpenBookPose {
        if (form == WheelForm.SMALL) {
            val position = Math.floorMod(place - frontTray, WheelForm.TRAYS)
            val surface = SmallWheelShelves.bookSurface(facing, position)
            val top = Vec3.atLowerCornerOf(blockPos).add(surface.top)
            return OpenBookPose(facing, top, BookSurface(top, surface.leanedBackDegrees))
        }
        val round = (place / form.booksPerTray - frontTray) * WheelTrays.QUARTER_TURN
        val middle = WheelTrays.openBookMiddle(form, facing, place % form.booksPerTray, round)
        return OpenBookPose(WheelTrays.outwardFacing(form, facing, round), Vec3.atLowerCornerOf(blockPos).add(middle))
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

    fun slotOf(tray: Int, along: Int): Int = tray * form.booksPerTray + along

    /** The tray [position] round from the front. */
    fun trayAt(position: Int): Int = Math.floorMod(frontTray + position, WheelForm.TRAYS)

    fun atTheFront(along: Int): ItemStack = books[slotOf(frontTray, along)]

    fun turnTheWheel() = turning.tick(frontTray, lastTurn)

    /** [book] onto place [along] of [tray], which is empty. */
    fun shelve(tray: Int, along: Int, book: ItemStack) {
        val slot = slotOf(tray, along)
        if (!books[slot].isEmpty) return
        books[slot] = book
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

    /** On by [by] trays, bringing the tray [by] round from the front to it. */
    fun turn(by: Int) {
        frontTray = Math.floorMod(frontTray + by, WheelForm.TRAYS)
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
        frontTray = Math.floorMod(input.getIntOr(FRONT_KEY, 0), WheelForm.TRAYS)
        opened.clear()
        input.read(OPEN_KEY, Codec.INT.listOf()).ifPresent { slots -> opened += slots.filter { it in 0 until form.books } }
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

    fun tick(frontTray: Int, lastTurn: Int) {
        if (!isStarted) {
            isStarted = true
            target = frontTray
            at = frontTray.toFloat()
            before = at
            return
        }
        val ahead = Math.floorMod(frontTray - target, WheelForm.TRAYS)
        target += when {
            ahead == 0 -> 0
            ahead * 2 == WheelForm.TRAYS -> if (lastTurn < 0) -ahead else ahead
            ahead * 2 < WheelForm.TRAYS -> ahead
            else -> ahead - WheelForm.TRAYS
        }
        before = at
        at = if (at < target) minOf(at + TRAYS_A_TICK, target.toFloat()) else maxOf(at - TRAYS_A_TICK, target.toFloat())
    }

    /** The tray at the front, fractional while it turns, [partialTick] of the way into the next tick. */
    fun shownAt(partialTick: Float, frontTray: Int): Float =
        if (!isStarted) frontTray.toFloat() else before + (at - before) * partialTick

    private companion object {
        /** A quarter turn in half a second. */
        const val TRAYS_A_TICK = 0.1f
    }
}

/**
 * Where a wheel's trays carry their books, in blocks from the corner of the block holding them and in the world's axes,
 * for the renderer and for a book lying open there alike. The art sets these, with its model's trays.
 */
object WheelTrays {
    /** From the axle to where a tray carries its book. */
    const val RADIUS = 4.5 / 16.0

    /** A carousel's tier floor, above the bottom of its block. */
    const val TIER_FLOOR = 2.0 / 16.0

    /** How far above its tray an open book's middle stands. */
    const val OPEN_ABOVE_THE_TRAY = 3.0 / 16.0

    const val QUARTER_TURN = Math.PI / 2

    /** Where place [along] of the tray [round] radians round from the front is: over the top, or to the right. */
    fun trayMiddle(form: WheelForm, facing: Direction, along: Int, round: Double): Vec3 {
        val forward = Vec3(facing.stepX.toDouble(), 0.0, facing.stepZ.toDouble())
        val right = facing.counterClockWise.let { Vec3(it.stepX.toDouble(), 0.0, it.stepZ.toDouble()) }
        val middle = Vec3(HALF, HALF, HALF)
        return when (form.axle) {
            WheelAxle.ACROSS -> middle.add(right.scale(along.toDouble()))
                .add(forward.scale(RADIUS * cos(round))).add(0.0, RADIUS * sin(round), 0.0)
            WheelAxle.UPRIGHT -> Vec3(HALF, along + TIER_FLOOR, HALF)
                .add(forward.scale(RADIUS * cos(round))).add(right.scale(RADIUS * sin(round)))
        }
    }

    fun openBookMiddle(form: WheelForm, facing: Direction, along: Int, round: Double): Vec3 =
        trayMiddle(form, facing, along, round).add(0.0, OPEN_ABOVE_THE_TRAY, 0.0)

    /** Which way a book open on the tray [round] radians round is read from: the front, or out from a carousel's axle. */
    fun outwardFacing(form: WheelForm, facing: Direction, round: Double): Direction = when (form.axle) {
        WheelAxle.ACROSS -> facing
        WheelAxle.UPRIGHT -> {
            val quarters = Math.floorMod(Math.round(round / QUARTER_TURN).toInt(), WheelForm.TRAYS)
            // A quarter on is the tray to the right, which faces right: anticlockwise of the front, seen from above.
            (0 until quarters).fold(facing) { turned, _ -> turned.counterClockWise }
        }
    }

    private const val HALF = 0.5
}

/**
 * Where the small wheel's shelves are, as Jonah's model rests them facing north, in its pixels. Each shelf rides an arm
 * of a cross turning on the axle, sliding along it as in a groove (Jonah): from one resting place to the next it turns a
 * quarter with the arm, slides between the two places' distances along it, and tilts between their tilts, so it passes
 * through every resting pose on its way round.
 *
 * Positions are counted round as the trays are: the front, the top, the back and the bottom.
 */
object SmallWheelShelves {
    const val FRONT = 0
    const val TOP = 1
    const val BACK = 2
    const val BOTTOM = 3

    /** A shelf's middle, and its tilt about the model's x axis: a negative tilt lowers its front edge. */
    data class ShelfPose(val middle: Vec3, val tiltDegrees: Double) {
        private val tilt: Double get() = Math.toRadians(tiltDegrees)

        /** Out of the shelf's top. */
        val normal: Vec3 get() = Vec3(0.0, cos(tilt), sin(tilt))

        /** Along the shelf's top toward its back edge. */
        val towardTheBack: Vec3 get() = Vec3(0.0, -sin(tilt), cos(tilt))

        /** The middle of the shelf's top, where a book lies. */
        val top: Vec3 get() = middle.add(normal.scale(SHELF_THICKNESS / 2))

        /** How far a book on it, read from the front, leans back from upright: a lectern's 67.5 at the front, flat 90. */
        val leanedBackDegrees: Double get() = QUARTER_TURN_DEGREES + tiltDegrees
    }

    /** Where the axle runs, across the block. */
    val AXLE = Vec3(8.0, 9.0, 8.0)

    private const val SHELF_THICKNESS = 1.0

    /** The shelves as Jonah's model rests them, by position; only the front one tilts, so the front is plain (Jonah). */
    private val AT_REST = listOf(
        ShelfPose(Vec3(8.0, 9.5, 4.0), -22.5),
        ShelfPose(Vec3(8.0, 15.5, 8.0), 0.0),
        ShelfPose(Vec3(8.0, 9.5, 12.0), 0.0),
        ShelfPose(Vec3(8.0, 2.5, 8.0), 0.0),
    )

    private const val QUARTER_TURN = Math.PI / 2
    private const val QUARTER_TURN_DEGREES = 90.0
    private const val PIXELS = 16.0
    private const val MIDDLE = 8.0
    private val POSITIONS = WheelForm.TRAYS.toDouble()

    /** Out along the arm that points [round] radians round from the front. */
    private fun outward(round: Double) = Vec3(0.0, sin(round), -cos(round))

    /** Across that arm, the way it turns. */
    private fun sideways(round: Double) = Vec3(0.0, cos(round), sin(round))

    /** How far out along its arm, and across it, the shelf resting at [position] is. */
    private fun onItsArm(position: Int): Pair<Double, Double> {
        val round = position * QUARTER_TURN
        val fromTheAxle = AT_REST[position].middle.subtract(AXLE)
        return fromTheAxle.dot(outward(round)) to fromTheAxle.dot(sideways(round))
    }

    /** The shelf at [position] round, fractional between its resting places. */
    fun poseAt(position: Double): ShelfPose {
        val wrapped = position - POSITIONS * floor(position / POSITIONS)
        val from = floor(wrapped).toInt()
        val to = (from + 1) % WheelForm.TRAYS
        val share = wrapped - from
        fun between(a: Double, b: Double) = a + (b - a) * share
        val (alongFrom, acrossFrom) = onItsArm(from)
        val (alongTo, acrossTo) = onItsArm(to)
        val round = wrapped * QUARTER_TURN
        val middle = AXLE
            .add(outward(round).scale(between(alongFrom, alongTo)))
            .add(sideways(round).scale(between(acrossFrom, acrossTo)))
        return ShelfPose(middle, between(AT_REST[from].tiltDegrees, AT_REST[to].tiltDegrees))
    }

    /** How far the cross of arms has turned, in degrees about the model's x axis, with [front] the tray at the front. */
    fun armsTurnedDegrees(front: Double): Double = -front * Math.toDegrees(QUARTER_TURN)

    /** The top a book lies on, on the shelf resting at [position], in the block of a wheel facing [facing]. */
    fun bookSurface(facing: Direction, position: Int): BookSurface {
        val pose = AT_REST[position]
        return BookSurface(toTheBlock(facing, pose.top), pose.leanedBackDegrees)
    }

    /** What a look at the wheel meets first, and where, in the model's pixels. */
    sealed interface Look {
        val point: Vec3

        data class AtBook(val position: Int, override val point: Vec3) : Look
        data class AtShelf(val position: Int, override val point: Vec3) : Look
        data class AtFrame(override val point: Vec3) : Look
    }

    /** Half a shelf across and along it, and a little over, so that a look at its edge meets it. */
    private const val HALF_ACROSS = 7.5
    private const val HALF_ALONG = 4.5

    /** How far over its shelf's top a book's open pages stand. */
    private const val BOOK_OVER_THE_SHELF = 2.0

    /** The plinth, the posts and the arms where they rest. */
    private val FRAME = listOf(
        AABB(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
        AABB(-1.0, 0.0, 7.0, 0.0, 10.0, 9.0),
        AABB(16.0, 0.0, 7.0, 17.0, 10.0, 9.0),
        AABB(0.0, 2.0, 7.0, 1.0, 16.0, 9.0),
        AABB(15.0, 2.0, 7.0, 16.0, 16.0, 9.0),
        AABB(0.0, 8.0, 1.0, 1.0, 10.0, 15.0),
        AABB(15.0, 8.0, 1.0, 16.0, 10.0, 15.0),
    )

    /** How far a look is followed, in pixels. */
    private const val LOOKED_THROUGH = 160.0

    /**
     * What a look from [eye] through [hit], both in blocks from the block's corner, meets first on a wheel facing
     * [facing] at rest: a book on the shelf at a position [hasABook], a shelf, the frame; or, meeting none of them,
     * the block where it was hit.
     */
    fun lookedAt(facing: Direction, eye: Vec3, hit: Vec3, hasABook: (Int) -> Boolean): Look {
        val from = inTheModel(facing, eye)
        val through = inTheModel(facing, hit)
        val looking = through.subtract(from).normalize()
        val met = mutableListOf<Pair<Double, Look>>()
        for ((position, pose) in AT_REST.withIndex()) {
            if (hasABook(position)) {
                val pages = pose.top.add(pose.normal.scale(BOOK_OVER_THE_SHELF))
                alongTheLookTo(pose, pages, from, looking, onlyFromAbove = true)
                    ?.let { met += it to Look.AtBook(position, from.add(looking.scale(it))) }
            }
            alongTheLookTo(pose, pose.middle, from, looking, onlyFromAbove = false)
                ?.let { met += it to Look.AtShelf(position, from.add(looking.scale(it))) }
        }
        val end = from.add(looking.scale(LOOKED_THROUGH))
        for (box in FRAME) {
            box.clip(from, end).orElse(null)?.let { met += it.distanceTo(from) to Look.AtFrame(it) }
        }
        return met.minByOrNull { it.first }?.second ?: Look.AtFrame(through)
    }

    /** How far along a look from [from] it meets a shelf like [pose]'s lying at [middle], or null where it misses. */
    private fun alongTheLookTo(pose: ShelfPose, middle: Vec3, from: Vec3, looking: Vec3, onlyFromAbove: Boolean): Double? {
        val into = looking.dot(pose.normal)
        val isEdgeOn = abs(into) < EDGE_ON
        if (isEdgeOn || (onlyFromAbove && into > 0.0)) return null
        val along = middle.subtract(from).dot(pose.normal) / into
        if (along <= 0.0) return null
        val landed = from.add(looking.scale(along)).subtract(middle)
        val isOnIt = abs(landed.x) <= HALF_ACROSS && abs(landed.dot(pose.towardTheBack)) <= HALF_ALONG
        return if (isOnIt) along else null
    }

    private const val EDGE_ON = 1e-6

    /**
     * Which way a use landing at [point], in the model's pixels, pushes a wheel facing [facing], used from [eye] in
     * blocks from its corner: away from whoever pushes it. From the front, above the axle the front shelf rises to the
     * top (the tray beneath comes to the front); below it, the front shelf sinks beneath; from behind, the other way.
     */
    fun pushedBy(facing: Direction, eye: Vec3, point: Vec3): Int {
        val isFromTheFront = inTheModel(facing, eye).z < AXLE.z
        val isAboveTheAxle = point.y >= AXLE.y
        return if (isFromTheFront == isAboveTheAxle) -1 else 1
    }

    /** A point in the model's pixels, facing north, in the block of a wheel facing [facing]. */
    fun toTheBlock(facing: Direction, pixels: Vec3): Vec3 {
        val east = facing.clockWise
        val back = facing.opposite
        val across = pixels.x - MIDDLE
        val toTheBack = pixels.z - MIDDLE
        return Vec3(
            (MIDDLE + across * east.stepX + toTheBack * back.stepX) / PIXELS,
            pixels.y / PIXELS,
            (MIDDLE + across * east.stepZ + toTheBack * back.stepZ) / PIXELS,
        )
    }

    /** A point in the block of a wheel facing [facing], in the model's pixels facing north. */
    private fun inTheModel(facing: Direction, inTheBlock: Vec3): Vec3 {
        val east = facing.clockWise
        val back = facing.opposite
        val fromTheMiddle = inTheBlock.subtract(0.5, 0.0, 0.5)
        return Vec3(
            MIDDLE + (fromTheMiddle.x * east.stepX + fromTheMiddle.z * east.stepZ) * PIXELS,
            inTheBlock.y * PIXELS,
            MIDDLE + (fromTheMiddle.x * back.stepX + fromTheMiddle.z * back.stepZ) * PIXELS,
        )
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
        val properties = Item.Properties().setId(ResourceKey.create(Registries.ITEM, block.form.id.location())).useBlockDescriptionPrefix()
        if (block == SMALL) SmallBookWheelItem(block, properties) else BlockItem(block, properties)
    }

    val SMALL_ITEM: Item get() = ITEMS.getValue(SMALL)

    /** "Dark Oak Book Wheel". */
    fun nameOf(wood: WoodKind): Component = Component.translatable("block.agesandtheart.book_wheel.${wood.serializedName}")

    /** A small book wheel of [wood]. */
    fun stackOf(wood: WoodKind): ItemStack {
        val stack = ItemStack(SMALL_ITEM)
        stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(SmallBookWheelBlock.WOOD, wood))
        return stack
    }

    /** [stack], marked with the wood of the small wheel in [state], as the loot table's `copy_state` marks a broken one. */
    fun inItsWood(stack: ItemStack, state: BlockState): ItemStack {
        val properties = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
        stack.set(DataComponents.BLOCK_STATE, properties.with(SmallBookWheelBlock.WOOD, state))
        return stack
    }

    val ENTITY_ID: Identifier = "book_wheel".location()

    val ENTITY: BlockEntityType<BookWheelBlockEntity> =
        BlockEntityType({ pos, state -> BookWheelBlockEntity(pos, state) }, BLOCKS.toSet())
}
