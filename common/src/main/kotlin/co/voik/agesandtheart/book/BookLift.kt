package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.util.Prediction
import net.minecraft.util.StringRepresentable
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChainBlock
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
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A book lift's loop of shelves, laid out on its two columns (design §7.8.3): [chainLength] blocks of
 * chain up each column between the base and the cap, and a shelf for every block of both columns from the base's row
 * to the cap's.
 *
 * Positions run round the loop from the base of the first column — the left, from the front — up it to the cap, across
 * to the second, and down it back to the base. A loop turned on carries its shelves up the first column; turned back,
 * up the second. Only a shelf in the chain's rows can be reached; those in the base's row and the cap's are passing
 * round the machinery inside them.
 */
data class BookLiftLoop(val chainLength: Int) {

    /** The rows from the base's to the cap's. */
    val rows: Int get() = chainLength + 2

    val shelves: Int get() = rows * 2

    /** Which column position [position] is in, and which row, the base's being 0. */
    fun cellOf(position: Int): Pair<Int, Int> {
        val round = Math.floorMod(position, shelves)
        return if (round < rows) 0 to round else 1 to (shelves - 1 - round)
    }

    fun positionOf(column: Int, row: Int): Int = if (column == 0) row else shelves - 1 - row

    fun isReachable(row: Int): Boolean = row in 1..chainLength

    companion object {
        /** The most chain a book lift hangs up each column (Jonah, 2026-11-18): forty lengths, and 44 shelves. */
        const val LONGEST_CHAIN = 20

        val MOST_SHELVES: Int = BookLiftLoop(LONGEST_CHAIN).shelves
    }
}

/**
 * A book lift's frame (Jonah, 2026-11-19): one block, facing whoever placed it, which is the way its buttons face and
 * the way the lift's open books are read from. Two frames side by side — beside each other or one behind the other —
 * with chain hung straight up from both to two more frames level with each other make a lift; the four are its corners,
 * and the bottom frame furthest west or north runs it ([BookLiftBlockEntity]).
 *
 * Used, a frame turns the lift: its upper half carries the shelves up the frame's own column, its lower half down; the
 * same again stops it at the next shelf, the other turns it round there. Breaking any frame brings the whole lift down,
 * the other frames, the chain and the books (Jonah).
 */
class BookLiftFrameBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BookLiftBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        createTickerHelper(type, BookLifts.ENTITY) { _, _, _, lift -> lift.tick() }

    /** Anything beside a frame may be the arc crystal, chain, or another frame, so the lift looks again at once. */
    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighbour: Block, orientation: Orientation?, movedByPiston: Boolean) {
        (level.getBlockEntity(pos) as? BookLiftBlockEntity)?.let { (it.controller() ?: it).lookAgain() }
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult = InteractionResult.TRY_WITH_EMPTY_HAND

    /** The buttons, until the art places them: the frame's upper half sends its column's shelves up, its lower half down. */
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val frame = level.getBlockEntity(pos) as? BookLiftBlockEntity ?: return InteractionResult.PASS
        val lift = frame.controller() ?: return InteractionResult.PASS
        val column = lift.columnOf(pos) ?: return InteractionResult.PASS
        val isUp = when (hitResult.direction) {
            Direction.UP -> true
            Direction.DOWN -> false
            else -> hitResult.location.y - pos.y >= UP_BUTTON_FROM
        }
        // Up the first column is on, and up the second is back.
        val upHere = if (column == 0) 1 else -1
        if (!level.isClientSide) lift.control(if (isUp) upHere else -upHere)
        return InteractionResult.SUCCESS
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING

        /** Where on a frame's face its up button begins: its upper half, until the art's buttons say otherwise. */
        const val UP_BUTTON_FROM = 0.5
    }
}

/** What a book lift shelf's block shows: its chain and its platform at rest, its chain alone while the loop turns. */
enum class ShelfShows(private val key: String) : StringRepresentable {
    CHAIN_AND_PLATFORM("chain_and_platform"),
    CHAIN("chain"),

    /** Never placed: the platform's model alone, which `BookLiftShelfRenderer` draws sliding. */
    PLATFORM("platform"),
    ;

    override fun getSerializedName(): String = key
}

