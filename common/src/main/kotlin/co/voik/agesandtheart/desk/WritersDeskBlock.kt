package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.StringRepresentable
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult

/**
 * Which part of the desk a block is. The centre carries the block entity; every other part points at it.
 *
 * Five, not three: the bookshelf wings each carry a supply piece above them, which is where the room for
 * the screen came from (design §7.3, and the plan's desk rework). [INK_CASE] and [SUPPLY_BIN] are the only
 * parts a player can open something *else* with.
 */
enum class DeskPart(private val key: String) : StringRepresentable {
    LEFT("left"),
    CENTRE("centre"),
    RIGHT("right"),
    INK_CASE("ink_case"),
    SUPPLY_BIN("supply_bin"),
    ;

    override fun getSerializedName(): String = key
}

/**
 * The writer's desk: three wide, two tall, one block entity.
 *
 * ```
 *    [ink case]   ·   [supply bin]      <- nothing above the centre, so a writer can see over their desk
 *    [bookshelf] [desk] [bookshelf]
 * ```
 *
 * Built on the bed's pattern — a `PART` property plus matching placement and break handlers — because
 * vanilla has no multiblock system and this is the shape it uses when it needs one. Every question is
 * answered by the centre; the other four parts exist to be broken, to be looked at, and in two cases to be
 * opened.
 *
 * **Placement now wants headroom**, which is the price of the room: a desk refuses in a two-high corridor
 * where the three-wide one fitted. `getStateForPlacement` returning null is what makes the item bounce
 * rather than place a desk that instantly breaks.
 */
class WritersDeskBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, DeskPart.CENTRE),
        )
    }

    override fun codec(): MapCodec<WritersDeskBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, PART)
    }

    /** Only the centre has one, so the wings cost nothing to tick or save. */
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(PART) == DeskPart.CENTRE) WritersDeskBlockEntity(pos, state) else null

    /**
     * Refuses placement unless both wings have room. Returning null here is what makes the item bounce
     * off a wall rather than placing a desk that is instantly broken.
     */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.horizontalDirection.opposite
        val level = context.level
        for ((part, at) in aroundCentre(facing, context.clickedPos)) {
            if (part == DeskPart.CENTRE) continue
            if (!level.getBlockState(at).canBeReplaced(context)) return null
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, DeskPart.CENTRE)
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        if (level.isClientSide) return
        for ((part, at) in aroundCentre(state.getValue(FACING), pos)) {
            if (part != DeskPart.CENTRE) level.setBlock(at, wing(state, part), UPDATE_ALL)
        }
    }

    /**
     * Breaking any third breaks all three, and only the centre drops — otherwise a desk yields three
     * items, or a wing survives with nothing to point at.
     */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val centre = centreOf(state, pos)
        if (!level.isClientSide && !player.isCreative) {
            // Dropped from the centre so the item lands where the desk was, not where the wing was hit.
            Block.popResource(level, centre, ItemStack(AgeContent.WRITERS_DESK))
        }
        // Contents even in creative, as a chest does: what is inside was never the block's to keep, and a
        // desk emptied by being broken is how everything else in the game behaves.
        if (!level.isClientSide) entityAt(level, centre)?.let { desk -> popContents(level, centre, desk) }
        clearOthers(level, pos, state)
        return super.playerWillDestroy(level, pos, state, player)
    }

    /**
     * Everything the desk was holding, on the floor.
     *
     * **Ink is not among it.** A fluid has no item to be — it arrived by bottle or by pipe and the tank is
     * the unit — so a desk that emptied itself into bottles would be inventing them. The archive, the
     * paper and the binding all went in as items and come back out as ones.
     *
     * The menu returns its own slots and the pages laid out on the surface when it closes, so neither is
     * here: by the time a desk can be broken, it holds only what it filed.
     */
    private fun popContents(level: Level, at: BlockPos, desk: WritersDeskBlockEntity) {
        for (word in desk.archive.words) {
            val page = ItemStack(AgeContent.PAGE).also { it.set(AgeContent.PAGE_WORD, word) }
            popEvery(level, at, page, desk.archive.count(word))
        }
        for (tier in InkTier.entries) {
            popEvery(level, at, ItemStack(paperFor(tier)), desk.stores.paper(tier))
        }
        popEvery(level, at, ItemStack(Items.LEATHER), desk.stores.binding())
    }

    /** [count] of [stack], a stackful at a time, since `popResource` drops one stack per call. */
    private fun popEvery(level: Level, at: BlockPos, stack: ItemStack, count: Int) {
        var left = count
        while (left > 0) {
            val batch = left.coerceAtMost(stack.maxStackSize)
            Block.popResource(level, at, stack.copyWithCount(batch))
            left -= batch
        }
    }

    /** What a grade of paper is as an item. Common paper is a tag, so vanilla's own is what comes back. */
    private fun paperFor(tier: InkTier): Item = when (tier) {
        InkTier.COMMON -> Items.PAPER
        InkTier.FINE -> AgeContent.FINE_PAPER
        InkTier.MASTERWORK -> AgeContent.MASTERWORK_PAPER
    }

    /** Middle-click anywhere on the desk gives the desk, not a wing. */
    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack =
        ItemStack(AgeContent.WRITERS_DESK)

    /**
     * An empty hand opens **what you touched**: the ink case, the supply bin, or the desk itself.
     *
     * All three anchor on the centre, because the stores and the archive live on its block entity — a menu
     * anchored at the part you clicked would fail `stillValid` the moment it looked for one.
     */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.FAIL
        val centre = centreOf(state, pos)
        if (level.getBlockEntity(centre) !is WritersDeskBlockEntity) return InteractionResult.FAIL
        when (state.getValue(PART)) {
            DeskPart.INK_CASE -> InkCaseMenu.open(serverPlayer, centre)
            DeskPart.SUPPLY_BIN -> SupplyBinMenu.open(serverPlayer, centre)
            DeskPart.LEFT, DeskPart.RIGHT, DeskPart.CENTRE -> {
                WritersDeskMenu.open(serverPlayer, centre)
                DeskCommands.opened(serverPlayer)
            }
        }
        return InteractionResult.CONSUME
    }

    /**
     * Using an item on any part of the desk offers it to the stores — the same door the UI's input slot
     * uses, so the two can never disagree about what is accepted.
     */
    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!DeskIntake.accepts(itemStack)) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level.isClientSide) return InteractionResult.SUCCESS
        val desk = entityAt(level, pos) ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val result = DeskIntake.offer(desk, itemStack)
        if (!result.took) return InteractionResult.TRY_WITH_EMPTY_HAND
        player.setItemInHand(hand, result.remainder)
        if (!result.returned.isEmpty && !player.inventory.add(result.returned)) {
            player.drop(result.returned, false)
        }
        return InteractionResult.SUCCESS
    }

    /** The centre for any part, so a wing can answer questions by asking it. */
    fun centreOf(state: BlockState, pos: BlockPos): BlockPos {
        val facing = state.getValue(FACING)
        val (left, right) = sidesOf(facing)
        return when (state.getValue(PART)) {
            DeskPart.CENTRE -> pos
            DeskPart.LEFT -> pos.relative(right)
            DeskPart.RIGHT -> pos.relative(left)
            DeskPart.INK_CASE -> pos.below().relative(right)
            DeskPart.SUPPLY_BIN -> pos.below().relative(left)
        }
    }

    private fun clearOthers(level: Level, pos: BlockPos, state: BlockState) {
        val centre = centreOf(state, pos)
        for ((_, part) in aroundCentre(state.getValue(FACING), centre)) {
            if (part == pos) continue
            val other = level.getBlockState(part)
            if (other.block === this) {
                // No drop and no update flag that would re-trigger us.
                level.setBlock(part, other.fluidState.createLegacyBlock(), UPDATE_SUPPRESS_DROPS or UPDATE_ALL)
            }
        }
    }

    /** Furniture moved nearby means the next open must re-read the room. */
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        orientation: net.minecraft.world.level.redstone.Orientation?,
        movedByPiston: Boolean,
    ) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston)
        (level.getBlockEntity(centreOf(state, pos)) as? WritersDeskBlockEntity)?.forgetSurvey()
    }

    private fun wing(state: BlockState, part: DeskPart): BlockState =
        defaultBlockState().setValue(FACING, state.getValue(FACING)).setValue(PART, part)

    companion object {
        val CODEC: MapCodec<WritersDeskBlock> = simpleCodec(::WritersDeskBlock)

        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val PART: EnumProperty<DeskPart> = EnumProperty.create("part", DeskPart::class.java)

        /** The desk runs left-to-right across the face you stand at, so the wings sit beside you. */
        private fun sidesOf(facing: Direction): Pair<Direction, Direction> =
            facing.counterClockWise to facing.clockWise

        /**
         * Every part of a desk whose centre is at [centre], and where it sits.
         *
         * One list, read by placement, by breaking and by the initial build — three things that must agree
         * about what a desk *is*, and did not have to before there were five of them.
         */
        private fun aroundCentre(facing: Direction, centre: BlockPos): List<Pair<DeskPart, BlockPos>> {
            val (left, right) = sidesOf(facing)
            return listOf(
                DeskPart.CENTRE to centre,
                DeskPart.LEFT to centre.relative(left),
                DeskPart.RIGHT to centre.relative(right),
                DeskPart.INK_CASE to centre.relative(left).above(),
                DeskPart.SUPPLY_BIN to centre.relative(right).above(),
            )
        }

        /** The block entity for any part of the desk at [pos], or null if this is not a desk. */
        fun entityAt(level: BlockGetter, pos: BlockPos): WritersDeskBlockEntity? {
            val state = level.getBlockState(pos)
            val block = state.block as? WritersDeskBlock ?: return null
            return level.getBlockEntity(block.centreOf(state, pos)) as? WritersDeskBlockEntity
        }
    }
}
