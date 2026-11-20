package co.voik.agesandtheart.book

import co.voik.agesandtheart.client.FinderSounds
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
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
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.util.Prediction
import net.minecraft.util.ProblemReporter
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChiseledBookShelfBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.UUID

/** One slot of one chiselled bookshelf. */
data class ShelfSlot(val shelf: BlockPos, val slot: Int)

/**
 * The book finder (design §7.8.3): mounted on the face of a wall of chiselled bookshelves, its carriage running across
 * the wall's [BookFinderFrameBlock]s, it searches every book on the wall, and on a choice its carriage goes across the
 * face of the wall to the shelf, draws the book out, brings it back and lays it on its lectern, for the player to read
 * where it stands. The block is the frame the carriage leaves from; the carriage is drawn ([FinderPart]).
 *
 * Used with an empty hand while the frame is free, it lists the wall's books. The book it holds open is a stand's
 * ([BookStands]): read and linked from as on a lectern, taken with its screen's Take Book, and shut — which here sends it
 * home. A book handed to it is laid on its lectern shut, as a fetched one is, and sent home the same way: to where it
 * was taken from, where it was the last one taken from the frame, and otherwise to the nearest empty slot.
 */
class BookFinderBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HOME, true).setValue(PART, FinderPart.STATIC)
                .setValue(INDICATOR, BookFinderFrameBlock.Indicator.READY),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, HOME, PART, INDICATOR)
    }

    /**
     * On the side of any block, facing out of it (Jonah, 2026-11-20): over a chiselled bookshelf it reaches the shelf,
     * and over anything else it is only track, for the carriage to travel.
     */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.clickedFace.takeIf { it.axis.isHorizontal } ?: return null
        val state = defaultBlockState().setValue(FACING, facing)
        return state.takeIf { it.canSurvive(context.level, context.clickedPos) }
    }

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        BookFinders.isAgainstABlock(level, pos, state.getValue(FACING))

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
        val lostWhatItStandsOn = directionToNeighbour == state.getValue(FACING).opposite && !state.canSurvive(level, pos)
        return if (lostWhatItStandsOn) Blocks.AIR.defaultBlockState() else state
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        if (level is ServerLevel && !oldState.`is`(this)) FrameIndicators.trackAdded(level, pos, state.getValue(FACING))
    }

    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
        if (!level.getBlockState(pos).`is`(this)) FrameIndicators.trackRemoved(level, pos, state.getValue(FACING))
    }

    /**
     * The whole space while the carriage is home, so it and the book on its lectern can be clicked wherever they are
     * drawn, and the plate on the shelf while it is out on the wall.
     */
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        if (state.getValue(HOME)) Shapes.block() else PLATES.getValue(state.getValue(FACING))

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        PLATES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BookFinderBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) {
            createTickerHelper(type, BookFinders.ENTITY) { _, _, _, finder -> FinderSounds.tick(finder) }
        } else {
            createTickerHelper(type, BookFinders.ENTITY) { _, _, _, finder -> finder.tick() }
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
        val finder = level.getBlockEntity(pos) as? BookFinderBlockEntity ?: return InteractionResult.PASS
        if (!stack.`is`(ItemTags.BOOKSHELF_BOOKS) || finder.task != FrameTask.RESTING) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (!level.isClientSide) finder.file(stack.consumeAndReturn(1, player))
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val finder = level.getBlockEntity(pos) as? BookFinderBlockEntity ?: return InteractionResult.PASS
        if (finder.task != FrameTask.PRESENTING) {
            if (player is ServerPlayer) finder.listFor(player)
            return InteractionResult.SUCCESS
        }
        // Presented shut, and opened and shut as on a lectern; sneaking at it once it is shut sends it home.
        val sendsItHome = player.isSecondaryUseActive && player.mainHandItem.isEmpty && !finder.isOpen
        if (sendsItHome) {
            if (!level.isClientSide) finder.sendBack()
            return InteractionResult.SUCCESS
        }
        return BookStands.use(
            level, finder, BookFinderBlockEntity.PLACE, finder.carried, player, hitResult,
            open = { finder.open() }, close = finder::close, take = { finder.handOver(player) },
        )
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING

        /** Whether the carriage is at the finder rather than out on the wall. */
        val HOME: BooleanProperty = BooleanProperty.create("home")

        /** Which of the finder's models a state is: only [FinderPart.STATIC] is placed, and the renderer draws the rest. */
        val PART: EnumProperty<FinderPart> = EnumProperty.create("part", FinderPart::class.java)

        /** Its own frame's indicator, as a frame's ([FrameIndicators]), which the client tints cyan unless it is lit. */
        val INDICATOR: EnumProperty<BookFinderFrameBlock.Indicator> = BookFinderFrameBlock.INDICATOR

        /** A plate three pixels deep against the shelf, facing north as the model does. */
        private val PLATES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(2.0, 2.0, 13.0, 14.0, 14.0, 16.0))
    }
}

/**
 * A book finder frame (Jonah, 2026-11-19): a narrow open frame over the front of a chiselled bookshelf, which is the
 * track a finder's frame runs on. A finder reaches only the shelves that carry one, so a wall of them is built a frame
 * at a time and paid for in the late game's materials. Only its corners are solid, so the shelf's slots are still
 * clicked through it.
 *
 * Its [INDICATOR] says whether a finder reaches it, and lights while the finder is on an errand to its shelf
 * ([FrameIndicators]).
 */
class BookFinderFrameBlock(properties: Properties) : Block(properties) {

    /** What a frame's indicator shows, which the client tints in a rime crystal's colours. */
    enum class Indicator(private val key: String) : StringRepresentable {
        /** A finder reaches it: green. */
        READY("ready"),

        /** No finder reaches it, or the wall is already as large as a finder can search: red. */
        INVALID("invalid"),

        /** Its shelf is the one a book is being fetched from or taken back to: blue. */
        ACTIVE("active"),
        ;

        override fun getSerializedName(): String = key
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(INDICATOR, Indicator.INVALID))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, INDICATOR)
    }

    /**
     * On the side of any block, facing out of it (Jonah, 2026-11-20): over a chiselled bookshelf it reaches the shelf,
     * and over anything else it is only track, for the carriage to travel.
     */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.clickedFace.takeIf { it.axis.isHorizontal } ?: return null
        val state = defaultBlockState().setValue(FACING, facing)
        return state.takeIf { it.canSurvive(context.level, context.clickedPos) }
    }

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        BookFinders.isAgainstABlock(level, pos, state.getValue(FACING))

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
        val lostWhatItStandsOn = directionToNeighbour == state.getValue(FACING).opposite && !state.canSurvive(level, pos)
        return if (lostWhatItStandsOn) Blocks.AIR.defaultBlockState() else state
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        if (level is ServerLevel && !oldState.`is`(this)) FrameIndicators.trackAdded(level, pos, state.getValue(FACING))
    }

    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
        if (!level.getBlockState(pos).`is`(this)) FrameIndicators.trackRemoved(level, pos, state.getValue(FACING))
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        CORNERS.getValue(state.getValue(FACING))

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING

        val INDICATOR: EnumProperty<Indicator> = EnumProperty.create("indicator", Indicator::class.java)

        /** The four corners and the indicator's housing, a pixel deep against the shelf, facing north as the model does. */
        private val CORNERS: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(
            Shapes.or(
                box(0.0, 0.0, 15.0, 2.0, 2.0, 16.0),
                box(14.0, 0.0, 15.0, 16.0, 2.0, 16.0),
                box(0.0, 14.0, 15.0, 2.0, 16.0, 16.0),
                box(14.0, 14.0, 15.0, 16.0, 16.0, 16.0),
                box(6.0, 14.0, 15.0, 10.0, 16.0, 16.0),
            ),
        )
    }
}

