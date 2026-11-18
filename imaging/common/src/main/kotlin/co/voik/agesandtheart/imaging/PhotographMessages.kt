package co.voik.agesandtheart.imaging

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The chunks a set-down recorder's photograph is drawn from, which the server streams and the client counts: those
 * ahead of the lens out to where the fog closes in, and two rows behind it, one so the row beside the lens can mesh and
 * one so that one can. Both sides work it out from the same pose.
 */
object PhotographRing {

    /** How far a photograph sees before the fog closes in. */
    const val SHOWN_RADIUS_CHUNKS = 6

    /** One further, since a chunk is meshed only once its neighbours have come. */
    const val RADIUS_CHUNKS = SHOWN_RADIUS_CHUNKS + 1

    /** One further again, since the client's chunk cache refuses a chunk outside its radius. */
    const val HELD_RADIUS_CHUNKS = RADIUS_CHUNKS + 1

    private const val ROWS_BEHIND = 2
    private const val BLOCKS_A_CHUNK = 16
    private const val HALF_A_CHUNK = 8

    fun centreOf(eye: Vec3): ChunkPos = ChunkPos.containing(BlockPos.containing(eye))

    /** The chunks for a photograph from [eye] along [yaw], nearest first. */
    fun around(eye: Vec3, yaw: Float): List<ChunkPos> {
        val forward = Vec3.directionFromRotation(0.0f, yaw)
        fun isNotFarBehind(chunk: ChunkPos): Boolean {
            val acrossX = chunk.x * BLOCKS_A_CHUNK + HALF_A_CHUNK - eye.x
            val acrossZ = chunk.z * BLOCKS_A_CHUNK + HALF_A_CHUNK - eye.z
            return acrossX * forward.x + acrossZ * forward.z >= -ROWS_BEHIND * BLOCKS_A_CHUNK
        }
        val centre = centreOf(eye)
        return ChunkPos.rangeClosed(centre, RADIUS_CHUNKS).toList().filter(::isNotFarBehind).sortedBy { centre.getChessboardDistance(it) }
    }
}

/**
 * A photograph wanted of a set-down recorder's view, from the client asked to draw it: where it stands and which way it
 * looks, and what a client cannot work out for itself about the level it stands in. Its chunks follow as
 * [PhotographChunk]s, and the picture goes up as [PhotographPiece]s.
 */
data class PhotographWanted(
    val id: UUID,
    val dimension: ResourceKey<Level>,
    val dimensionType: Identifier,
    val eyeX: Double,
    val eyeY: Double,
    val eyeZ: Double,
    val yaw: Float,
    val pitch: Float,
    /** The obfuscated seed, as a client level is given it; the raw one zooms its biomes differently. */
    val biomeZoomSeed: Long,
    val seaLevel: Int,
    val gameTime: Long,
    val rain: Float,
    val thunder: Float,
    val chunksComing: Int,
    val isFlat: Boolean,
) : CustomPacketPayload {

    val eye: Vec3 get() = Vec3(eyeX, eyeY, eyeZ)

    override fun type(): CustomPacketPayload.Type<PhotographWanted> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhotographWanted> = CustomPacketPayload.Type(Imaging.id("photograph_wanted"))

        val STREAM_CODEC: StreamCodec<ByteBuf, PhotographWanted> = StreamCodec.of(
            { buffer, value ->
                UUIDUtil.STREAM_CODEC.encode(buffer, value.id)
                Identifier.STREAM_CODEC.encode(buffer, value.dimension.identifier())
                Identifier.STREAM_CODEC.encode(buffer, value.dimensionType)
                buffer.writeDouble(value.eyeX)
                buffer.writeDouble(value.eyeY)
                buffer.writeDouble(value.eyeZ)
                buffer.writeFloat(value.yaw)
                buffer.writeFloat(value.pitch)
                ByteBufCodecs.VAR_LONG.encode(buffer, value.biomeZoomSeed)
                ByteBufCodecs.VAR_INT.encode(buffer, value.seaLevel)
                ByteBufCodecs.VAR_LONG.encode(buffer, value.gameTime)
                buffer.writeFloat(value.rain)
                buffer.writeFloat(value.thunder)
                ByteBufCodecs.VAR_INT.encode(buffer, value.chunksComing)
                buffer.writeBoolean(value.isFlat)
            },
            { buffer ->
                PhotographWanted(
                    id = UUIDUtil.STREAM_CODEC.decode(buffer),
                    dimension = ResourceKey.create(Registries.DIMENSION, Identifier.STREAM_CODEC.decode(buffer)),
                    dimensionType = Identifier.STREAM_CODEC.decode(buffer),
                    eyeX = buffer.readDouble(),
                    eyeY = buffer.readDouble(),
                    eyeZ = buffer.readDouble(),
                    yaw = buffer.readFloat(),
                    pitch = buffer.readFloat(),
                    biomeZoomSeed = ByteBufCodecs.VAR_LONG.decode(buffer),
                    seaLevel = ByteBufCodecs.VAR_INT.decode(buffer),
                    gameTime = ByteBufCodecs.VAR_LONG.decode(buffer),
                    rain = buffer.readFloat(),
                    thunder = buffer.readFloat(),
                    chunksComing = ByteBufCodecs.VAR_INT.decode(buffer),
                    isFlat = buffer.readBoolean(),
                )
            },
        )
    }
}

/** One chunk for the photograph [id], carrying vanilla's own two payloads unchanged, as a linking panel's do. */
class PhotographChunk(
    val id: UUID,
    val x: Int,
    val z: Int,
    val chunk: ClientboundLevelChunkPacketData,
    val light: ClientboundLightUpdatePacketData,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PhotographChunk> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhotographChunk> = CustomPacketPayload.Type(Imaging.id("photograph_chunk"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PhotographChunk> = StreamCodec.of(
            { buffer, value ->
                UUIDUtil.STREAM_CODEC.encode(buffer, value.id)
                buffer.writeInt(value.x)
                buffer.writeInt(value.z)
                ClientboundLevelChunkPacketData.STREAM_CODEC.encode(buffer, value.chunk)
                ClientboundLightUpdatePacketData.STREAM_CODEC.encode(buffer, value.light)
            },
            { buffer ->
                PhotographChunk(
                    id = UUIDUtil.STREAM_CODEC.decode(buffer),
                    x = buffer.readInt(),
                    z = buffer.readInt(),
                    chunk = ClientboundLevelChunkPacketData.STREAM_CODEC.decode(buffer),
                    light = ClientboundLightUpdatePacketData.STREAM_CODEC.decode(buffer),
                )
            },
        )
    }
}

/** One piece of a photograph going up, as [PicturePiece] is for a handheld shot, saying which photograph it is of. */
data class PhotographPiece(val id: UUID, val index: Int, val count: Int, val bytes: ByteArray) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PhotographPiece> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhotographPiece> = CustomPacketPayload.Type(Imaging.id("photograph_piece"))

        val STREAM_CODEC: StreamCodec<ByteBuf, PhotographPiece> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PhotographPiece::id,
            ByteBufCodecs.VAR_INT, PhotographPiece::index,
            ByteBufCodecs.VAR_INT, PhotographPiece::count,
            ByteBufCodecs.byteArray(PicturePiece.MOST_BYTES), PhotographPiece::bytes,
            ::PhotographPiece,
        )
    }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
