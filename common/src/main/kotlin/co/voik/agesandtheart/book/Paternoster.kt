package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
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
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
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
 * A paternoster's loop of shelves, laid out on its two columns (design `book-storage.md` §2): [chainLength] blocks of
 * chain up each column between the base and the cap, and a shelf for every block of both columns from the base's row
 * to the cap's.
 *
 * Positions run round the loop from the base of the first column — the left, from the front — up it to the cap, across
 * to the second, and down it back to the base. A loop turned on carries its shelves up the first column; turned back,
 * up the second. Only a shelf in the chain's rows can be reached; those in the base's row and the cap's are passing
 * round the machinery inside them.
 */
data class PaternosterLoop(val chainLength: Int) {

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
        /** The most chain a paternoster hangs up each column (Jonah, 2026-11-18): forty lengths, and 44 shelves. */
        const val LONGEST_CHAIN = 20

        val MOST_SHELVES: Int = PaternosterLoop(LONGEST_CHAIN).shelves
    }
}

/**
 * A paternoster's base or its cap, each two blocks side by side over the loop's two columns, the left one first. Used,
 * either turns the loop: on its left block, carrying the shelves up the left column, and on its right block up the right;
 * used again the same way, it stops at the next shelf. The base holds the books, and the cap is the base's to find.
 *
 * Breaking either brings the whole thing down — the other end, the chain and the books (Jonah).
 */
class PaternosterEndBlock(val isBase: Boolean, properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, PairPart.LEFT))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, PART)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.horizontalDirection.opposite
        val second = context.clickedPos.relative(towardTheSecond(facing))
        val hasRoom = context.level.isInsideBuildHeight(second) && context.level.getBlockState(second).canBeReplaced(context)
        return if (hasRoom) defaultBlockState().setValue(FACING, facing) else null
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        level.setBlock(pos.relative(towardTheSecond(state.getValue(FACING))), state.setValue(PART, PairPart.RIGHT), UPDATE_ALL)
    }

    /** Either block lost takes the other with it, as a large book wheel's does. */
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
        val toward = towardTheSecond(state.getValue(FACING))
        val towardTheOther = if (state.getValue(PART).isFirst) toward else toward.opposite
        if (directionToNeighbour != towardTheOther) {
            return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
        }
        val otherStands = neighbourState.`is`(this) &&
            neighbourState.getValue(FACING) == state.getValue(FACING) &&
            neighbourState.getValue(PART) != state.getValue(PART)
        return if (otherStands) state else Blocks.AIR.defaultBlockState()
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide && player.preventsBlockDrops() && !state.getValue(PART).isFirst) {
            val first = firstPos(state, pos)
            val firstState = level.getBlockState(first)
            if (firstState.`is`(this) && firstState.getValue(PART).isFirst) {
                level.setBlock(first, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (isBase && state.getValue(PART).isFirst) PaternosterBlockEntity(pos, state) else null

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        createTickerHelper(type, Paternosters.ENTITY) { _, _, _, paternoster -> paternoster.tick() }

    /** Anything beside the base may be the arc crystal, or the chain above it, so the base looks again at once. */
    override fun neighborChanged(state: BlockState, level: Level, pos: BlockPos, neighbour: Block, orientation: Orientation?, movedByPiston: Boolean) {
        if (isBase) (level.getBlockEntity(firstPos(state, pos)) as? PaternosterBlockEntity)?.lookAgain()
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

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val paternoster = baseOf(level, state, pos) ?: return InteractionResult.PASS
        if (!paternoster.isStanding) return InteractionResult.PASS
        if (!level.isClientSide) paternoster.control(if (state.getValue(PART).isFirst) 1 else -1)
        return InteractionResult.SUCCESS
    }

    /** The base's block entity, from either block of the base or of a cap over it. */
    private fun baseOf(level: Level, state: BlockState, pos: BlockPos): PaternosterBlockEntity? {
        val first = firstPos(state, pos)
        if (isBase) return level.getBlockEntity(first) as? PaternosterBlockEntity
        for (down in 1..PaternosterLoop.LONGEST_CHAIN + 1) {
            val paternoster = level.getBlockEntity(first.below(down)) as? PaternosterBlockEntity ?: continue
            return paternoster.takeIf { it.loop?.chainLength == down - 1 }
        }
        return null
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val PART: EnumProperty<PairPart> = EnumProperty.create("part", PairPart::class.java, PairPart.LEFT, PairPart.RIGHT)

        /** From the left block to the right, for an end whose front faces [facing]. */
        fun towardTheSecond(facing: Direction): Direction = facing.counterClockWise

        fun firstPos(state: BlockState, pos: BlockPos): BlockPos =
            if (state.getValue(PART).isFirst) pos else pos.relative(towardTheSecond(state.getValue(FACING)).opposite)
    }
}

/** What a paternoster shelf's block shows: its chain and its platform at rest, its chain alone while the loop turns. */
enum class ShelfShows(private val key: String) : StringRepresentable {
    CHAIN_AND_PLATFORM("chain_and_platform"),
    CHAIN("chain"),

    /** Never placed: the platform's model alone, which `PaternosterShelfRenderer` draws sliding. */
    PLATFORM("platform"),
    ;

    override fun getSerializedName(): String = key
}

/**
 * A length of a standing paternoster's chain with a shelf on it. Every length of both columns becomes one when the
 * paternoster stands, and a shelf is what a player uses to take or shelve a book, as a chiselled shelf is used. Its
 * block entity remembers the chain it was, which is what it drops; it never drops itself, and is never an item.
 *
 * At rest its model is the chain and the platform. While the loop turns it shows the chain alone, and its renderer
 * draws the platform and its book sliding into it from the block before ([ShelfShows]).
 */
class PaternosterShelfBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHOWS, ShelfShows.CHAIN_AND_PLATFORM))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, SHOWS)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = PaternosterShelfBlockEntity(pos, state)

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
        return use(stack, level, pos, player)
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult =
        use(ItemStack.EMPTY, level, pos, player)

    /** As a chiselled shelf: shelving [held] where the shelf is empty, and taking the book where it is not. */
    private fun use(held: ItemStack, level: Level, pos: BlockPos, player: Player): InteractionResult {
        val shelf = level.getBlockEntity(pos) as? PaternosterShelfBlockEntity ?: return InteractionResult.PASS
        val paternoster = shelf.base() ?: return InteractionResult.PASS
        val index = paternoster.shelfAt(shelf.column, shelf.row) ?: return InteractionResult.PASS
        if (paternoster.bookOn(index).isEmpty) {
            if (held.isEmpty) return InteractionResult.PASS
            if (!level.isClientSide) paternoster.shelve(index, held.consumeAndReturn(1, player))
            return InteractionResult.SUCCESS
        }
        if (!level.isClientSide) player.inventory.placeItemBackInInventory(paternoster.take(index), Prediction.SERVER_ONLY)
        return InteractionResult.SUCCESS
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val SHOWS: EnumProperty<ShelfShows> = EnumProperty.create("shows", ShelfShows::class.java)

        private val CHAIN: VoxelShape = box(6.5, 0.0, 6.5, 9.5, 16.0, 9.5)
        private val WITH_THE_PLATFORM: VoxelShape = Shapes.or(CHAIN, box(1.0, 0.0, 1.0, 15.0, 2.0, 15.0))

        /** What the outline and a click find: the platform and a book lying on it. */
        private val WITH_THE_BOOK: VoxelShape = Shapes.or(CHAIN, box(1.0, 0.0, 1.0, 15.0, 4.0, 15.0))
    }
}

