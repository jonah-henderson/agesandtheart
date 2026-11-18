package co.voik.agesandtheart.book

import co.voik.agesandtheart.client.BookFinderOpener
import co.voik.agesandtheart.client.LinkTransitOpener
import co.voik.agesandtheart.location
import co.voik.agesandtheart.page.PageLearning
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
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties
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
import kotlin.math.ceil

/** One slot of one chiselled bookshelf. */
data class ShelfSlot(val shelf: BlockPos, val slot: Int)

/**
 * The book finder (design §7.8.3): mounted on the face of a wall of chiselled bookshelves, it searches
 * every book on the wall, and on a choice its frame goes across the face of the wall to the shelf, draws the book out,
 * brings it back shut in its clamps and holds it open as a lectern does, for the player to read where it stands.
 *
 * Used with an empty hand while the frame is free, it lists the wall's books. Used while it holds a book open, it is
 * read as a lectern's is: a book of ours links from its panel and opens its screen anywhere else, a written book opens
 * its pages, and any other book is taken; sneaking sends the book home. A book handed to it is filed: home, where it
 * was the last one taken from the frame, and otherwise on the nearest empty slot.
 */
class BookFinderBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HOLDING, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, HOLDING)
    }

    /** Only on the face of a chiselled bookshelf, facing out of it. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.clickedFace.takeIf { it.axis.isHorizontal } ?: return null
        val state = defaultBlockState().setValue(FACING, facing)
        return state.takeIf { it.canSurvive(context.level, context.clickedPos) }
    }

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        val facing = state.getValue(FACING)
        val behind = level.getBlockState(pos.relative(facing.opposite))
        return behind.`is`(Blocks.CHISELED_BOOKSHELF) && behind.getValue(ChiseledBookShelfBlock.FACING) == facing
    }

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
        val lostItsShelf = directionToNeighbour == state.getValue(FACING).opposite && !state.canSurvive(level, pos)
        return if (lostItsShelf) Blocks.AIR.defaultBlockState() else state
    }

    /** The plate on the shelf, and while it holds a book open, the whole space, so the book can be clicked. */
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        if (state.getValue(HOLDING)) Shapes.block() else PLATES.getValue(state.getValue(FACING))

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        PLATES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BookFinderBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null else createTickerHelper(type, BookFinders.ENTITY) { _, _, _, finder -> finder.tick() }

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
        if (!level.isClientSide) finder.file(player, stack.consumeAndReturn(1, player))
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val finder = level.getBlockEntity(pos) as? BookFinderBlockEntity ?: return InteractionResult.PASS
        if (finder.task != FrameTask.PRESENTING) {
            if (player is ServerPlayer) finder.listFor(player)
            return InteractionResult.SUCCESS
        }
        if (player.isSecondaryUseActive) {
            if (!level.isClientSide) finder.sendBack()
            return InteractionResult.SUCCESS
        }
        val book = finder.carried
        val panelPage = LecternBooks.panelPageOf(book)
        when {
            panelPage != null -> {
                val spot = LecternBookPlane.spotLookedAt(
                    state.getValue(FACING), finder.bookCorner, BookBinding.of(book), player.eyePosition, hitResult.location,
                )
                val onThePanel = spot != null && spot.page == panelPage && spot.withinThePanel
                if (onThePanel) linkFrom(level, finder, player) else read(level, finder, player)
            }
            BookFinders.isReadable(book) -> if (level.isClientSide) BookFinderOpener.readWritten(book, pos)
            else -> if (!level.isClientSide) finder.handOver(player)
        }
        return InteractionResult.SUCCESS
    }

    /** Goes, leaving the book in the frame for the next reader, as a lectern's does. */
    private fun linkFrom(level: Level, finder: BookFinderBlockEntity, player: Player) {
        if (level.isClientSide) LinkTransitOpener.begin(BookBeingRead.InAFinder(finder.blockPos))
        if (level !is ServerLevel || player !is ServerPlayer) return
        if (Linking.go(player, level, finder.carried)) finder.setChanged() else Linking.endedWithoutGoing(player)
    }

    private fun read(level: Level, finder: BookFinderBlockEntity, player: Player) {
        if (level.isClientSide) BookFinderOpener.readOurs(finder.carried, finder.blockPos)
        if (player is ServerPlayer) PageLearning.study(player, finder.carried)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING

        /** Whether its frame holds a book open, which is when the book is there to be clicked. */
        val HOLDING: BooleanProperty = BooleanProperty.create("holding")

        /** A plate three pixels deep against the shelf, facing north as the model does. */
        private val PLATES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(2.0, 2.0, 13.0, 14.0, 14.0, 16.0))
    }
}

