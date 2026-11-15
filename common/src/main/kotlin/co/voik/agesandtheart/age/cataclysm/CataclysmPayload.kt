package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.location
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * The end coming to the Age you stand in, or null where none is: the client draws and hears all of it from
 * the schedule and its own clock. [age] lets a client refuse a message meant for the world it just left.
 */
data class CataclysmPayload(val age: Identifier, val doom: Doom?) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<CataclysmPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<CataclysmPayload> = CustomPacketPayload.Type("cataclysm".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, CataclysmPayload> = StreamCodec.of(::write, ::read)

        private fun write(buffer: ByteBuf, payload: CataclysmPayload) {
            Identifier.STREAM_CODEC.encode(buffer, payload.age)
            val doom = payload.doom
            ByteBufCodecs.BOOL.encode(buffer, doom != null)
            if (doom == null) return
            ByteBufCodecs.VAR_INT.encode(buffer, doom.kind.ordinal)
            ByteBufCodecs.VAR_LONG.encode(buffer, doom.startsAt)
            ByteBufCodecs.VAR_INT.encode(buffer, doom.timeline.approach)
            ByteBufCodecs.VAR_INT.encode(buffer, doom.timeline.entry)
            ByteBufCodecs.VAR_INT.encode(buffer, doom.timeline.travel)
            ByteBufCodecs.VAR_INT.encode(buffer, doom.timeline.heat)
            ByteBufCodecs.VAR_INT.encode(buffer, doom.timeline.dust)
            ByteBufCodecs.FLOAT.encode(buffer, doom.bearingDegrees)
        }

        private fun read(buffer: ByteBuf): CataclysmPayload {
            val age = Identifier.STREAM_CODEC.decode(buffer)
            if (!ByteBufCodecs.BOOL.decode(buffer)) return CataclysmPayload(age, null)
            val kind = CataclysmKind.entries[ByteBufCodecs.VAR_INT.decode(buffer)]
            val startsAt = ByteBufCodecs.VAR_LONG.decode(buffer)
            val timeline = ImpactTimeline(
                approach = ByteBufCodecs.VAR_INT.decode(buffer),
                entry = ByteBufCodecs.VAR_INT.decode(buffer),
                travel = ByteBufCodecs.VAR_INT.decode(buffer),
                heat = ByteBufCodecs.VAR_INT.decode(buffer),
                dust = ByteBufCodecs.VAR_INT.decode(buffer),
            )
            val bearing = ByteBufCodecs.FLOAT.decode(buffer)
            return CataclysmPayload(age, Doom(kind, startsAt, timeline, bearing))
        }
    }
}