/**
 * Where a shelf stands in its paternoster — its column and how far above the base — and the chain it was made from,
 * set when the paternoster stands and sent to clients with the block, so a client finds the base without searching.
 */
class PaternosterShelfBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(Paternosters.SHELF_ENTITY, pos, state) {

    var chain: BlockState = Blocks.IRON_CHAIN.defaultBlockState()
        private set
    var column = 0
        private set
    var row = 1
        private set

    fun madeFrom(chain: BlockState, column: Int, row: Int) {
        this.chain = chain
        this.column = column
        this.row = row
        setChanged()
    }

    /** The paternoster this shelf hangs in, while its base is there. */
    fun base(): PaternosterBlockEntity? {
        val level = level ?: return null
        val below = blockPos.below(row)
        val state = level.getBlockState(below)
        if (!state.`is`(Paternosters.BASE)) return null
        return level.getBlockEntity(PaternosterEndBlock.firstPos(state, below)) as? PaternosterBlockEntity
    }

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
        row = input.getIntOr(ROW_KEY, 1).coerceIn(1, PaternosterLoop.LONGEST_CHAIN)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.store(CHAIN_KEY, BlockState.CODEC, chain)
        output.putInt(COLUMN_KEY, column)
        output.putInt(ROW_KEY, row)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    private companion object {
        const val CHAIN_KEY = "chain"
        const val COLUMN_KEY = "column"
        const val ROW_KEY = "row"
    }
}