/**
 * A length of a standing book lift's chain with a shelf on it. Every length of both columns becomes one when the
 * book lift stands, and a shelf is what a player uses to take or shelve a book, as a chiselled shelf is used. Its
 * block entity remembers the chain it was, which is what it drops; it never drops itself, and is never an item.
 *
 * At rest its model is the chain and the platform. While the loop turns it shows the chain alone, and its renderer
 * draws the platform and its book sliding into it from the block before ([ShelfShows]).
 */
class BookLiftShelfBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHOWS, ShelfShows.CHAIN_AND_PLATFORM))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, SHOWS)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BookLiftShelfBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        if (state.getValue(SHOWS) == ShelfShows.CHAIN) CHAIN else WITH_THE_BOOK

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        if (state.getValue(SHOWS) == ShelfShows.CHAIN) CHAIN else WITH_THE_PLATFORM

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
        return use(stack, level, pos, player, hitResult)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult =
        use(ItemStack.EMPTY, level, pos, player, hitResult)

    /**
     * Shelving [held] where the shelf is empty; a book on it is a lectern's ([BookStands]): opened, read and linked from
     * where it lies, taken with its screen's Take Book, and kept open as the lift runs.
     */
    private fun use(held: ItemStack, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        val shelf = level.getBlockEntity(pos) as? BookLiftShelfBlockEntity ?: return InteractionResult.PASS
        val lift = shelf.base() ?: return InteractionResult.PASS
        val index = lift.shelfAt(shelf.column, shelf.row) ?: return InteractionResult.PASS
        val book = lift.bookOn(index)
        if (book.isEmpty) {
            if (held.isEmpty) return InteractionResult.PASS
            if (!level.isClientSide) lift.shelve(index, held.consumeAndReturn(1, player))
            return InteractionResult.SUCCESS
        }
        return BookStands.use(
            level, lift, index, book, player, hit,
            open = { lift.open(index) },
            close = { lift.close(index) },
            take = { player.inventory.placeItemBackInInventory(lift.take(index), Prediction.SERVER_ONLY) },
        )
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val SHOWS: EnumProperty<ShelfShows> = EnumProperty.create("shows", ShelfShows::class.java)

        private val CHAIN: VoxelShape = box(6.5, 0.0, 6.5, 9.5, 16.0, 9.5)
        private val WITH_THE_PLATFORM: VoxelShape = Shapes.or(CHAIN, box(1.0, 0.0, 1.0, 15.0, 2.0, 15.0))

        /** What the outline and a click find: the platform, and a book on it lying shut or standing open as on a lectern. */
        private val WITH_THE_BOOK: VoxelShape = Shapes.or(CHAIN, box(1.0, 0.0, 1.0, 15.0, 12.0, 15.0))
    }
}

/**
 * Where a shelf stands in its book lift — its column and how far above the base — and the chain it was made from,
 * set when the book lift stands and sent to clients with the block, so a client finds the base without searching.
 */
class BookLiftShelfBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(BookLifts.SHELF_ENTITY, pos, state) {

    var chain: BlockState = Blocks.IRON_CHAIN.defaultBlockState()
        private set
    var column = 0
        private set
    var row = 1
        private set

    /** The frame running the lift this shelf hangs in. */
    private var controllerPos: BlockPos = BlockPos.ZERO

    fun madeFrom(chain: BlockState, column: Int, row: Int, controller: BlockPos) {
        this.chain = chain
        this.column = column
        this.row = row
        this.controllerPos = controller
        setChanged()
    }

    /** The book lift this shelf hangs in, while the frame running it stands. */
    fun base(): BookLiftBlockEntity? =
        (level?.getBlockEntity(controllerPos) as? BookLiftBlockEntity)?.takeIf { it.isStanding }

