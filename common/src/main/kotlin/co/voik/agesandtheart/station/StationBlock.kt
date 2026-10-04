package co.voik.agesandtheart.station

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
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
 * A grinder or a pulper — see [Station]. Using it opens its screen.
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

    /** Opens its screen, as a furnace does; an item in the hand is not loaded straight in. */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val entity = level.getBlockEntity(pos) as? StationBlockEntity ?: return InteractionResult.FAIL
        MachineRecipeLists.open(player, entity)
        return InteractionResult.CONSUME
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
