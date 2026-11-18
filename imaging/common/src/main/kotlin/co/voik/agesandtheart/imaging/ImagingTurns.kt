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

/** A recorder on a tripod aimed along [yaw] and [pitch] by a player looking through it. */
data class AimTheRecorder(val pos: BlockPos, val yaw: Float, val pitch: Float) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<AimTheRecorder> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<AimTheRecorder> = CustomPacketPayload.Type(Imaging.id("aim_the_recorder"))

        val STREAM_CODEC: StreamCodec<ByteBuf, AimTheRecorder> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AimTheRecorder::pos,
            ByteBufCodecs.FLOAT, AimTheRecorder::yaw,
            ByteBufCodecs.FLOAT, AimTheRecorder::pitch,
            ::AimTheRecorder,
        )
    }
}

/** A set-down recorder zoomed to [zoom], a share of its widest view, by a player looking through it. */
data class ZoomTheRecorder(val pos: BlockPos, val zoom: Float) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ZoomTheRecorder> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ZoomTheRecorder> = CustomPacketPayload.Type(Imaging.id("zoom_the_recorder"))

        val STREAM_CODEC: StreamCodec<ByteBuf, ZoomTheRecorder> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ZoomTheRecorder::pos,
            ByteBufCodecs.FLOAT, ZoomTheRecorder::zoom,
            ::ZoomTheRecorder,
        )
    }
}

/** The server's word to look through the recorder at [pos], as using it sneaking does: its screen's Adjust button. */
data class LookThroughTheRecorder(val pos: BlockPos) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LookThroughTheRecorder> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LookThroughTheRecorder> = CustomPacketPayload.Type(Imaging.id("look_through_the_recorder"))

        val STREAM_CODEC: StreamCodec<ByteBuf, LookThroughTheRecorder> =
            BlockPos.STREAM_CODEC.map(::LookThroughTheRecorder, LookThroughTheRecorder::pos)
    }
}

/** What a turn, an aim or a zoom asks of the server: the recorder within reach, turned a step either way or aimed. */
object ImagingTurns {

    /** As far as a player may stand from a recorder they look through, and turn it. */
    private const val REACH = 8.0

    fun asked(player: ServerPlayer, turn: TurnTheRecorder) {
        val level = player.level()
        val state = level.getBlockState(turn.pos)
        val isARecorderOnTheGround = state.`is`(ImagingContent.IMAGE_RECORDER_BLOCK) && !state.getValue(ImageRecorderBlock.MOUNTED)
        if (!isARecorderOnTheGround || !isWithinReach(player, turn.pos)) return
        val by = turn.by.coerceIn(-1, 1)
        level.setBlock(turn.pos, ImageRecorderBlock.turned(state, by), Block.UPDATE_ALL)
        level.playSound(null, turn.pos, SoundEvents.LANTERN_HIT, SoundSource.BLOCKS, TURN_VOLUME, TURN_PITCH)
    }

    fun aimed(player: ServerPlayer, aim: AimTheRecorder) {
        val state = player.level().getBlockState(aim.pos)
        val isMounted = state.`is`(ImagingContent.IMAGE_RECORDER_BLOCK) && state.getValue(ImageRecorderBlock.MOUNTED)
        val isARealAim = aim.yaw.isFinite() && aim.pitch.isFinite()
        if (!isMounted || !isARealAim || !isWithinReach(player, aim.pos)) return
        (player.level().getBlockEntity(aim.pos) as? ImageRecorderBlockEntity)?.aim(aim.yaw, aim.pitch)
    }

    fun zoomed(player: ServerPlayer, zoom: ZoomTheRecorder) {
        val isARecorder = player.level().getBlockState(zoom.pos).`is`(ImagingContent.IMAGE_RECORDER_BLOCK)
        if (!isARecorder || !zoom.zoom.isFinite() || !isWithinReach(player, zoom.pos)) return
        (player.level().getBlockEntity(zoom.pos) as? ImageRecorderBlockEntity)?.zoomTo(zoom.zoom)
    }

    private fun isWithinReach(player: ServerPlayer, pos: BlockPos): Boolean = player.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH

    private const val TURN_VOLUME = 0.4f
    private const val TURN_PITCH = 1.4f
}