    /** However it goes, it drops the chain it was, and the base looks again: a length gone takes the rest. */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        val level = level ?: return
        if (level.isClientSide) return
        Block.popResource(level, pos, ItemStack(chain.block))
        base()?.lookAgain()
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        chain = input.read(CHAIN_KEY, BlockState.CODEC).orElse(Blocks.IRON_CHAIN.defaultBlockState())
        column = input.getIntOr(COLUMN_KEY, 0).coerceIn(0, 1)
        row = input.getIntOr(ROW_KEY, 1).coerceIn(1, BookLiftLoop.LONGEST_CHAIN)
        controllerPos = input.read(CONTROLLER_KEY, BlockPos.CODEC).orElse(blockPos.below(row))
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.store(CHAIN_KEY, BlockState.CODEC, chain)
        output.putInt(COLUMN_KEY, column)
        output.putInt(ROW_KEY, row)
        output.store(CONTROLLER_KEY, BlockPos.CODEC, controllerPos)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    private companion object {
        const val CHAIN_KEY = "chain"
        const val COLUMN_KEY = "column"
        const val ROW_KEY = "row"
        const val CONTROLLER_KEY = "controller"
    }
}

/**
 * A book lift frame's block entity. Every frame has one; the frame that runs a lift ([isStanding]) holds its books and
 * how it is turning, and the lift's other three frames only say which frame that is ([controller]).
 *
 * **It stands** while two frames side by side, two more level with each other above them at most
 * [BookLiftLoop.LONGEST_CHAIN] blocks up, and chain — iron or copper, hung straight — up both columns between are all
 * there; each length then becomes a [BookLiftShelfBlock]. Of the two bottom frames, the one to the west or north runs
 * it, which is as good as any (Jonah, 2026-11-19), and its facing is the way the lift's books are read. A block of arc
 * crystal beside either bottom frame runs it, at one speed whatever its height. The server looks at it twice a second,
 * and at once when something beside a frame or a shelf changes.
 *
 * **It comes down** as Jonah ruled: a shelf broken breaks the whole chain, and the books drop where their shelves were,
 * but the frames stand; any frame broken takes everything.
 *
 * **It turns a shelf at a time**, and comes to rest with every shelf in a block space; books are reached only while it
 * is still. The server says when each shelf is reached, and every client draws the way between.
 *
 * **Its shelves are high-capacity lecterns** ([BookStand]) (Jonah, 2026-11-19): a book opened on one stays open as the
 * lift runs, carried round open.
 */
class BookLiftBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(BookLifts.ENTITY, pos, state), BookStand {

    private val books: NonNullList<ItemStack> = NonNullList.withSize(BookLiftLoop.MOST_SHELVES, ItemStack.EMPTY)

    /** The loop while it stands, and null while it does not. */
    var loop: BookLiftLoop? = null
        private set

    val isStanding: Boolean get() = loop != null

    /** How far round the shelves have gone: shelf `s` is at loop position `s + shift`. */
    var shift = 0
        private set

    /** Which way it is turning, on (1), back (-1) or not at all. */
    var direction = 0
        private set

    /** Which way it was last asked to turn, which it does from each shelf it reaches until asked to stop. */
    private var asked = 0

    /** Ticks into the step between one shelf and the next. On a client, counted by the client. */
    var ticksIntoStep = 0
        private set

    private var isPowered = false
    private var shouldLookAgain = true

    /** On the frame running a lift: from it toward the other bottom frame, the second column. */
    private var towardThePartner: Direction = Direction.EAST

    /** On a lift's other frames: the frame running it. */
    private var runBy: BlockPos? = null

    /** The frame running the lift this frame is part of, itself included, or null where it is part of none. */
    fun controller(): BookLiftBlockEntity? {
        if (isStanding) return this
        val by = runBy ?: return null
        return (level?.getBlockEntity(by) as? BookLiftBlockEntity)?.takeIf { it.isStanding }
    }

    /** Which column a frame or space at [pos] is in, or null where it is in neither. */
    fun columnOf(pos: BlockPos): Int? = (0..1).firstOrNull { columnBase(it).x == pos.x && columnBase(it).z == pos.z }

    /** The shelves whose books lie open, which they stay as the lift runs. */
    private val opened = HashSet<Int>()

    fun bookOn(shelf: Int): ItemStack = books[shelf]

    fun isOpen(shelf: Int): Boolean = shelf in opened

    override fun openBookAt(place: Int): ItemStack? = books.getOrNull(place)?.takeIf { place in opened && !it.isEmpty }

    override fun openPlaces(): Collection<Int> = opened.toList()