/**
 * One of a book finder's models: the [STATIC] frame its block is placed as, and the parts [co.voik.agesandtheart.client.BookFinderRenderer]
 * moves, each modelled where it stands at rest (`scripts/books/book_finder_models.py`).
 */
enum class FinderPart(private val key: String) : StringRepresentable {
    STATIC("static"),
    CHASSIS("chassis"),
    EXTRACTOR("extractor"),
    ROTATOR("rotator"),
    LEFT_PADDLE("left_paddle"),
    RIGHT_PADDLE("right_paddle"),
    LECTERN("lectern"),
    BOOK_SURFACE("book_surface"),
    ;

    override fun getSerializedName(): String = key
}

/**
 * What a book finder's frame is doing. Each that travels has a path; the rest stand at a shelf or at the finder, playing
 * one of [FinderMotions]'s motions forward or backward, or stand aside out of another finder's way. The carriage [isAway]
 * from the finder from setting out until it is back.
 */
enum class FrameTask(val travels: Boolean, val isAway: Boolean) {
    RESTING(false, false),
    FOLDING(false, false),
    GOING_FOR(true, true),
    PULLING(false, true),
    BRINGING(true, true),
    DELIVERING(false, false),
    PRESENTING(false, false),
    CLOSING(false, false),
    STOWING(false, false),
    TAKING_BACK(true, true),
    PUSHING(false, true),
    COMING_BACK(true, true),
    UNFOLDING(false, false),
    STEPPING_ASIDE(true, true),
    STANDING_ASIDE(false, true),
    STEPPING_BACK(true, true),
}

/**
 * A book finder and its frame. The server runs the frame's errands, and tells clients each task as it starts — what it
 * is, when it started, and for one that travels, its path — so every client draws the frame's way along it for
 * itself ([co.voik.agesandtheart.client.BookFinderRenderer]).
 *
 * A second player's request waits its turn; a book held open while one waits goes home after [PATIENCE_TICKS].
 *
 * Finders may share a wall (Jonah, 2026-11-19). Each plans a trip around the others' [Reservation]s, set out when it
 * plans, so carriages never meet though their ways may cross: the way it would take, else one around where they would
 * meet, setting out later if it must. A carriage at rest holds its space for good, so a finder whose way runs through
 * another's waits for it, and that one steps aside to a free space the next time it is idle or holds its book shut,
 * carrying the book, and comes back once nobody needs its space.
 *
 * A way the wall no longer has under it, broken while the carriage is out or before it sets out on a leg, is taken again
 * from where the carriage is ([reroute]): round the gap where it can, home where its errand is cut off, and where home is
 * too, it stands aside on the nearest frame until a way home is mended.
 */
class BookFinderBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(BookFinders.ENTITY, pos, state), BookStand {

    var task: FrameTask = FrameTask.RESTING
        private set

    /** The game time [task] began. */
    var startedAt: Long = 0L
        private set

    /** The way the frame takes on a task that travels. */
    var path: FramePath? = null
        private set

    /** The book in the frame's clamps, or held open. */
    var carried: ItemStack = ItemStack.EMPTY
        private set

    /** The shelf slot the frame is fetching from or taking a book back to, whose frame's indicator is lit for it. */
    var errand: ShelfSlot? = null
        private set(slot) {
            val was = field
            field = slot
            if (was?.shelf != slot?.shelf) FrameIndicators.errandMoved(level, facing, from = was?.shelf, to = slot?.shelf)
        }

    /** Whether the book on the lectern has been opened, which a player does; it is brought shut. */
    var isOpen: Boolean = false
        private set

    /** The way the frame last set out by, which it comes home along. */
    private var outward: FramePath? = null

    /** Where the book held open came from, which is where it goes back to. */
    private var home: ShelfSlot? = null

    /** Where the carriage will be, as last planned, which the other finders on the wall plan around. */
    private var timetable: List<Reservation> = emptyList()

    /** The spaces of the way this finder waits to have cleared, which a carriage resting on one of them makes way from. */
    private var awaited: Set<BlockPos> = emptySet()

    /** Whether the carriage is out of another finder's way, or on its way there or back. */
    private var isMakingWay: Boolean = false

    /** Whether the wall has lost a frame, a shelf or a finder since the carriage last looked to its way. */
    private var isWallChanged: Boolean = false

    /** Whether the book on the lectern is waiting for its way home to clear, and since when. */
    private var isSendingHome: Boolean = false
    private var sendingHomeSince: Long = 0L

    /** The last book taken from the frame and where it came from, so that handing it back sends it home. */
    private var lastTaken: Pair<ItemStack, ShelfSlot>? = null

    private val waiting = ArrayDeque<FetchRequest>()
    private var fetching: FetchRequest? = null

    /** A request: which slot, what was in it when asked, and who asked, and whether they have been told it waits for its way. */
    private class FetchRequest(val from: ShelfSlot, val expected: ItemStack, val asker: UUID) {
        var isToldItWaits: Boolean = false
    }

    /** A trip the carriage can take: its way, how long it waits before setting out, and the wall it holds meanwhile. */
    private class Trip(val way: FramePath, val delay: Int, val reservations: List<Reservation>)

    /**
     * A trip planned: ready to take; waiting for others to clear the [Waiting.way]; not to be had while a carriage at rest
     * stands in the way with nowhere off it to step aside to; or not to be had on this wall at all.
     */
    private sealed interface Planned {
        class Ready(val trip: Trip) : Planned
        class Waiting(val way: FramePath) : Planned
        data object BoxedIn : Planned
        data object Unreachable : Planned
    }

    /**
     * The other finders' holds on the wall, by space, and the spaces they are waiting to have cleared; the spaces held for
     * good, and of those, the ones a carriage rests in at its own finder, which it steps aside from.
     */
    private class Traffic(val held: Map<BlockPos, List<Reservation>>, val awaited: Set<BlockPos>, val resting: Set<BlockPos>) {
        val heldForGood: Set<BlockPos> = held.filterValues { holds -> holds.any { it.until == Reservation.FOR_GOOD } }.keys

        fun allows(plan: List<Reservation>): Boolean = plan.none { mine -> held[mine.space].orEmpty().any(mine::clashesWith) }

        fun clashesIn(plan: List<Reservation>): List<BlockPos> =
            plan.filter { mine -> held[mine.space].orEmpty().any(mine::clashesWith) }.map { it.space }
    }

    /** How sending the book on the lectern home went: off, waiting for its way to clear, or no slot on the wall with room. */
    private enum class SentHome { GOING, WAITING, NO_ROOM }

    val facing: Direction get() = blockState.getValue(BookFinderBlock.FACING)

    fun wall(): BookFinderWall = BookFinderWall.of(level!!, blockPos, facing)

