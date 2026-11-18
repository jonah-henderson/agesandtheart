package co.voik.agesandtheart.imaging

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3

/** A set-down recorder turned [by] sixteenths, clockwise where positive, by a player looking through it. */
data class TurnTheRecorder(val pos: BlockPos, val by: Int) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<TurnTheRecorder> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TurnTheRecorder> = CustomPacketPayload.Type(Imaging.id("turn_the_recorder"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TurnTheRecorder> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TurnTheRecorder::pos,
            ByteBufCodecs.VAR_INT, TurnTheRecorder::by,
            ::TurnTheRecorder,
        )
    }
}

/** What a turn asks of the server: the recorder within reach, turned a step either way. */
object ImagingTurns {

    /** As far as a player may stand from a recorder they look through, and turn it. */
    private const val REACH = 8.0

    fun asked(player: ServerPlayer, turn: TurnTheRecorder) {
        val level = player.level()
        val state = level.getBlockState(turn.pos)
        val isARecorder = state.`is`(ImagingContent.IMAGE_RECORDER_BLOCK)
        val isWithinReach = player.distanceToSqr(Vec3.atCenterOf(turn.pos)) <= REACH * REACH
        if (!isARecorder || !isWithinReach) return
        val by = turn.by.coerceIn(-1, 1)
        level.setBlock(turn.pos, ImageRecorderBlock.turned(state, by), Block.UPDATE_ALL)
        level.playSound(null, turn.pos, SoundEvents.LANTERN_HIT, SoundSource.BLOCKS, TURN_VOLUME, TURN_PITCH)
    }

    private const val TURN_VOLUME = 0.4f
    private const val TURN_PITCH = 1.4f
}
