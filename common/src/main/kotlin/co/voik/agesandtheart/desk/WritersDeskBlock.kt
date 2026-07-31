package co.voik.agesandtheart.desk

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
import net.minecraft.world.item.ItemStack
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

/** Which third of the desk a block is. The centre carries the block entity; the wings only point at it. */
enum class DeskPart(private val key: String) : StringRepresentable {
    LEFT("left"),
    CENTRE("centre"),
    RIGHT("right"),
    ;

    override fun getSerializedName(): String = key
}

/**
 * The writer's desk: three blocks wide, one block entity.
 *
 * Built on the bed's pattern — a `PART` property plus matching placement and break handlers — because
 * vanilla has no multiblock system and this is the shape it uses when it needs one. The wings exist only
 * to be broken and to be looked at; every question is answered by the centre.
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
        val centre = context.clickedPos
        val level = context.level
        val (left, right) = sidesOf(facing)
        for (side in listOf(left, right)) {
            if (!level.getBlockState(centre.relative(side)).canBeReplaced(context)) return null
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, DeskPart.CENTRE)
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        if (level.isClientSide) return
        val facing = state.getValue(FACING)
        val (left, right) = sidesOf(facing)
        level.setBlock(pos.relative(left), wing(state, DeskPart.LEFT), UPDATE_ALL)
        level.setBlock(pos.relative(right), wing(state, DeskPart.RIGHT), UPDATE_ALL)
    }

    /**
     * Breaking any third breaks all three, and only the centre drops — otherwise a desk yields three
     * items, or a wing survives with nothing to point at.
     */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide && !player.isCreative) {
            // Dropped from the centre so the item lands where the desk was, not where the wing was hit.
            Block.popResource(level, centreOf(state, pos), ItemStack(AgeContent.WRITERS_DESK))
        }
        clearOthers(level, pos, state)
        return super.playerWillDestroy(level, pos, state, player)
    }

    /** Middle-click anywhere on the desk gives the desk, not a wing. */
    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack =
        ItemStack(AgeContent.WRITERS_DESK)

    /** An empty hand opens the desk; any part of it, since the wings are the same furniture. */
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
        WritersDeskMenu.open(serverPlayer, centre)
        DeskCommands.opened(serverPlayer)
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
        }
    }

    private fun clearOthers(level: Level, pos: BlockPos, state: BlockState) {
        val centre = centreOf(state, pos)
        val facing = state.getValue(FACING)
        val (left, right) = sidesOf(facing)
        for (part in listOf(centre, centre.relative(left), centre.relative(right))) {
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

        /** The block entity for any part of the desk at [pos], or null if this is not a desk. */
        fun entityAt(level: BlockGetter, pos: BlockPos): WritersDeskBlockEntity? {
            val state = level.getBlockState(pos)
            val block = state.block as? WritersDeskBlock ?: return null
            return level.getBlockEntity(block.centreOf(state, pos)) as? WritersDeskBlockEntity
        }
    }
}