    /** Where the carriage is across the face of the wall, [elapsed] ticks into its task, from the finder's own space. */
    fun carriageAt(elapsed: Double): WallPoint = when (task) {
        FrameTask.PULLING, FrameTask.PUSHING -> errand?.let { WallPoint.of(blockPos, facing, it.shelf) }
        FrameTask.STANDING_ASIDE -> path?.points?.last()
        else -> if (task.travels) path?.at(elapsed * BLOCKS_A_TICK) else null
    } ?: AT_THE_FINDER

    /** The middle of the carriage in the world, at [at] on the wall. */
    fun carriageInTheWorld(at: WallPoint): Vec3 {
        val right = facing.counterClockWise
        return Vec3.atCenterOf(blockPos).add(right.stepX * at.across, at.up, right.stepZ * at.across)
    }

    /** How long [task] lasts, in ticks; one held open lasts until it is taken or sent. */
    fun ticksOf(task: FrameTask): Int = when (task) {
        FrameTask.GOING_FOR, FrameTask.BRINGING, FrameTask.TAKING_BACK, FrameTask.COMING_BACK,
        FrameTask.STEPPING_ASIDE, FrameTask.STEPPING_BACK -> path?.let(Timetable::ticksAlong) ?: 1
        FrameTask.FOLDING, FrameTask.UNFOLDING -> FinderMotions.FOLD.ticks
        FrameTask.PULLING, FrameTask.PUSHING -> FinderMotions.PULL.ticks
        FrameTask.DELIVERING, FrameTask.STOWING -> FinderMotions.DELIVER.ticks
        FrameTask.CLOSING -> SHUT_TICKS
        FrameTask.RESTING, FrameTask.PRESENTING, FrameTask.STANDING_ASIDE -> Int.MAX_VALUE
    }

    /** The wall's books, sent to [player] for their screen. */
    fun listFor(player: ServerPlayer) {
        val books = wall().shelves.flatMap { shelf ->
            val shelved = level?.getBlockEntity(shelf) as? ChiseledBookShelfBlockEntity ?: return@flatMap emptyList()
            (0 until SLOTS).mapNotNull { slot ->
                val book = shelved.getItem(slot)
                if (book.isEmpty) null else FinderBook(shelf, slot, BookFinders.nameOf(book, player.level()), LecternBooks.shownToClients(book))
            }
        }
        Services.NETWORK.sendToPlayer(player, BookFinderListPayload(blockPos, books.sortedBy { it.name.lowercase() }))
    }

    /** [player] asking for the book in [from]: fetched now if the frame is free, and after the others if not. */
    fun request(player: ServerPlayer, from: ShelfSlot) {
        val level = level ?: return
        if (from.slot !in 0 until SLOTS || from.shelf !in wall().shelves) return
        val book = (level.getBlockEntity(from.shelf) as? ChiseledBookShelfBlockEntity)?.getItem(from.slot) ?: return
        if (book.isEmpty) return
        val isBusy = task != FrameTask.RESTING || waiting.isNotEmpty()
        if (isBusy && waiting.size >= MOST_WAITING) return
        waiting.addLast(FetchRequest(from, book.copy(), player.uuid))
        if (isBusy) player.sendOverlayMessage(Component.translatable(QUEUED))
    }

    /** The book on the lectern opened, as a lectern's is. */
    fun open() {
        if (task != FrameTask.PRESENTING || isOpen) return
        isOpen = true
        stopSendingHome()
        changed()
    }

    /** The book on the lectern shut, and left on it. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        play(SoundEvents.BOOK_PAGE_TURN)
        changed()
    }

    /** The book on the lectern, taken by [player]. */
    fun handOver(player: Player) {
        if (task != FrameTask.PRESENTING) return
        val book = carried
        carried = ItemStack.EMPTY
        home?.let { lastTaken = book.copy() to it }
        player.inventory.placeItemBackInInventory(book, Prediction.SERVER_ONLY)
        play(SoundEvents.BOOK_PUT)
        stopSendingHome()
        begin(FrameTask.RESTING)
    }

    /** The book on the lectern taken home, shut first where it is open. */
    fun sendBack() {
        if (task != FrameTask.PRESENTING || isSendingHome) return
        if (isOpen) {
            play(SoundEvents.BOOK_PAGE_TURN)
            begin(FrameTask.CLOSING)
        } else {
            stow()
        }
    }

    /**
     * The book on the lectern taken off it and home; left on it, shut, while the way is not yet clear; and where the wall
     * has no room, dropped.
     */
    private fun stow() {
        val level = level ?: return
        when (takeBack()) {
            SentHome.GOING -> isSendingHome = false
            SentHome.WAITING -> {
                if (!isSendingHome) sendingHomeSince = level.gameTime
                isSendingHome = true
                if (task != FrameTask.PRESENTING) begin(FrameTask.PRESENTING)
            }
            SentHome.NO_ROOM -> {
                stopSendingHome()
                Containers.dropItemStack(level, blockPos.x + HALF, blockPos.y + HALF, blockPos.z + HALF, carried)
                carried = ItemStack.EMPTY
                begin(FrameTask.RESTING)
            }
        }
    }

    private fun stopSendingHome() {
        isSendingHome = false
        awaited = emptySet()
    }

    /**
     * [book] handed to the frame, and laid shut on its lectern to be read or sent home (Jonah, 2026-11-19): home is
     * where it was taken from, where it is the last book taken from the frame.
     */
    fun file(book: ItemStack) {
        home = lastTaken?.takeIf { (taken, _) -> ItemStack.isSameItemSameComponents(taken, book) }?.second
        carried = book
        play(SoundEvents.BOOK_PUT)
        begin(FrameTask.PRESENTING)
    }

    override fun openBookAt(place: Int): ItemStack? =
        carried.takeIf { task == FrameTask.PRESENTING && isOpen && place == PLACE && !it.isEmpty }

    override fun openPlaces(): Collection<Int> = if (openBookAt(PLACE) != null) listOf(PLACE) else emptyList()

    /** On the finder's lectern, as a lectern would hold it. */
    override fun openBookPose(place: Int): OpenBookPose = OpenBookPose(facing, Vec3.atBottomCenterOf(blockPos).add(0.0, OPEN_BOOK_UP, 0.0))

    override fun answer(player: ServerPlayer, place: Int, asked: StandAsked) {
        when (asked) {
            StandAsked.TAKE -> handOver(player)
            StandAsked.CLOSE -> sendBack()
        }
    }

    /** Told by [FrameIndicators] that a frame, a shelf or a finder of its wall is gone; looked into at the next tick. */
    fun wallChanged() {
        isWallChanged = true
    }

    fun tick() {
        val level = level as? ServerLevel ?: return
        if (isWallChanged) {
            isWallChanged = false
            keepToTheWall(level)
        }
        val elapsed = level.gameTime - startedAt
        when (task) {
            FrameTask.RESTING -> rest(level)
            FrameTask.STANDING_ASIDE -> if (isTimeToPlan(level) && !isAwaitedByAnother()) stepBack()
            FrameTask.PRESENTING -> {
                val isTimeToPlan = isTimeToPlan(level)
                if (isTimeToPlan && isAwaitedByAnother()) {
                    // Out of the way with the book, once nobody is reading it, or once they have had their time.
                    if (!isOpen && stepAside()) return
                    if (isOpen && elapsed >= PATIENCE_TICKS) close()
                }
                if (isSendingHome && isTimeToPlan) stow()
                val isWantedElsewhere = isTimeToPlan && elapsed >= PATIENCE_TICKS && waiting.isNotEmpty()
                if (isWantedElsewhere) sendBack()
                // Shut where nobody is near to read it, as a lectern's book is.
                val nobodyReading = isOpen && BookStands.isTimeToLookRound(level) && !BookStands.anybodyNear(level, blockPos)
                if (nobodyReading) {
                    isOpen = false
                    changed()
                }
            }
            else -> if (elapsed >= ticksOf(task)) finish(level, task)
        }
    }

