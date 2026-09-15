package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.Acquaintance
import co.voik.agesandtheart.age.word.Acquainted
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult

/**
 * The D'ni surveying device: leave it standing in a place and it learns what the Art calls it (design §8.3).
 *
 * **Deployed rather than carried in hand**, because a survey is work and work takes time. Its twin the
 * analysis machine is a station the referent is brought to; this one is a station you *carry to the
 * referent* and set down, which is presence made a thing you do rather than a button you press: you must
 * have reached the crimson forest, and then stood an instrument in it.
 *
 * **Three stages ([DeviceStage]), in the block state and nowhere else.** A place cannot move, so the biome
 * under the device when it finishes is the biome that was under it when it started — there is no result
 * worth remembering between the two, and so **no block entity**, which is the one way this differs from the
 * analysis machine: that one destroys its sample going in and has to remember what it ate. The delay is a
 * scheduled tick for the same reason: vanilla already saves one with the chunk.
 *
 * **A survey consumes nothing** — not the device, not the place. The price is the travel and the wait, which
 * is the honest cost for a referent whose whole nature is *where* it is.
 */
class SurveyingDeviceBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(DeviceStage.PROPERTY, DeviceStage.IDLE))
    }

    override fun codec(): MapCodec<SurveyingDeviceBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(DeviceStage.PROPERTY)
    }

    /**
     * One interaction, and what it does is whatever the device is ready for.
     *
     * Only the empty-hand door, unlike the machine's: nothing is fed to a surveying device, so an item in
     * hand is no part of the reading and a player sneaking with one should be able to build against it.
     */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val surveyor = player as? ServerPlayer ?: return InteractionResult.FAIL
        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        when (state.getValue(DeviceStage.PROPERTY)) {
            DeviceStage.IDLE -> begin(serverLevel, pos, state, surveyor)
            DeviceStage.WORKING -> DeviceWork.keepWorking(
                serverLevel, pos, this, surveyor, "device.agesandtheart.surveying_device.working",
            )
            DeviceStage.READY -> hand(serverLevel, pos, state, surveyor)
        }
        return InteractionResult.SUCCESS
    }

    private fun begin(level: ServerLevel, pos: BlockPos, state: BlockState, surveyor: ServerPlayer) {
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.WORKING), UPDATE_ALL)
        level.scheduleTick(pos, this, DeviceStage.WORK_TICKS)
        level.playSound(null, pos, SoundEvents.SPYGLASS_USE, SoundSource.BLOCKS, DeviceWork.VOLUME, DeviceWork.PITCH)
        DeviceWork.say(surveyor, "device.agesandtheart.surveying_device.started")
    }

    /** The reading is done. Nothing is recorded, because the place will still be there to be read. */
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        DeviceWork.finishWork(level, pos, state)
    }

    /**
     * The reading, handed over — and spent whatever it was worth. A refusal costs only another wait, since
     * the same ground can always be surveyed again.
     */
    private fun hand(level: ServerLevel, pos: BlockPos, state: BlockState, surveyor: ServerPlayer) {
        val outcome = Acquaintance.withPlace(surveyor, level, pos)
        Acquaintance.tell(surveyor, outcome)
        if (outcome is Acquainted.Learned) {
            level.playSound(
                null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, DeviceWork.VOLUME, DeviceWork.PITCH,
            )
        }
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.IDLE), UPDATE_ALL)
    }

    /** A running device is visibly running, which is the only thing the wait has to say for itself. */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        DeviceWork.workingParticles(level, pos, random, state)
    }

    companion object {
        val CODEC: MapCodec<SurveyingDeviceBlock> = simpleCodec(::SurveyingDeviceBlock)
    }
}
