package co.voik.agesandtheart.book

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
import net.minecraft.tags.ItemTags
import net.minecraft.util.Prediction
import net.minecraft.util.RandomSource
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
 * Used on its front, it shelves a book on the front tray or takes the one there, as a chiselled shelf does; used on a
 * face that shows another tray, it turns that tray round to the front the short way; used on an end of its axle, it
 * turns a tray on, or back when sneaking. Breaking it drops its books, as a chest does.
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
    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) createTickerHelper(type, BookWheels.ENTITY) { _, _, _, wheel -> wheel.turnTheWheel() } else null

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
        val face = WheelFace.of(hitResult.direction, state.getValue(FACING))
        val position = form.axle.positionShownBy(face)
        if (position != 0) {
            val by = when {
                position == null -> if (player.isSecondaryUseActive) -1 else 1
                position * 2 > WheelForm.TRAYS -> position - WheelForm.TRAYS
                else -> position
            }
            if (!level.isClientSide) wheel.turn(by)
            return InteractionResult.SUCCESS
        }
        val along = form.part?.let { state.getValue(it).along } ?: 0
        val atTheFront = wheel.atTheFront(along)
        if (atTheFront.isEmpty) {
            if (held.isEmpty) return InteractionResult.PASS
            if (!level.isClientSide) wheel.shelve(along, held.consumeAndReturn(1, player))
            return InteractionResult.SUCCESS
        }
        if (!level.isClientSide) player.inventory.placeItemBackInInventory(wheel.take(along), Prediction.SERVER_ONLY)
        return InteractionResult.SUCCESS
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

class SmallBookWheelBlock(properties: Properties) : BookWheelBlock(properties) {
    override val form: WheelForm get() = WheelForm.SMALL
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
class BookWheelBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(BookWheels.ENTITY, pos, state) {

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

    fun bookIn(slot: Int): ItemStack = books[slot]

    fun slotOf(tray: Int, along: Int): Int = tray * form.booksPerTray + along

    fun atTheFront(along: Int): ItemStack = books[slotOf(frontTray, along)]

    fun turnTheWheel() = turning.tick(frontTray, lastTurn)

    /** [book] onto the front tray's place [along], which is empty. */
    fun shelve(along: Int, book: ItemStack) {
        val slot = slotOf(frontTray, along)
        if (!books[slot].isEmpty) return
        books[slot] = book
        val enchanted = book.`is`(Items.ENCHANTED_BOOK)
        play(if (enchanted) SoundEvents.CHISELED_BOOKSHELF_INSERT_ENCHANTED else SoundEvents.CHISELED_BOOKSHELF_INSERT)
        changed()
    }

    /** The book in the front tray's place [along], taken off it. */
    fun take(along: Int): ItemStack {
        val slot = slotOf(frontTray, along)
        val taken = books[slot]
        books[slot] = ItemStack.EMPTY
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
        lastTurn = if (input.getIntOr(LAST_TURN_KEY, 1) < 0) -1 else 1
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, books, true)
        output.putInt(FRONT_KEY, frontTray)
        output.putInt(LAST_TURN_KEY, lastTurn)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    private companion object {
        const val FRONT_KEY = "front"
        const val LAST_TURN_KEY = "last_turn"
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
        BlockItem(block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, block.form.id.location())).useBlockDescriptionPrefix())
    }

    val ENTITY_ID: Identifier = "book_wheel".location()

    val ENTITY: BlockEntityType<BookWheelBlockEntity> =
        BlockEntityType({ pos, state -> BookWheelBlockEntity(pos, state) }, BLOCKS.toSet())
}