    /** The open books on shelves a player can reach, not those passing round inside the frames; the nearest is chosen. */
    override fun panelCandidates(): Collection<Int> {
        val loop = loop ?: return emptyList()
        return opened.filter { loop.isReachable(loop.cellOf(it + shift).second) }
    }

    /** Standing open on its shelf, where the shelf rests, read from the lift's front. */
    override fun openBookPose(place: Int): OpenBookPose {
        val (column, row) = (loop ?: BookLiftLoop(1)).cellOf(place + shift)
        return OpenBookPose(facing, Vec3.atBottomCenterOf(columnBase(column).above(row)).add(0.0, OPEN_BOOK_UP, 0.0))
    }

    override fun answer(player: ServerPlayer, place: Int, asked: StandAsked) {
        when (asked) {
            StandAsked.TAKE -> if (!books[place].isEmpty) player.inventory.placeItemBackInInventory(take(place), Prediction.SERVER_ONLY)
            StandAsked.CLOSE -> close(place)
        }
    }

    fun open(shelf: Int) {
        if (opened.add(shelf)) changed()
    }

    fun close(shelf: Int) {
        if (!opened.remove(shelf)) return
        play(SoundEvents.BOOK_PAGE_TURN)
        changed()
    }

    /** The shelf at [column] and [row] while the loop is still, if any. */
    fun shelfAt(column: Int, row: Int): Int? {
        val loop = loop ?: return null
        if (direction != 0 || !loop.isReachable(row)) return null
        return Math.floorMod(loop.positionOf(column, row) - shift, loop.shelves)
    }

    val facing: Direction get() = blockState.getValue(BookLiftFrameBlock.FACING)

    /** The bottom of [column]: the bottom frame in it. */
    fun columnBase(column: Int): BlockPos = if (column == 0) blockPos else blockPos.relative(towardThePartner)

    fun lookAgain() {
        shouldLookAgain = true
    }

    /** Asked to turn [way] from a control: started, turned round at the next shelf, or stopped there if already going that way. */
    fun control(way: Int) {
        asked = if (asked == way) 0 else way
        play(SoundEvents.STONE_BUTTON_CLICK_ON, BUTTON_VOLUME)
        setChanged()
    }

    /** [book] onto [shelf], which is empty. */
    fun shelve(shelf: Int, book: ItemStack) {
        if (!books[shelf].isEmpty) return
        books[shelf] = book
        play(if (book.`is`(Items.ENCHANTED_BOOK)) SoundEvents.CHISELED_BOOKSHELF_INSERT_ENCHANTED else SoundEvents.CHISELED_BOOKSHELF_INSERT)
        changed()
    }

    /** The book on [shelf], taken off it, and shut. */
    fun take(shelf: Int): ItemStack {
        val taken = books[shelf]
        books[shelf] = ItemStack.EMPTY
        opened.remove(shelf)
        play(if (taken.`is`(Items.ENCHANTED_BOOK)) SoundEvents.CHISELED_BOOKSHELF_PICKUP_ENCHANTED else SoundEvents.CHISELED_BOOKSHELF_PICKUP)
        changed()
        return taken
    }

    fun tick() {
        val level = level ?: return
        if (level.isClientSide) {
            if (direction != 0 && ticksIntoStep < STEP_TICKS) ticksIntoStep++
            return
        }
        if (shouldLookAgain || level.gameTime % LOOK_EVERY == 0L) {
            shouldLookAgain = false
            look(level)
        }
        // Its open books shut where nobody is near to read them, as a lectern's are.
        if (opened.isNotEmpty() && BookStands.isTimeToLookRound(level) && !BookStands.anybodyNear(level as ServerLevel, blockPos)) {
            opened.clear()
            changed()
        }
        val loop = loop ?: return
        if (direction == 0) {
            if (asked == 0) return
            if (!isPowered) {
                asked = 0
                return
            }
            showOnTheShelves(level, loop, ShelfShows.CHAIN)
            startStep(asked)
            return
        }
        ticksIntoStep++
        if (ticksIntoStep < STEP_TICKS) return
        shift = Math.floorMod(shift + direction, loop.shelves)
        if (asked != 0 && isPowered) {
            startStep(asked)
        } else {
            startStep(0)
            showOnTheShelves(level, loop, ShelfShows.CHAIN_AND_PLATFORM)
        }
    }