    /**
     * At rest: out of another finder's way where one waits on this space, else off on the first request, which waits
     * at the head of the queue while its way is not clear.
     */
    private fun rest(level: ServerLevel) {
        val isTimeToPlan = isTimeToPlan(level)
        if (isTimeToPlan && isAwaitedByAnother() && stepAside()) return
        val request = waiting.firstOrNull() ?: return
        val isWaitingForItsWay = awaited.isNotEmpty()
        if (isWaitingForItsWay && !isTimeToPlan) return
        if (goFor(request)) waiting.removeFirst()
    }

    /** Whether [request] is settled: set out on, or not to be had. False while its way is not yet clear. */
    private fun goFor(request: FetchRequest): Boolean {
        val wall = wall()
        val target = request.from.shelf.takeIf { it in wall.shelves }?.let(wall::cellOf)
        val planned = target?.let { planTrip(DOCK, it, wall, traffic(wall), outAndBack(it, FinderMotions.FOLD.ticks)) }
        when (planned) {
            null, Planned.Unreachable -> {
                awaited = emptySet()
                tell(request.asker, UNREACHABLE)
            }
            Planned.BoxedIn -> {
                awaited = emptySet()
                tell(request.asker, BOXED_IN)
            }
            is Planned.Waiting -> {
                awaited = spacesOf(planned.way)
                if (!request.isToldItWaits) tell(request.asker, WAY_BLOCKED)
                request.isToldItWaits = true
                return false
            }
            is Planned.Ready -> {
                awaited = emptySet()
                fetching = request
                errand = request.from
                outward = planned.trip.way
                isMakingWay = false
                setOff(FrameTask.FOLDING, planned.trip)
            }
        }
        return true
    }

    /**
     * Out of the way of the finder waiting on this space: to the nearest free space it can reach, if any, the lectern
     * folded first, or the book on it taken off it to be carried there and back.
     */
    private fun stepAside(): Boolean {
        val wall = wall()
        val traffic = traffic(wall)
        val isCarrying = !carried.isEmpty
        val first = if (isCarrying) FrameTask.STOWING else FrameTask.FOLDING
        for (aside in freeSpaces(wall, traffic).take(MOST_SPACES_TRIED)) {
            val planned = planTrip(DOCK, aside, wall, traffic) { way, delay ->
                timetableFrom().stand(DOCK, delay + ticksOf(first)).travel(way).standForGood(aside).reservations
            }
            if (planned is Planned.Ready) {
                outward = planned.trip.way
                isMakingWay = true
                awaited = emptySet()
                setOff(first, planned.trip)
                return true
            }
        }
        return false
    }

    /** Back home from standing aside, once the way is clear. */
    private fun stepBack() {
        val wall = wall()
        val aside = outward?.let { FramePath.cellUnder(it.points.last()) } ?: DOCK
        val planned = planTrip(aside, DOCK, wall, traffic(wall)) { way, delay ->
            timetableFrom().stand(aside, delay).travel(way).standForGood(DOCK).reservations
        }
        if (planned is Planned.Ready) setOff(FrameTask.STEPPING_BACK, planned.trip, way = planned.trip.way)
    }

    /**
     * The spaces of the wall nobody holds or waits on, nearest first along the wall's own ways, where the carriage can
     * stand aside.
     */
    private fun freeSpaces(wall: BookFinderWall, traffic: Traffic): List<WallCell> {
        fun isFree(cell: WallCell): Boolean {
            val space = spaceOf(cell)
            return space !in traffic.held && space !in traffic.awaited
        }
        val seen = linkedSetOf(DOCK)
        val waiting = ArrayDeque(listOf(DOCK))
        while (waiting.isNotEmpty()) {
            val cell = waiting.removeFirst()
            for ((across, up) in ALONG_AND_UP) {
                val next = WallCell(cell.across + across, cell.up + up)
                if (next in wall.room && seen.add(next)) waiting.addLast(next)
            }
        }
        return seen.filter { it != DOCK && isFree(it) }
    }

    /**
     * The first trip from [from] to [to] that meets no other carriage, as [timetableOf] holds the wall for a way and a
     * wait before setting out: the way the frame would take, else one around the spaces where it would meet another,
     * setting out a little later each time until [MOST_DELAY]. With none, it waits on a way only moving carriages hold,
     * else on one through carriages at rest, so long as each of them can step off it.
     */
    private fun planTrip(
        from: WallCell,
        to: WallCell,
        wall: BookFinderWall,
        traffic: Traffic,
        timetableOf: (FramePath, Int) -> List<Reservation>,
    ): Planned {
        val direct = FramePath.between(from, to, wall.room) ?: return Planned.Unreachable
        val meetings = traffic.clashesIn(timetableOf(direct, 0)).mapTo(HashSet(), ::cellOf) - from - to
        val detour = if (meetings.isEmpty()) null else FramePath.between(from, to, wall.room - meetings)
        for (delay in 0..MOST_DELAY step DELAY_STEP) {
            for (way in listOfNotNull(direct, detour)) {
                val plan = timetableOf(way, delay)
                if (traffic.allows(plan)) return Planned.Ready(Trip(way, delay, plan))
            }
        }
        val heldForGood = traffic.heldForGood.mapTo(HashSet(), ::cellOf) - from - to
        val awaitedWay = FramePath.between(from, to, wall.room - heldForGood) ?: direct
        val awaitedSpaces = spacesOf(awaitedWay)
        val restingInTheWay = traffic.resting.filter { it in awaitedSpaces }
        val eachCanStepOff = restingInTheWay.all { canStepOff(it, awaitedSpaces, wall, traffic) }
        return if (eachCanStepOff) Planned.Waiting(awaitedWay) else Planned.BoxedIn
    }

    /**
     * Whether the carriage resting at [space] has somewhere off [way] to step aside to: a space nobody holds for good,
     * reached without passing another carriage at rest, this one's included.
     */
    private fun canStepOff(space: BlockPos, way: Set<BlockPos>, wall: BookFinderWall, traffic: Traffic): Boolean {
        val now = level?.gameTime ?: 0L
        val myOwn = reservationsFrom(now).filter { it.until == Reservation.FOR_GOOD }.map { it.space }
        val passable = { cell: WallCell -> cell in wall.room && spaceOf(cell) !in traffic.heldForGood - space && spaceOf(cell) !in myOwn }
        val start = cellOf(space)
        val seen = hashSetOf(start)
        val waiting = ArrayDeque(listOf(start))
        while (waiting.isNotEmpty()) {
            val cell = waiting.removeFirst()
            if (spaceOf(cell) !in way) return true
            for ((across, up) in ALONG_AND_UP) {
                val next = WallCell(cell.across + across, cell.up + up)
                if (passable(next) && seen.add(next)) waiting.addLast(next)
            }
        }
        return false
    }