/**
 * A paternoster's base: whether it stands, its books by shelf, and how its loop is turning.
 *
 * **It stands** while the base, a matching cap above it in the same columns at most [PaternosterLoop.LONGEST_CHAIN]
 * blocks up, and chain — iron or copper, hung straight — up both columns from one to the other are all there; each
 * length then becomes a [PaternosterShelfBlock]. A block of arc crystal beside the base runs it, at one speed whatever
 * its height. The server looks at it twice a second, and at once when something beside the base or a shelf changes.
 *
 * **It comes down** as Jonah ruled: a shelf broken breaks the whole chain, and the books drop where their shelves were,
 * but the base and cap stand; the base or the cap broken takes everything.
 *
 * **It turns a shelf at a time**, and comes to rest with every shelf in a block space; books are reached only while it
 * is still. The server says when each shelf is reached, and every client draws the way between.
 */
class PaternosterBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(Paternosters.ENTITY, pos, state) {

    private val books: NonNullList<ItemStack> = NonNullList.withSize(PaternosterLoop.MOST_SHELVES, ItemStack.EMPTY)

    /** The loop while it stands, and null while it does not. */
    var loop: PaternosterLoop? = null
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

    fun bookOn(shelf: Int): ItemStack = books[shelf]

    /** The shelf at [column] and [row] while the loop is still, if any. */
    fun shelfAt(column: Int, row: Int): Int? {
        val loop = loop ?: return null
        if (direction != 0 || !loop.isReachable(row)) return null
        return Math.floorMod(loop.positionOf(column, row) - shift, loop.shelves)
    }

    val facing: Direction get() = blockState.getValue(PaternosterEndBlock.FACING)

    /** The bottom of [column]: the base's block in it. */
    fun columnBase(column: Int): BlockPos =
        if (column == 0) blockPos else blockPos.relative(PaternosterEndBlock.towardTheSecond(facing))

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