    private fun startStep(way: Int) {
        direction = way
        ticksIntoStep = 0
        if (way != 0) play(SoundEvents.CHAIN_STEP, STEP_VOLUME)
        setChanged()
        level?.blockEvent(blockPos, blockState.block, MOVED, shift * MOVED_SHIFT_STRIDE + (direction + 1))
    }

    /** A client told where the loop is and which way it goes; the step's ticks start over. */
    override fun triggerEvent(id: Int, param: Int): Boolean {
        if (id != MOVED) return super.triggerEvent(id, param)
        shift = param / MOVED_SHIFT_STRIDE
        direction = param % MOVED_SHIFT_STRIDE - 1
        ticksIntoStep = 0
        return true
    }

    /** Every shelf of [loop] made to show [shows]: the platform at rest, the chain alone while turning. */
    private fun showOnTheShelves(level: Level, loop: BookLiftLoop, shows: ShelfShows) {
        forEachShelf(loop) { pos ->
            val state = level.getBlockState(pos)
            if (state.`is`(BookLifts.SHELF)) level.setBlock(pos, state.setValue(BookLiftShelfBlock.SHOWS, shows), Block.UPDATE_CLIENTS)
        }
    }

    private fun forEachShelf(loop: BookLiftLoop, action: (column: Int, row: Int, pos: BlockPos) -> Unit) {
        for (column in 0..1) for (row in 1..loop.chainLength) action(column, row, columnBase(column).above(row))
    }

    private fun forEachShelf(loop: BookLiftLoop, action: (BlockPos) -> Unit) = forEachShelf(loop) { _, _, pos -> action(pos) }

    /** Whether it stands, and is powered; and what comes down where something is gone. */
    private fun look(level: Level) {
        val standing = loop
        if (standing == null) {
            // A frame of a standing lift is that lift's, and stands up no other.
            if (runBy != null && controller() == null) runBy = null
            if (runBy == null) standUp(level)
            return
        }
        val everyFrameStands = level.getBlockState(columnBase(1)).`is`(BookLifts.FRAME) && topFramesAt(level, standing.chainLength + 1)
        if (!everyFrameStands) {
            comeDown(level, alsoTheFrames = true)
            level.destroyBlock(blockPos, true)
            return
        }
        var everyShelfHangs = true
        forEachShelf(standing) { pos -> if (!level.getBlockState(pos).`is`(BookLifts.SHELF)) everyShelfHangs = false }
        if (!everyShelfHangs) {
            comeDown(level, alsoTheFrames = false)
            return
        }
        isPowered = hasArcCrystalBeside(level)
    }

    /** Whether a frame stands [up] blocks above each column's bottom. */
    private fun topFramesAt(level: Level, up: Int): Boolean = (0..1).all { level.getBlockState(columnBase(it).above(up)).`is`(BookLifts.FRAME) }

    /** A frame of no lift at [pos], free to be claimed by this one. */
    private fun isAFreeFrame(level: Level, pos: BlockPos): Boolean {
        val frame = level.getBlockEntity(pos) as? BookLiftBlockEntity ?: return false
        return frame.controller() == null
    }

    /**
     * Stands it up where this frame has a free frame to its east or south, chain hangs straight up both columns, and two
     * free frames close them at the top: each length made a shelf, every shelf empty, the first at the bottom of this
     * frame's column. A frame to the west or north stands it from its own side, so each pair is tried once.
     */
    private fun standUp(level: Level) {
        for (toward in listOf(Direction.EAST, Direction.SOUTH)) {
            towardThePartner = toward
            if (!isAFreeFrame(level, columnBase(1))) continue
            var up = 1
            while (up <= BookLiftLoop.LONGEST_CHAIN && (0..1).all { isChain(level.getBlockState(columnBase(it).above(up))) }) up++
            val chainLength = up - 1
            if (chainLength < 1 || !(0..1).all { isAFreeFrame(level, columnBase(it).above(up)) }) continue
            standUp(level, BookLiftLoop(chainLength))
            return
        }
    }