    /** Out to [shelf] and back, [beforeLeaving] ticks at the finder first and a pull's at the shelf. */
    private fun outAndBack(shelf: WallCell, beforeLeaving: Int): (FramePath, Int) -> List<Reservation> = { way, delay ->
        timetableFrom()
            .stand(DOCK, delay + beforeLeaving)
            .travel(way)
            .stand(shelf, FinderMotions.PULL.ticks)
            .travel(way.reversed())
            .standForGood(DOCK)
            .reservations
    }

    private fun timetableFrom(): Timetable = Timetable(level?.gameTime ?: 0L, ::spaceOf)

    /** [trip] begun with [first], once it has waited as long as it must, and held to. */
    private fun setOff(first: FrameTask, trip: Trip, way: FramePath? = null) {
        timetable = trip.reservations
        begin(first, way, startsAt = (level?.gameTime ?: 0L) + trip.delay)
    }

    /** Where the other finders on the wall hold it from now on, and what they wait on. */
    private fun traffic(wall: BookFinderWall): Traffic {
        val now = level?.gameTime ?: 0L
        val others = othersOn(wall)
        val holds = others.associateWith { it.reservationsFrom(now) }
        val held = holds.values.flatten().groupBy { it.space }
        val resting = holds.filter { (other, its) -> its.any { it.space == other.blockPos && it.until == Reservation.FOR_GOOD } }.keys
        return Traffic(held, others.flatMapTo(HashSet()) { it.awaited }, resting.mapTo(HashSet()) { it.blockPos })
    }

    private fun othersOn(wall: BookFinderWall): List<BookFinderBlockEntity> = wall.shelves.mapNotNull { shelf ->
        val front = shelf.relative(facing)
        if (front == blockPos) null else level?.getBlockEntity(front) as? BookFinderBlockEntity
    }

    /** This carriage's holds from [now] on: as it planned, or where nothing is planned, its own space for good. */
    private fun reservationsFrom(now: Long): List<Reservation> =
        timetable.filter { it.until >= now }.ifEmpty { listOf(Reservation(blockPos, now, Reservation.FOR_GOOD)) }

    private fun isAwaitedByAnother(): Boolean = othersOn(wall()).any { blockPos in it.awaited }

    private fun isTimeToPlan(level: Level): Boolean = level.gameTime % TICKS_BETWEEN_PLANS == 0L

    /** The block in front of the shelf of [cell], where the carriage stands there. */
    private fun spaceOf(cell: WallCell): BlockPos = blockPos.relative(facing.counterClockWise, cell.across).above(cell.up)

    private fun cellOf(space: BlockPos): WallCell = WallCell.of(blockPos, facing, space.relative(facing.opposite))

    /** Every space [way] passes through. */
    private fun spacesOf(way: FramePath): Set<BlockPos> = Timetable.cellsAlong(way).mapTo(HashSet(), ::spaceOf)

    private fun finish(level: ServerLevel, done: FrameTask) {
        when (done) {
            FrameTask.FOLDING -> then(if (isMakingWay) FrameTask.STEPPING_ASIDE else FrameTask.GOING_FOR, outward)
            FrameTask.GOING_FOR -> {
                val request = fetching
                val shelved = errand?.let { level.getBlockEntity(it.shelf) as? ChiseledBookShelfBlockEntity }
                val book = errand?.let { shelved?.getItem(it.slot) }
                val isStillThere = request != null && book != null && ItemStack.isSameItemSameComponents(book, request.expected)
                if (!isStillThere) {
                    request?.let { tell(it.asker, GONE) }
                    // Back when its timetable says, as if it had drawn the book out.
                    then(FrameTask.COMING_BACK, wayHome(), after = FinderMotions.PULL.ticks)
                    return
                }
                carried = shelved!!.removeItem(errand!!.slot, 1)
                play(SoundEvents.CHISELED_BOOKSHELF_PICKUP, at = errand!!.shelf)
                then(FrameTask.PULLING)
            }
            FrameTask.PULLING -> then(FrameTask.BRINGING, wayHome())
            FrameTask.BRINGING -> {
                home = errand
                fetching = null
                errand = null
                outward = null
                then(FrameTask.DELIVERING)
            }
            FrameTask.DELIVERING -> then(FrameTask.PRESENTING)
            FrameTask.CLOSING -> stow()
            FrameTask.TAKING_BACK -> {
                val there = errand
                val shelved = there?.let { level.getBlockEntity(it.shelf) as? ChiseledBookShelfBlockEntity }
                val slot = there?.let { open -> shelved?.let { emptySlotOn(it, prefer = open.slot) } }
                if (shelved == null || slot == null) {
                    // Filled while it travelled: back to the finder when its timetable says, to be held open again.
                    then(FrameTask.BRINGING, wayHome(), after = FinderMotions.PULL.ticks)
                    return
                }
                errand = ShelfSlot(there.shelf, slot)
                then(FrameTask.PUSHING)
            }
            FrameTask.PUSHING -> {
                val there = errand
                val shelved = there?.let { level.getBlockEntity(it.shelf) as? ChiseledBookShelfBlockEntity }
                if (there != null && shelved != null && shelved.getItem(there.slot).isEmpty) {
                    shelved.setItem(there.slot, carried)
                    carried = ItemStack.EMPTY
                    play(SoundEvents.CHISELED_BOOKSHELF_INSERT, at = there.shelf)
                    then(FrameTask.COMING_BACK, wayHome())
                } else {
                    then(FrameTask.BRINGING, wayHome())
                }
            }
            FrameTask.COMING_BACK -> {
                errand = null
                outward = null
                then(FrameTask.UNFOLDING)
            }
            FrameTask.STOWING -> then(if (isMakingWay) FrameTask.STEPPING_ASIDE else FrameTask.TAKING_BACK, outward)
            FrameTask.UNFOLDING -> then(FrameTask.RESTING)
            FrameTask.STEPPING_ASIDE -> then(FrameTask.STANDING_ASIDE, outward)
            FrameTask.STEPPING_BACK -> {
                isMakingWay = false
                outward = null
                then(if (carried.isEmpty) FrameTask.UNFOLDING else FrameTask.DELIVERING)
            }
            FrameTask.RESTING, FrameTask.PRESENTING, FrameTask.STANDING_ASIDE -> Unit
        }
    }