/** What a book finder's frame is doing. Each that travels has a path; the rest stand at a shelf or at the finder. */
enum class FrameTask(val travels: Boolean) {
    RESTING(false),
    GOING_FOR(true),
    PULLING(false),
    BRINGING(true),
    OPENING(false),
    PRESENTING(false),
    CLOSING(false),
    TAKING_BACK(true),
    PUSHING(false),
    COMING_BACK(true),
}

/**
 * A book finder and its frame. The server runs the frame's errands, and tells clients each task as it starts — what it
 * is, when it started, and for one that travels, its path — so every client draws the frame's way along it for
 * itself ([co.voik.agesandtheart.client.BookFinderRenderer]).
 *
 * A second player's request waits its turn; a book held open while one waits goes home after [PATIENCE_TICKS].
 */
class BookFinderBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(BookFinders.ENTITY, pos, state) {

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

    /** The shelf slot the frame is fetching from or taking a book back to. */
    var errand: ShelfSlot? = null
        private set

    /** Where the book held open came from, which is where it goes back to. */
    private var home: ShelfSlot? = null

    /** The last book taken from the frame and where it came from, so that handing it back sends it home. */
    private var lastTaken: Pair<ItemStack, ShelfSlot>? = null

    private val waiting = ArrayDeque<FetchRequest>()
    private var fetching: FetchRequest? = null

    /** A request: which slot, what was in it when asked, and who asked. */
    private class FetchRequest(val from: ShelfSlot, val expected: ItemStack, val asker: UUID)

    val facing: Direction get() = blockState.getValue(BookFinderBlock.FACING)

    /** Where a lectern's corner would be for its book to be held as this frame holds one: in the middle of the finder's space. */
    val bookCorner: Vec3 get() = Vec3.atLowerCornerOf(blockPos).add(0.0, BOOK_LOWER_THAN_A_LECTERNS, 0.0)

    fun wall(): BookFinderWall = BookFinderWall.of(level!!, blockPos, facing)

    /** How long [task] lasts, in ticks; one held open lasts until it is taken or sent. */
    fun ticksOf(task: FrameTask): Int = when {
        task.travels -> ceil((path?.length ?: 0.0) / BLOCKS_A_TICK).toInt().coerceAtLeast(1)
        task == FrameTask.PULLING || task == FrameTask.PUSHING -> SLIDE_TICKS
        task == FrameTask.OPENING || task == FrameTask.CLOSING -> TURN_TICKS
        else -> Int.MAX_VALUE
    }