    /** The book on [shelf], taken off it. */
    fun take(shelf: Int): ItemStack {
        val taken = books[shelf]
        books[shelf] = ItemStack.EMPTY
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
    private fun showOnTheShelves(level: Level, loop: PaternosterLoop, shows: ShelfShows) {
        forEachShelf(loop) { pos ->
            val state = level.getBlockState(pos)
            if (state.`is`(Paternosters.SHELF)) level.setBlock(pos, state.setValue(PaternosterShelfBlock.SHOWS, shows), Block.UPDATE_CLIENTS)
        }
    }

    private fun forEachShelf(loop: PaternosterLoop, action: (column: Int, row: Int, pos: BlockPos) -> Unit) {
        for (column in 0..1) for (row in 1..loop.chainLength) action(column, row, columnBase(column).above(row))
    }

    private fun forEachShelf(loop: PaternosterLoop, action: (BlockPos) -> Unit) = forEachShelf(loop) { _, _, pos -> action(pos) }

    /** Whether it stands, and is powered; and what comes down where something is gone. */
    private fun look(level: Level) {
        val standing = loop
        if (standing == null) {
            standUp(level)
            return
        }
        if (!capStandsAt(level, standing.chainLength + 1)) {
            comeDown(level, alsoTheCap = true)
            level.destroyBlock(blockPos, true)
            return
        }
        var everyShelfHangs = true
        forEachShelf(standing) { pos -> if (!level.getBlockState(pos).`is`(Paternosters.SHELF)) everyShelfHangs = false }
        if (!everyShelfHangs) {
            comeDown(level, alsoTheCap = false)
            return
        }
        isPowered = hasArcCrystalBeside(level)
    }

    private fun capStandsAt(level: Level, up: Int): Boolean = (0..1).all { column ->
        val cap = level.getBlockState(columnBase(column).above(up))
        cap.`is`(Paternosters.CAP) && cap.getValue(PaternosterEndBlock.FACING) == facing &&
            cap.getValue(PaternosterEndBlock.PART).isFirst == (column == 0)
    }

    /**
     * Stands it up where chain hangs straight up both columns to a cap: each length made a shelf, every shelf empty, the
     * first at the bottom left.
     */
    private fun standUp(level: Level) {
        var up = 1
        while (up <= PaternosterLoop.LONGEST_CHAIN && (0..1).all { isChain(level.getBlockState(columnBase(it).above(up))) }) up++
        val chainLength = up - 1
        if (chainLength < 1 || !capStandsAt(level, up)) return
        val standing = PaternosterLoop(chainLength)
        // The loop first, so that no shelf's arrival makes the base think a length is missing.
        loop = standing
        forEachShelf(standing) { column, row, pos ->
            val chain = level.getBlockState(pos)
            level.setBlock(pos, Paternosters.SHELF.defaultBlockState().setValue(PaternosterShelfBlock.FACING, facing), Block.UPDATE_ALL)
            (level.getBlockEntity(pos) as? PaternosterShelfBlockEntity)?.madeFrom(chain, column, row)
        }
        shift = 0
        direction = 0
        asked = 0
        books.clear()
        isPowered = hasArcCrystalBeside(level)
        play(SoundEvents.CHAIN_PLACE)
        changed()
    }

    private fun isChain(state: BlockState): Boolean = state.`is`(BlockTags.CHAINS) && state.getValue(ChainBlock.AXIS) == Direction.Axis.Y

    /** A block of arc crystal against either block of the base, on any side but its top. */
    private fun hasArcCrystalBeside(level: Level): Boolean = (0..1).any { column ->
        val base = columnBase(column)
        Direction.entries.filter { it != Direction.UP }.any { level.getBlockState(base.relative(it)).`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK) }
    }

    /** Its books dropped where their shelves are and the whole chain broken, and with [alsoTheCap], the cap too. */
    private fun comeDown(level: Level, alsoTheCap: Boolean) {
        val standing = loop ?: return
        loop = null
        direction = 0
        asked = 0
        dropBooks(level, standing)
        // Each shelf drops the chain it was as it goes.
        forEachShelf(standing) { pos -> if (level.getBlockState(pos).`is`(Paternosters.SHELF)) level.destroyBlock(pos, false) }
        changed()
        if (!alsoTheCap) return
        for (column in 0..1) {
            val cap = columnBase(column).above(standing.chainLength + 1)
            if (level.getBlockState(cap).`is`(Paternosters.CAP)) level.destroyBlock(cap, true)
        }
    }

    private fun dropBooks(level: Level, standing: PaternosterLoop) {
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
        comeDown(level, alsoTheCap = true)
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
        val chainLength = input.getIntOr(CHAIN_KEY, 0).coerceIn(0, PaternosterLoop.LONGEST_CHAIN)
        loop = if (chainLength > 0) PaternosterLoop(chainLength) else null
        shift = input.getIntOr(SHIFT_KEY, 0)
        direction = input.getIntOr(DIRECTION_KEY, 0).coerceIn(-1, 1)
        asked = input.getIntOr(ASKED_KEY, 0).coerceIn(-1, 1)
        ticksIntoStep = input.getIntOr(TICKS_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, books, true)
        output.putInt(CHAIN_KEY, loop?.chainLength ?: 0)
        output.putInt(SHIFT_KEY, shift)
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
        private const val DIRECTION_KEY = "direction"
        private const val ASKED_KEY = "asked"
        private const val TICKS_KEY = "ticks_into_step"
    }
}

/** The paternoster's registrations, for [co.voik.agesandtheart.content.AgeContent]'s lists. */
object Paternosters {
    val BASE_ID: Identifier = "paternoster_base".location()
    val CAP_ID: Identifier = "paternoster_cap".location()
    val SHELF_ID: Identifier = "paternoster_shelf".location()

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

    val BASE: PaternosterEndBlock = PaternosterEndBlock(true, properties(BASE_ID))
    val CAP: PaternosterEndBlock = PaternosterEndBlock(false, properties(CAP_ID))

    /** As hard to break as the chain it was; it drops that chain from its block entity, so it has no loot table. */
    val SHELF: PaternosterShelfBlock = PaternosterShelfBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, SHELF_ID))
            .mapColor(MapColor.METAL)
            .strength(STRENGTH_OF_A_CHAIN, RESISTANCE_OF_A_CHAIN)
            .sound(SoundType.CHAIN)
            .requiresCorrectToolForDrops()
            .noOcclusion()
            .noLootTable(),
    )

    val BASE_ITEM: Item = BlockItem(BASE, Item.Properties().setId(ResourceKey.create(Registries.ITEM, BASE_ID)).useBlockDescriptionPrefix())
    val CAP_ITEM: Item = BlockItem(CAP, Item.Properties().setId(ResourceKey.create(Registries.ITEM, CAP_ID)).useBlockDescriptionPrefix())

    val ENTITY: BlockEntityType<PaternosterBlockEntity> =
        BlockEntityType({ pos, state -> PaternosterBlockEntity(pos, state) }, setOf(BASE))

    val SHELF_ENTITY: BlockEntityType<PaternosterShelfBlockEntity> =
        BlockEntityType({ pos, state -> PaternosterShelfBlockEntity(pos, state) }, setOf(SHELF))
}