    /**
     * The book on the lectern taken off it and off to its own slot while that is empty, waiting up to [PATIENCE_TICKS]
     * for the way there to clear (Jonah, 2026-11-19). Where another book has taken its slot, its slot cannot be reached,
     * or the wait is up, it goes to the nearest slot with room whose way is clear, and while every such way waits on other
     * finders, it waits too.
     */
    private fun takeBack(): SentHome {
        val wall = wall()
        val level = level ?: return SentHome.NO_ROOM
        val traffic = traffic(wall)
        fun isEmpty(slot: ShelfSlot) = (level.getBlockEntity(slot.shelf) as? ChiseledBookShelfBlockEntity)?.getItem(slot.slot)?.isEmpty == true
        val ownSlot = home?.takeIf { it.shelf in wall.shelves && isEmpty(it) }
        var firstWaiting: FramePath? = null
        fun tryFor(there: ShelfSlot): Planned {
            val target = wall.cellOf(there.shelf)
            val planned = planTrip(DOCK, target, wall, traffic, outAndBack(target, FinderMotions.DELIVER.ticks))
            when (planned) {
                Planned.Unreachable, Planned.BoxedIn -> Unit
                is Planned.Waiting -> if (firstWaiting == null) firstWaiting = planned.way
                is Planned.Ready -> {
                    errand = there
                    home = null
                    outward = planned.trip.way
                    awaited = emptySet()
                    setOff(FrameTask.STOWING, planned.trip)
                }
            }
            return planned
        }
        if (ownSlot != null) {
            val planned = tryFor(ownSlot)
            if (planned is Planned.Ready) return SentHome.GOING
            val hasWaitedLongEnough = isSendingHome && level.gameTime - sendingHomeSince >= PATIENCE_TICKS
            if (planned is Planned.Waiting && !hasWaitedLongEnough) {
                awaited = spacesOf(planned.way)
                return SentHome.WAITING
            }
        }
        val nearestFirst = wall.shelves.sortedBy { wall.cellOf(it).let { cell -> cell.across * cell.across + cell.up * cell.up } }
        val homeShelf = home?.shelf?.takeIf { it in wall.shelves }
        val withRoom = (listOfNotNull(homeShelf) + nearestFirst).distinct().mapNotNull { shelf ->
            val shelved = level.getBlockEntity(shelf) as? ChiseledBookShelfBlockEntity ?: return@mapNotNull null
            emptySlotOn(shelved, excluding = ownSlot?.takeIf { it.shelf == shelf }?.slot)?.let { ShelfSlot(shelf, it) }
        }
        for (there in withRoom.take(MOST_SPACES_TRIED)) {
            if (tryFor(there) is Planned.Ready) return SentHome.GOING
        }
        val waitingFor = firstWaiting ?: return SentHome.NO_ROOM
        awaited = spacesOf(waitingFor)
        return SentHome.WAITING
    }

    /** An empty slot on [shelved], [prefer] if it is, and never [excluding]. */
    private fun emptySlotOn(shelved: ChiseledBookShelfBlockEntity, prefer: Int? = null, excluding: Int? = null): Int? {
        fun isOpen(slot: Int) = slot != excluding && shelved.getItem(slot).isEmpty
        if (prefer != null && isOpen(prefer)) return prefer
        return (0 until SLOTS).firstOrNull(::isOpen)
    }

    /**
     * Home the way it went, retraced (Jonah, 2026-11-19), rather than a curve of its own that would arrive level and so
     * turn the journey out inside out; a way of its own only where it has none to retrace.
     */
    private fun wayHome(): FramePath = outward?.reversed() ?: wayBetween(errandCell(), DOCK)

    private fun errandCell(): WallCell = errand?.let { wall().cellOf(it.shelf) } ?: DOCK

    /** The frame's way between two spaces, and a straight one where the wall has changed so that there is none. */
    private fun wayBetween(from: WallCell, to: WallCell): FramePath =
        FramePath.between(from, to, wall().room) ?: FramePath(listOf(FramePath.pointOf(from), FramePath.pointOf(to)))

    /**
     * [next] begun as the task before it ends, [after] that, so the carriage keeps to the timetable the others plan
     * around however late its tick; taken again where the wall no longer has its way.
     */
    private fun then(next: FrameTask, way: FramePath? = null, after: Int = 0) {
        val startsAt = startedAt + ticksOf(task) + after
        val isOffTheWall = next.travels && way != null && !fits(way, wall().room)
        if (isOffTheWall) {
            reroute(way.points.first(), next, FramePath.cellUnder(way.points.last()), startsAt)
            return
        }
        begin(next, way, startsAt)
    }

    /**
     * The carriage kept to the wall as it now is: the rest of a way that leaves it taken again from where the carriage
     * is, and a carriage standing where there is no frame any more moved off onto the nearest.
     */
    private fun keepToTheWall(level: ServerLevel) {
        val room = wall().room
        val now = level.gameTime
        val elapsed = (now - startedAt).toDouble()
        val here = carriageAt(elapsed)
        val way = path
        if (task.travels && way != null) {
            val rest = way.after(elapsed * BLOCKS_A_TICK)
            if (!fits(rest, room)) reroute(here, task, FramePath.cellUnder(way.points.last()), startsAt = now)
            return
        }
        val isStandingOffTheWall = task.isAway && Timetable.cellsUnder(here).none { it in room }
        if (isStandingOffTheWall) strand(here, startsAt = now)
    }

    /**
     * The carriage at [from] set on its way to [to] for [next] again, over the wall as it is now, from [startsAt]: from
     * the nearest space of the wall, round whatever is gone and the other carriages. Where [to] is cut off it heads home
     * instead, and where home is too, or no way clears, it stands aside where it is ([strand]).
     */
    private fun reroute(from: WallPoint, next: FrameTask, to: WallCell, startsAt: Long) {
        val wall = wall()
        val start = nearestOnTheWall(from, wall.room) ?: return strand(from, startsAt)
        fun hasAWayBetween(one: WallCell, other: WallCell) = FramePath.between(one, other, wall.room) != null
        val canStillGo = hasAWayBetween(start, to) && hasAWayBetween(to, DOCK)
        val task = when {
            canStillGo -> next
            hasAWayBetween(start, DOCK) -> giveUp(next)
            else -> return strand(from, startsAt)
        }
        val destination = if (task == next) to else DOCK
        val isOutward = task == FrameTask.GOING_FOR || task == FrameTask.TAKING_BACK
        val wayOut = if (isOutward) FramePath.between(DOCK, destination, wall.room) else null
        val planned = planTrip(start, destination, wall, traffic(wall)) { way, delay ->
            val there = Timetable(startsAt, ::spaceOf).standAt(from, delay).travel(joined(from, way))
            val thereAndBack = wayOut?.let {
                there.stand(destination, FinderMotions.PULL.ticks).travel(it.reversed()).standForGood(DOCK)
            }
            (thereAndBack ?: there.standForGood(destination)).reservations
        }
        if (planned !is Planned.Ready) return strand(from, startsAt)
        val way = joined(from, planned.trip.way)
        timetable = holdsBefore(startsAt) + planned.trip.reservations
        when {
            wayOut != null -> outward = wayOut
            task == FrameTask.STEPPING_ASIDE -> outward = way
        }
        begin(task, way, startsAt + planned.trip.delay)
    }

    /** The task that heads home from [next], whose errand is cut off, the request it was for given up. */
    private fun giveUp(next: FrameTask): FrameTask = when (next) {
        FrameTask.GOING_FOR -> {
            fetching?.let { tell(it.asker, UNREACHABLE) }
            fetching = null
            errand = null
            FrameTask.COMING_BACK
        }
        FrameTask.TAKING_BACK -> FrameTask.BRINGING
        FrameTask.STEPPING_ASIDE -> FrameTask.STEPPING_BACK
        else -> next
    }

    /**
     * The carriage at [from], with no way it can take, stepped onto the nearest space of the wall from [startsAt] to stand
     * aside there until a way home clears; the request it was for given up, and a book it holds kept to be brought home.
     */
    private fun strand(from: WallPoint, startsAt: Long) {
        if (carried.isEmpty) fetching?.let { tell(it.asker, UNREACHABLE) } else home = errand ?: home
        fetching = null
        errand = null
        val there = nearestOnTheWall(from, wall().room) ?: FramePath.cellUnder(from)
        val hop = FramePath(listOf(from, FramePath.pointOf(there)))
        outward = hop
        isMakingWay = true
        timetable = holdsBefore(startsAt) + Timetable(startsAt, ::spaceOf).travel(hop).standForGood(there).reservations
        begin(FrameTask.STEPPING_ASIDE, hop, startsAt)
    }