    private fun standUp(level: Level, standing: BookLiftLoop) {
        // The loop first, so that no shelf's arrival makes it think a length is missing.
        loop = standing
        runBy = null
        forEachShelf(standing) { column, row, pos ->
            val chain = level.getBlockState(pos)
            level.setBlock(pos, BookLifts.SHELF.defaultBlockState().setValue(BookLiftShelfBlock.FACING, facing), Block.UPDATE_ALL)
            (level.getBlockEntity(pos) as? BookLiftShelfBlockEntity)?.madeFrom(chain, column, row, blockPos)
        }
        for (frame in otherFrames(standing)) (level.getBlockEntity(frame) as? BookLiftBlockEntity)?.claimedBy(blockPos)
        shift = 0
        direction = 0
        asked = 0
        books.clear()
        isPowered = hasArcCrystalBeside(level)
        play(SoundEvents.CHAIN_PLACE)
        changed()
    }

    /** The lift's frames but this one: the other at the bottom, and the two at the top. */
    private fun otherFrames(standing: BookLiftLoop): List<BlockPos> =
        listOf(columnBase(1), columnBase(0).above(standing.chainLength + 1), columnBase(1).above(standing.chainLength + 1))

    /** Made one of the lift run by the frame at [controller], or none with null. */
    fun claimedBy(controller: BlockPos?) {
        runBy = controller
        changed()
    }

    private fun isChain(state: BlockState): Boolean = state.`is`(BlockTags.CHAINS) && state.getValue(ChainBlock.AXIS) == Direction.Axis.Y