    /** The wall's books, sent to [player] for their screen. */
    fun listFor(player: ServerPlayer) {
        val books = wall().shelves.flatMap { shelf ->
            val shelved = level?.getBlockEntity(shelf) as? ChiseledBookShelfBlockEntity ?: return@flatMap emptyList()
            (0 until SLOTS).mapNotNull { slot ->
                val book = shelved.getItem(slot)
                if (book.isEmpty) null else FinderBook(shelf, slot, BookFinders.nameOf(book, player.level()))
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

    /** The book held open, taken by [player]. */
    fun handOver(player: Player) {
        if (task != FrameTask.PRESENTING) return
        val book = carried
        carried = ItemStack.EMPTY
        home?.let { lastTaken = book.copy() to it }
        player.inventory.placeItemBackInInventory(book, Prediction.SERVER_ONLY)
        play(SoundEvents.BOOK_PUT)
        begin(FrameTask.RESTING)
    }

    /** The book held open, shut and taken home. */
    fun sendBack() {
        if (task != FrameTask.PRESENTING) return
        play(SoundEvents.BOOK_PAGE_TURN)
        begin(FrameTask.CLOSING)
    }

    /** [book] handed to the frame by [player], and taken to its home or the nearest empty slot. */
    fun file(player: Player, book: ItemStack) {
        val remembered = lastTaken?.takeIf { (taken, _) -> ItemStack.isSameItemSameComponents(taken, book) }?.second
        carried = book
        home = remembered
        if (!takeBack()) {
            player.inventory.placeItemBackInInventory(carried, Prediction.SERVER_ONLY)
            carried = ItemStack.EMPTY
            player.sendOverlayMessage(Component.translatable(NO_ROOM))
        }
    }

    /** Answered from a book's screen held open in this frame, by a player in reach of it. */
    fun answer(player: ServerPlayer, asked: FinderAsked) {
        if (!player.position().closerThan(Vec3.atCenterOf(blockPos), LecternBooks.PANEL_HELD_WITHIN_BLOCKS)) return
        when (asked) {
            FinderAsked.TAKE -> if (player.mayBuild()) handOver(player)
            FinderAsked.SEND_BACK -> sendBack()
        }
    }

    fun tick() {
        val level = level as? ServerLevel ?: return
        val elapsed = level.gameTime - startedAt
        when (task) {
            FrameTask.RESTING -> waiting.removeFirstOrNull()?.let(::goFor)
            FrameTask.PRESENTING -> if (waiting.isNotEmpty() && elapsed >= PATIENCE_TICKS) sendBack()
            else -> if (elapsed >= ticksOf(task)) finish(level, task)
        }
    }

    private fun goFor(request: FetchRequest) {
        val wall = wall()
        val way = request.from.shelf.takeIf { it in wall.shelves }?.let { FramePath.between(DOCK, wall.cellOf(it), wall.room) }
        if (way == null) {
            tell(request.asker, UNREACHABLE)
            return
        }
        fetching = request
        errand = request.from
        begin(FrameTask.GOING_FOR, way)
    }

    private fun finish(level: ServerLevel, done: FrameTask) {
        when (done) {
            FrameTask.GOING_FOR -> {
                val request = fetching
                val shelved = errand?.let { level.getBlockEntity(it.shelf) as? ChiseledBookShelfBlockEntity }
                val book = errand?.let { shelved?.getItem(it.slot) }
                val isStillThere = request != null && book != null && ItemStack.isSameItemSameComponents(book, request.expected)
                if (!isStillThere) {
                    request?.let { tell(it.asker, GONE) }
                    comeBack()
                    return
                }
                carried = shelved!!.removeItem(errand!!.slot, 1)
                play(SoundEvents.CHISELED_BOOKSHELF_PICKUP, at = errand!!.shelf)
                begin(FrameTask.PULLING)
            }
            FrameTask.PULLING -> begin(FrameTask.BRINGING, wayBetween(errandCell(), DOCK))
            FrameTask.BRINGING -> {
                home = errand
                fetching = null
                errand = null
                // A blank descriptive book is written where it is first opened, as on a lectern.
                DescriptiveBookItem.writeIfBlank(carried, level)
                LinkingBookItem.clearStrayWriting(carried)
                holding(true)
                play(SoundEvents.BOOK_PAGE_TURN)
                begin(FrameTask.OPENING)
            }
            FrameTask.OPENING -> begin(FrameTask.PRESENTING)
            FrameTask.CLOSING -> {
                holding(false)
                if (!takeBack()) {
                    Containers.dropItemStack(level, blockPos.x + HALF, blockPos.y + HALF, blockPos.z + HALF, carried)
                    carried = ItemStack.EMPTY
                    begin(FrameTask.RESTING)
                }
            }
            FrameTask.TAKING_BACK -> {
                val there = errand
                val shelved = there?.let { level.getBlockEntity(it.shelf) as? ChiseledBookShelfBlockEntity }
                val slot = there?.let { open -> shelved?.let { emptySlotOn(it, prefer = open.slot) } }
                if (shelved == null || slot == null) {
                    // Filled while it travelled: back to the finder, to be held open again.
                    begin(FrameTask.BRINGING, wayBetween(errandCell(), DOCK))
                    return
                }
                errand = ShelfSlot(there.shelf, slot)
                begin(FrameTask.PUSHING)
            }
            FrameTask.PUSHING -> {
                val there = errand
                val shelved = there?.let { level.getBlockEntity(it.shelf) as? ChiseledBookShelfBlockEntity }
                if (there != null && shelved != null && shelved.getItem(there.slot).isEmpty) {
                    shelved.setItem(there.slot, carried)
                    carried = ItemStack.EMPTY
                    play(SoundEvents.CHISELED_BOOKSHELF_INSERT, at = there.shelf)
                    comeBack()
                } else {
                    begin(FrameTask.BRINGING, wayBetween(errandCell(), DOCK))
                }
            }
            FrameTask.COMING_BACK -> {
                errand = null
                begin(FrameTask.RESTING)
            }
            FrameTask.RESTING, FrameTask.PRESENTING -> Unit
        }
    }

    /**
     * Off with the book in the clamps to its home, where that slot is empty, or the nearest shelf with room; false where
     * the wall has none.
     */
    private fun takeBack(): Boolean {
        val wall = wall()
        val level = level ?: return false
        val homeShelf = home?.takeIf { it.shelf in wall.shelves }?.shelf
        val ordered = listOfNotNull(homeShelf) + wall.shelves.sortedBy { wall.cellOf(it).let { cell -> cell.across * cell.across + cell.up * cell.up } }
        for (shelf in ordered) {
            val shelved = level.getBlockEntity(shelf) as? ChiseledBookShelfBlockEntity ?: continue
            val slot = emptySlotOn(shelved, prefer = home?.takeIf { it.shelf == shelf }?.slot) ?: continue
            val way = FramePath.between(DOCK, wall.cellOf(shelf), wall.room) ?: continue
            errand = ShelfSlot(shelf, slot)
            home = null
            begin(FrameTask.TAKING_BACK, way)
            return true
        }
        return false
    }

    private fun emptySlotOn(shelved: ChiseledBookShelfBlockEntity, prefer: Int?): Int? {
        if (prefer != null && shelved.getItem(prefer).isEmpty) return prefer
        return (0 until SLOTS).firstOrNull { shelved.getItem(it).isEmpty }
    }

    private fun comeBack() {
        begin(FrameTask.COMING_BACK, wayBetween(errandCell(), DOCK))
    }

    private fun errandCell(): WallCell = errand?.let { wall().cellOf(it.shelf) } ?: DOCK

    /** The frame's way between two spaces, and a straight one where the wall has changed so that there is none. */
    private fun wayBetween(from: WallCell, to: WallCell): FramePath =
        FramePath.between(from, to, wall().room) ?: FramePath(listOf(FramePath.pointOf(from), FramePath.pointOf(to)))

    private fun begin(next: FrameTask, way: FramePath? = null) {
        task = next
        path = way
        startedAt = level?.gameTime ?: 0L
        changed()
    }

    private fun holding(isHolding: Boolean) {
        val level = level ?: return
        if (blockState.getValue(BookFinderBlock.HOLDING) != isHolding) {
            level.setBlock(blockPos, blockState.setValue(BookFinderBlock.HOLDING, isHolding), Block.UPDATE_ALL)
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

    /** Broken, the book in its frame drops where the frame is. */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        val level = level ?: return
        if (level.isClientSide || carried.isEmpty) return
        Containers.dropItemStack(level, pos.x + HALF, pos.y + HALF, pos.z + HALF, carried)
        carried = ItemStack.EMPTY
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        task = input.read(TASK_KEY, Codec.STRING).map { name -> FrameTask.entries.firstOrNull { it.name == name } }.orElse(null) ?: FrameTask.RESTING
        startedAt = input.getLongOr(STARTED_KEY, 0L)
        path = input.read(PATH_KEY, Codec.DOUBLE.listOf()).map { flat ->
            FramePath(flat.chunked(2).filter { it.size == 2 }.map { (across, up) -> WallPoint(across, up) })
        }.orElse(null)?.takeIf { it.points.size >= 2 }
        carried = input.read(CARRIED_KEY, ItemStack.CODEC).orElse(ItemStack.EMPTY)
        errand = readSlot(input, ERRAND_KEY)
        home = readSlot(input, HOME_KEY)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        writeTheFrame(output, carried)
        home?.let { writeSlot(output, HOME_KEY, it) }
    }

    private fun writeTheFrame(output: ValueOutput, book: ItemStack) {
        output.store(TASK_KEY, Codec.STRING, task.name)
        output.putLong(STARTED_KEY, startedAt)
        path?.let { way -> output.store(PATH_KEY, Codec.DOUBLE.listOf(), way.points.flatMap { listOf(it.across, it.up) }) }
        if (!book.isEmpty) output.store(CARRIED_KEY, ItemStack.CODEC, book)
        errand?.let { writeSlot(output, ERRAND_KEY, it) }
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

        /** A chiselled bookshelf's six slots. */
        const val SLOTS = 6

        /** Four blocks a second across the wall. */
        const val BLOCKS_A_TICK = 0.2

        /** A book drawn out of its slot or pushed back in. */
        const val SLIDE_TICKS = 12

        /** A book turned from the clamps and opened, or shut and turned back. */
        const val TURN_TICKS = 16

        /** How long a book held open waits for another's request before going home: half a minute. */
        private const val PATIENCE_TICKS = 600L
        private const val MOST_WAITING = 8
        private const val HALF = 0.5
        private const val SLOT_INTS = 4

        /** Down from a lectern's book, so that the frame holds it open in the middle of the finder's space. */
        private const val BOOK_LOWER_THAN_A_LECTERNS = 0.5 - LecternBookPlane.CENTRE_HEIGHT

        private const val TASK_KEY = "task"
        private const val STARTED_KEY = "started_at"
        private const val PATH_KEY = "path"
        private const val CARRIED_KEY = "carried"
        private const val ERRAND_KEY = "errand"
        private const val HOME_KEY = "home"

        private const val QUEUED = "block.agesandtheart.book_finder.queued"
        private const val GONE = "block.agesandtheart.book_finder.gone"
        private const val UNREACHABLE = "block.agesandtheart.book_finder.unreachable"
        private const val NO_ROOM = "block.agesandtheart.book_finder.no_room"
    }
}

/** What a reader asks of a book held open in a finder's frame, from its screen. */
enum class FinderAsked { TAKE, SEND_BACK }

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

    val ENTITY: BlockEntityType<BookFinderBlockEntity> = BlockEntityType({ pos, state -> BookFinderBlockEntity(pos, state) }, setOf(BLOCK))

    /** A book with pages to turn that are not ours: a written book, or one still being written. */
    fun isReadable(book: ItemStack): Boolean = book.`is`(Items.WRITTEN_BOOK) || book.`is`(Items.WRITABLE_BOOK)

    /** A book as the finder's list names it: its name, and an enchanted book's enchantments after it. */
    fun nameOf(book: ItemStack, level: Level): String {
        val name = book.hoverName.string
        val enchantments = book.get(DataComponents.STORED_ENCHANTMENTS) ?: return name
        val listed = enchantments.entrySet().joinToString(", ") { (enchantment, level) ->
            Enchantment.getFullname(enchantment, level).string
        }
        return if (listed.isEmpty()) name else "$name: $listed"
    }

    /** The book held open in the finder at [pos], where [viewer] may see its panel, or null. */
    fun openBookSeenBy(viewer: Player, pos: BlockPos): ItemStack? {
        val level = viewer.level()
        if (!level.isLoaded(pos)) return null
        if (!viewer.position().closerThan(Vec3.atCenterOf(pos), LecternBooks.PANEL_HELD_WITHIN_BLOCKS)) return null
        val finder = level.getBlockEntity(pos) as? BookFinderBlockEntity ?: return null
        if (finder.task != FrameTask.PRESENTING) return null
        return finder.carried.takeIf(LecternBooks::isOurs)
    }

    fun handleFetch(player: ServerPlayer, payload: BookFinderFetchPayload) {
        finderInReach(player, payload.finder)?.request(player, ShelfSlot(payload.shelf, payload.slot))
    }

    fun handleAsked(player: ServerPlayer, payload: BookFinderAskPayload) {
        finderInReach(player, payload.finder)?.answer(player, payload.asked)
    }

    private fun finderInReach(player: ServerPlayer, pos: BlockPos): BookFinderBlockEntity? {
        val level = player.level()
        if (!level.isLoaded(pos) || !player.position().closerThan(Vec3.atCenterOf(pos), LecternBooks.PANEL_HELD_WITHIN_BLOCKS)) return null
        return level.getBlockEntity(pos) as? BookFinderBlockEntity
    }
}