    /** The space of [room] nearest [point]: of those the carriage is partly in, or failing them, their neighbours. */
    private fun nearestOnTheWall(point: WallPoint, room: Set<WallCell>): WallCell? {
        val under = Timetable.cellsUnder(point)
        val beside = under.flatMap { cell -> ALONG_AND_UP.map { (across, up) -> WallCell(cell.across + across, cell.up + up) } }
        val near = under.filter { it in room }.ifEmpty { beside.filter { it in room } }
        return near.minByOrNull { FramePath.pointOf(it).distanceTo(point) }
    }

    /** [way], which sets out from a space of the wall, set out on from [from] instead. */
    private fun joined(from: WallPoint, way: FramePath): FramePath = FramePath(listOf(from) + way.points)

    /** Whether every space [way] passes is in [room]. */
    private fun fits(way: FramePath, room: Set<WallCell>): Boolean = Timetable.cellsAlong(way).all { it in room }

    /** The holds of the timetable for what the carriage does before [startsAt]. */
    private fun holdsBefore(startsAt: Long): List<Reservation> = timetable.filter { it.from < startsAt }

    private fun begin(next: FrameTask, way: FramePath? = null, startsAt: Long = level?.gameTime ?: 0L) {
        task = next
        path = way
        startedAt = startsAt
        if (next != FrameTask.PRESENTING) isOpen = false
        home(!next.isAway)
        changed()
    }

    private fun home(isHome: Boolean) {
        val level = level ?: return
        if (blockState.getValue(BookFinderBlock.HOME) != isHome) {
            level.setBlock(blockPos, blockState.setValue(BookFinderBlock.HOME, isHome), Block.UPDATE_ALL)
        }
    }

    private fun tell(asker: UUID, key: String) {
        (level?.getPlayerByUUID(asker) as? ServerPlayer)?.sendOverlayMessage(Component.translatable(key))
    }

    private fun play(sound: SoundEvent, at: BlockPos = blockPos) {
        val level = level as? ServerLevel ?: return
        level.playSound(null, at, sound, SoundSource.BLOCKS, 1.0f, 1.0f)
    }

    private fun changed() {
        setChanged()
        val level = level ?: return
        if (!level.isClientSide) level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    /** Broken, the book in its frame drops where the frame is, and the shelf it was on an errand to goes dark. */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        val level = level ?: return
        errand?.let { FrameIndicators.errandMoved(level, facing, from = it.shelf, to = null) }
        if (level.isClientSide || carried.isEmpty) return
        Containers.dropItemStack(level, pos.x + HALF, pos.y + HALF, pos.z + HALF, carried)
        carried = ItemStack.EMPTY
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        task = input.read(TASK_KEY, Codec.STRING).map { name -> FrameTask.entries.firstOrNull { it.name == name } }.orElse(null) ?: FrameTask.RESTING
        startedAt = input.getLongOr(STARTED_KEY, 0L)
        path = readPath(input, PATH_KEY)
        carried = input.read(CARRIED_KEY, ItemStack.CODEC).orElse(ItemStack.EMPTY)
        errand = readSlot(input, ERRAND_KEY)
        isOpen = input.getBooleanOr(OPEN_KEY, false)
        home = readSlot(input, HOME_KEY)
        outward = readPath(input, OUTWARD_KEY)
        timetable = input.read(TIMETABLE_KEY, Codec.LONG.listOf()).map { flat ->
            flat.chunked(RESERVATION_LONGS).filter { it.size == RESERVATION_LONGS }.map { (x, y, z, from, until) ->
                Reservation(BlockPos(x.toInt(), y.toInt(), z.toInt()), from, until)
            }
        }.orElse(emptyList())
        isMakingWay = input.getBooleanOr(MAKING_WAY_KEY, false)
        isSendingHome = input.getBooleanOr(SENDING_HOME_KEY, false)
        sendingHomeSince = input.getLongOr(SENDING_HOME_SINCE_KEY, 0L)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        writeTheFrame(output, carried)
        home?.let { writeSlot(output, HOME_KEY, it) }
        outward?.let { writePath(output, OUTWARD_KEY, it) }
        val flatTimetable = timetable.flatMap { listOf(it.space.x.toLong(), it.space.y.toLong(), it.space.z.toLong(), it.from, it.until) }
        output.store(TIMETABLE_KEY, Codec.LONG.listOf(), flatTimetable)
        output.putBoolean(MAKING_WAY_KEY, isMakingWay)
        output.putBoolean(SENDING_HOME_KEY, isSendingHome)
        output.putLong(SENDING_HOME_SINCE_KEY, sendingHomeSince)
    }

    private fun readPath(input: ValueInput, key: String): FramePath? =
        input.read(key, Codec.DOUBLE.listOf()).map { flat ->
            FramePath(flat.chunked(2).filter { it.size == 2 }.map { (across, up) -> WallPoint(across, up) })
        }.orElse(null)?.takeIf { it.points.size >= 2 }

    private fun writePath(output: ValueOutput, key: String, way: FramePath) {
        output.store(key, Codec.DOUBLE.listOf(), way.points.flatMap { listOf(it.across, it.up) })
    }

    private fun writeTheFrame(output: ValueOutput, book: ItemStack) {
        output.store(TASK_KEY, Codec.STRING, task.name)
        output.putLong(STARTED_KEY, startedAt)
        path?.let { writePath(output, PATH_KEY, it) }
        if (!book.isEmpty) output.store(CARRIED_KEY, ItemStack.CODEC, book)
        errand?.let { writeSlot(output, ERRAND_KEY, it) }
        output.putBoolean(OPEN_KEY, isOpen)
    }

    private fun readSlot(input: ValueInput, key: String): ShelfSlot? =
        input.read(key, Codec.INT.listOf()).map { ints ->
            if (ints.size == SLOT_INTS) ShelfSlot(BlockPos(ints[0], ints[1], ints[2]), ints[3].coerceIn(0, SLOTS - 1)) else null
        }.orElse(null)