    /** A block of arc crystal against either bottom frame, on any side but its top. */
    private fun hasArcCrystalBeside(level: Level): Boolean = (0..1).any { column ->
        val base = columnBase(column)
        Direction.entries.filter { it != Direction.UP }.any { level.getBlockState(base.relative(it)).`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK) }
    }

    /** Its books dropped where their shelves are and the whole chain broken, and with [alsoTheFrames], its other frames. */
    private fun comeDown(level: Level, alsoTheFrames: Boolean) {
        val standing = loop ?: return
        loop = null
        direction = 0
        asked = 0
        opened.clear()
        dropBooks(level, standing)
        // Each shelf drops the chain it was as it goes.
        forEachShelf(standing) { pos -> if (level.getBlockState(pos).`is`(BookLifts.SHELF)) level.destroyBlock(pos, false) }
        changed()
        for (frame in otherFrames(standing)) {
            (level.getBlockEntity(frame) as? BookLiftBlockEntity)?.claimedBy(null)
            if (alsoTheFrames && level.getBlockState(frame).`is`(BookLifts.FRAME)) level.destroyBlock(frame, true)
        }
    }

    private fun dropBooks(level: Level, standing: BookLiftLoop) {
        for (shelf in 0 until standing.shelves) {
            val book = books[shelf]
            if (book.isEmpty) continue
            books[shelf] = ItemStack.EMPTY
            val (column, row) = standing.cellOf(shelf + shift)
            val at = columnBase(column).above(row)
            Containers.dropItemStack(level, at.x + HALF, at.y + HALF, at.z + HALF, book)
        }
    }

    /** The base broken: the books drop, and the chain and the cap come down with it. */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        val level = level ?: return
        if (level.isClientSide) return
        // A frame of a lift run from elsewhere tells it, and it comes down when it looks.
        if (!isStanding) {
            controller()?.lookAgain()
            return
        }
        comeDown(level, alsoTheFrames = true)
        Containers.dropContents(level, pos, books)
    }

    private fun play(sound: SoundEvent, volume: Float = 1.0f) {
        val level = level as? ServerLevel ?: return
        level.playSound(null, blockPos, sound, SoundSource.BLOCKS, volume, 1.0f)
    }

    private fun changed() {
        setChanged()
        val level = level ?: return
        if (!level.isClientSide) level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        books.clear()
        ContainerHelper.loadAllItems(input, books)
        val chainLength = input.getIntOr(CHAIN_KEY, 0).coerceIn(0, BookLiftLoop.LONGEST_CHAIN)
        towardThePartner = input.read(PARTNER_KEY, Direction.CODEC).orElse(Direction.EAST)
        runBy = input.read(RUN_BY_KEY, BlockPos.CODEC).orElse(null)
        loop = if (chainLength > 0) BookLiftLoop(chainLength) else null
        shift = input.getIntOr(SHIFT_KEY, 0)
        opened.clear()
        input.read(OPEN_KEY, Codec.INT.listOf()).ifPresent { shelves -> opened += shelves.filter { it in 0 until BookLiftLoop.MOST_SHELVES } }
        direction = input.getIntOr(DIRECTION_KEY, 0).coerceIn(-1, 1)
        asked = input.getIntOr(ASKED_KEY, 0).coerceIn(-1, 1)
        ticksIntoStep = input.getIntOr(TICKS_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, books, true)
        output.putInt(CHAIN_KEY, loop?.chainLength ?: 0)
        output.store(PARTNER_KEY, Direction.CODEC, towardThePartner)
        runBy?.let { output.store(RUN_BY_KEY, BlockPos.CODEC, it) }
        output.putInt(SHIFT_KEY, shift)
        output.store(OPEN_KEY, Codec.INT.listOf(), opened.sorted())
        output.putInt(DIRECTION_KEY, direction)
        output.putInt(ASKED_KEY, asked)
        output.putInt(TICKS_KEY, ticksIntoStep)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    companion object {
        /** Half a second from one shelf to the next: one standard speed, whatever the height (Jonah). */
        const val STEP_TICKS = 10

        private const val LOOK_EVERY = 10L
        private const val HALF = 0.5
        private const val BUTTON_VOLUME = 0.6f
        private const val STEP_VOLUME = 0.5f

        /** The block event a step is sent as: the shift and the direction packed into its parameter. */
        private const val MOVED = 1
        private const val MOVED_SHIFT_STRIDE = 4

        private const val CHAIN_KEY = "chain"
        private const val SHIFT_KEY = "shift"
        private const val OPEN_KEY = "open"
        private const val PARTNER_KEY = "toward_the_partner"
        private const val RUN_BY_KEY = "run_by"

        /** An open book's middle, above the bottom of its shelf's space. */
        const val OPEN_BOOK_UP = 6.0 / 16.0
        private const val DIRECTION_KEY = "direction"
        private const val ASKED_KEY = "asked"
        private const val TICKS_KEY = "ticks_into_step"
    }
}

/** The book lift's registrations, for [co.voik.agesandtheart.content.AgeContent]'s lists. */
object BookLifts {
    val FRAME_ID: Identifier = "book_lift_frame".location()
    val SHELF_ID: Identifier = "book_lift_shelf".location()

    private const val STRENGTH = 3.5f
    private const val STRENGTH_OF_A_CHAIN = 5.0f
    private const val RESISTANCE_OF_A_CHAIN = 6.0f

    private fun properties(id: Identifier): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .setId(ResourceKey.create(Registries.BLOCK, id))
        .mapColor(MapColor.METAL)
        .strength(STRENGTH)
        .sound(SoundType.COPPER)
        .requiresCorrectToolForDrops()
        .noOcclusion()

    val FRAME: BookLiftFrameBlock = BookLiftFrameBlock(properties(FRAME_ID))

    /** As hard to break as the chain it was; it drops that chain from its block entity, so it has no loot table. */
    val SHELF: BookLiftShelfBlock = BookLiftShelfBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, SHELF_ID))
            .mapColor(MapColor.METAL)
            .strength(STRENGTH_OF_A_CHAIN, RESISTANCE_OF_A_CHAIN)
            .sound(SoundType.CHAIN)
            .requiresCorrectToolForDrops()
            .noOcclusion()
            .noLootTable(),
    )

    val FRAME_ITEM: Item = BlockItem(FRAME, Item.Properties().setId(ResourceKey.create(Registries.ITEM, FRAME_ID)).useBlockDescriptionPrefix())

    val ENTITY: BlockEntityType<BookLiftBlockEntity> =
        BlockEntityType({ pos, state -> BookLiftBlockEntity(pos, state) }, setOf(FRAME))

    val SHELF_ENTITY: BlockEntityType<BookLiftShelfBlockEntity> =
        BlockEntityType({ pos, state -> BookLiftShelfBlockEntity(pos, state) }, setOf(SHELF))
}
