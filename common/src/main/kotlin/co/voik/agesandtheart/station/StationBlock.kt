package co.voik.agesandtheart.station

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult

/**
 * A grinder or a pulper — see [Station]. Use an input on it to load it, an empty hand to collect, and a
 * sneaking empty hand to take the input back.
 */
class StationBlock(val station: Station, properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(StationActivity.PROPERTY, StationActivity.IDLE))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = StationBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(StationActivity.PROPERTY)
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, AgeContent.STATION_ENTITY, ::tickStation)

    /** Anything it cannot work goes on to the empty hand, as a jukebox passes on what is not a disc. */
    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? StationBlockEntity ?: return InteractionResult.FAIL
        if (!entity.accepts(itemStack)) return InteractionResult.TRY_WITH_EMPTY_HAND
        load(entity, itemStack, player)
        return InteractionResult.SUCCESS
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? StationBlockEntity ?: return InteractionResult.FAIL
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.FAIL
        val wantsTheInputBack = player.isSecondaryUseActive && !entity.inputStack.isEmpty
        when {
            wantsTheInputBack -> hand(entity, StationBlockEntity.INPUT, serverPlayer)
            !entity.outputStack.isEmpty -> hand(entity, StationBlockEntity.OUTPUT, serverPlayer)
            else -> return InteractionResult.PASS
        }
        return InteractionResult.SUCCESS
    }

    /** As much of the stack as fits alongside what is already waiting. */
    private fun load(entity: StationBlockEntity, offered: ItemStack, player: Player) {
        val waiting = entity.inputStack
        val canJoin = waiting.isEmpty || ItemStack.isSameItemSameComponents(waiting, offered)
        if (!canJoin) return
        val room = entity.getMaxStackSize(offered) - waiting.count
        val moved = minOf(room, offered.count)
        if (moved <= 0) return
        entity.setItem(StationBlockEntity.INPUT, offered.copyWithCount(waiting.count + moved))
        offered.consume(moved, player)
    }

    private fun hand(entity: StationBlockEntity, slot: Int, player: ServerPlayer) {
        val taken = entity.removeItem(slot, entity.getItem(slot).count)
        player.inventory.placeItemBackInInventory(taken, Prediction.SERVER_ONLY)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (state.getValue(StationActivity.PROPERTY) != StationActivity.WORKING) return
        level.addParticle(
            station.workingParticle,
            pos.x + random.nextDouble(),
            pos.y + ABOVE_THE_STATION,
            pos.z + random.nextDouble(),
            0.0,
            0.0,
            0.0,
        )
    }

    companion object {
        private const val ABOVE_THE_STATION = 1.05

        private fun tickStation(level: Level, pos: BlockPos, state: BlockState, entity: StationBlockEntity) {
            val serverLevel = level as? ServerLevel ?: return
            val activity = entity.work(serverLevel)
            if (state.getValue(StationActivity.PROPERTY) != activity) {
                level.setBlock(pos, state.setValue(StationActivity.PROPERTY, activity), UPDATE_ALL)
            }
        }
    }
}