    private fun writeSlot(output: ValueOutput, key: String, slot: ShelfSlot) {
        output.store(key, Codec.INT.listOf(), listOf(slot.shelf.x, slot.shelf.y, slot.shelf.z, slot.slot))
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    /** What a client draws the frame from; the Age behind a linking book stays on the server, as on a lectern. */
    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        val tag = CompoundTag()
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries)
        writeTheFrame(output, LecternBooks.shownToClients(carried))
        tag.merge(output.buildResult())
        return tag
    }

    companion object {
        /** The finder's own space. */
        val DOCK = WallCell(0, 0)
        private val AT_THE_FINDER = WallPoint(0.0, 0.0)

        /** A chiselled bookshelf's six slots. */
        const val SLOTS = 6

        /** Four blocks a second across the wall. */
        const val BLOCKS_A_TICK = 0.2

        /** An open book swinging shut before it is taken off the lectern: as long as a lectern's book takes. */
        private const val SHUT_TICKS = 10

        /** The book on the lectern lies as a lectern's, twelve pixels lower: the lectern is vanilla's, sunk that far. */
        const val OPEN_BOOK_UP = 5.0 / 16.0

        /** How long a book held open waits for another's request, or another finder's way, before going home: half a minute. */
        private const val PATIENCE_TICKS = 600L

        /** How often a finder waiting on the wall's traffic looks again: twice a second. */
        private const val TICKS_BETWEEN_PLANS = 10L

        /** The longest a carriage waits at the finder for its way to clear, and the steps it tries waiting by. */
        private const val MOST_DELAY = 600
        private const val DELAY_STEP = 10

        /** The most free spaces tried for standing aside, and slots with room tried for a book sent home. */
        private const val MOST_SPACES_TRIED = 8

        private val ALONG_AND_UP = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private const val RESERVATION_LONGS = 5
        private const val MOST_WAITING = 8
        private const val HALF = 0.5
        private const val SLOT_INTS = 4

        private const val TASK_KEY = "task"
        private const val STARTED_KEY = "started_at"
        private const val PATH_KEY = "path"
        private const val CARRIED_KEY = "carried"
        private const val ERRAND_KEY = "errand"
        private const val OPEN_KEY = "open"
        private const val HOME_KEY = "home"
        private const val OUTWARD_KEY = "outward"
        private const val TIMETABLE_KEY = "timetable"
        private const val MAKING_WAY_KEY = "making_way"
        private const val SENDING_HOME_KEY = "sending_home"
        private const val SENDING_HOME_SINCE_KEY = "sending_home_since"

        /** The finder holds one book, in its frame. */
        const val PLACE = 0

        private const val QUEUED = "block.agesandtheart.book_finder.queued"
        private const val GONE = "block.agesandtheart.book_finder.gone"
        private const val UNREACHABLE = "block.agesandtheart.book_finder.unreachable"
        private const val WAY_BLOCKED = "block.agesandtheart.book_finder.way_blocked"
        private const val BOXED_IN = "block.agesandtheart.book_finder.boxed_in"
    }
}

/** The finder's registrations, for [co.voik.agesandtheart.content.AgeContent]'s lists, and what its parts share. */
object BookFinders {
    val ID: Identifier = "book_finder".location()

    private const val STRENGTH = 3.0f

    val BLOCK: BookFinderBlock = BookFinderBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.COLOR_ORANGE)
            .strength(STRENGTH)
            .sound(SoundType.COPPER)
            .noOcclusion()
            .pushReaction(PushReaction.POPPED),
    )

    val ITEM: Item = BlockItem(BLOCK, Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix())

    val FRAME_ID: Identifier = "book_finder_frame".location()

    val FRAME: BookFinderFrameBlock = BookFinderFrameBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, FRAME_ID))
            .mapColor(MapColor.COLOR_ORANGE)
            .strength(STRENGTH)
            .sound(SoundType.COPPER)
            .noOcclusion()
            .pushReaction(PushReaction.POPPED),
    )

    val FRAME_ITEM: Item =
        BlockItem(FRAME, Item.Properties().setId(ResourceKey.create(Registries.ITEM, FRAME_ID)).useBlockDescriptionPrefix())

    /**
     * A finder or a frame used on a chiselled bookshelf is put on it, rather than the shelf taking a book out or refusing
     * it (Jonah, 2026-11-19): which is plainly what a player holding one wants, and spares them sneaking. Null for
     * anything else, which vanilla handles.
     */
    fun placedOnAShelf(player: Player, level: Level, hand: InteractionHand, hitResult: BlockHitResult): InteractionResult? {
        val held = player.getItemInHand(hand)
        val isOneOfOurs = held.`is`(ITEM) || held.`is`(FRAME_ITEM)
        if (!isOneOfOurs || !level.getBlockState(hitResult.blockPos).`is`(Blocks.CHISELED_BOOKSHELF)) return null
        return held.useOn(UseOnContext(player, hand, hitResult))
    }

    /** Whether something at [pos] facing [facing] is mounted on the side of a block, which any but air or the like is. */
    fun isAgainstABlock(level: BlockGetter, pos: BlockPos, facing: Direction): Boolean =
        canCarryATrack(level.getBlockState(pos.relative(facing.opposite)))

    /** Whether a frame or a finder may be built against [state]: anything solid enough not to be built over. */
    fun canCarryATrack(state: BlockState): Boolean = !state.isAir && !state.canBeReplaced()

    /** Whether [state] is a chiselled bookshelf facing [facing], which a finder on the wall in front of it serves. */
    fun isAShelfFacing(state: BlockState, facing: Direction): Boolean =
        state.`is`(Blocks.CHISELED_BOOKSHELF) && state.getValue(ChiseledBookShelfBlock.FACING) == facing

    val ENTITY: BlockEntityType<BookFinderBlockEntity> = BlockEntityType({ pos, state -> BookFinderBlockEntity(pos, state) }, setOf(BLOCK))

    private val TRAVEL_SOUND_ID: Identifier = "block.book_finder.travel".location()
    private val JUNCTION_SOUND_ID: Identifier = "block.book_finder.junction".location()
    private val EXTRACTOR_SOUND_ID: Identifier = "block.book_finder.extractor".location()
    private val SURFACE_SOUND_ID: Identifier = "block.book_finder.surface".location()

    /**
     * The carriage at work (Jonah, 2026-11-19): rolling along the wall, over each junction of frames, and the actuators
     * of its extractor and of its lectern's surface. Vanilla's stand in, named in `sounds.json`, until the asset pass.
     */
    val TRAVEL_SOUND: SoundEvent = SoundEvent.createVariableRangeEvent(TRAVEL_SOUND_ID)
    val JUNCTION_SOUND: SoundEvent = SoundEvent.createVariableRangeEvent(JUNCTION_SOUND_ID)
    val EXTRACTOR_SOUND: SoundEvent = SoundEvent.createVariableRangeEvent(EXTRACTOR_SOUND_ID)
    val SURFACE_SOUND: SoundEvent = SoundEvent.createVariableRangeEvent(SURFACE_SOUND_ID)

    val soundEvents: List<Pair<Identifier, SoundEvent>> = listOf(
        TRAVEL_SOUND_ID to TRAVEL_SOUND,
        JUNCTION_SOUND_ID to JUNCTION_SOUND,
        EXTRACTOR_SOUND_ID to EXTRACTOR_SOUND,
        SURFACE_SOUND_ID to SURFACE_SOUND,
    )

    /** A book as the finder's list names it: its name, and an enchanted book's enchantments after it. */
    fun nameOf(book: ItemStack, level: Level): String {
        val name = book.hoverName.string
        val enchantments = book.get(DataComponents.STORED_ENCHANTMENTS) ?: return name
        val listed = enchantments.entrySet().joinToString(", ") { (enchantment, level) ->
            Enchantment.getFullname(enchantment, level).string
        }
        return if (listed.isEmpty()) name else "$name: $listed"
    }

    fun handleFetch(player: ServerPlayer, payload: BookFinderFetchPayload) {
        finderInReach(player, payload.finder)?.request(player, ShelfSlot(payload.shelf, payload.slot))
    }

    private fun finderInReach(player: ServerPlayer, pos: BlockPos): BookFinderBlockEntity? {
        val level = player.level()
        if (!level.isLoaded(pos) || !player.position().closerThan(Vec3.atCenterOf(pos), LecternBooks.PANEL_HELD_WITHIN_BLOCKS)) return null
        return level.getBlockEntity(pos) as? BookFinderBlockEntity
    }
}
