package co.voik.agesandtheart.imaging

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The chunks a set-down recorder's photograph is drawn from, which the server streams and the client counts: those
 * ahead of the lens out to where the fog closes in, the photographing player's own render distance so a photograph
 * sees as far as their view through the recorder did, and two rows behind it, one so the row beside the lens can mesh
 * and one so that one can. Both sides work it out from the same pose and distance.
 */
object PhotographRing {

    /** The fewest chunks a photograph sees, as vanilla's own render distance allows no fewer. */
    const val LEAST_SHOWN_RADIUS_CHUNKS = 2

    /** How far it is streamed: one further than [shownRadius], since a chunk is meshed only once its neighbours come. */
    fun streamedRadius(shownRadius: Int): Int = shownRadius + 1

    /** How far chunks are held, one further again, since the client's chunk cache refuses a chunk outside its radius. */
    fun heldRadius(shownRadius: Int): Int = shownRadius + 2

    private const val ROWS_BEHIND = 2
    private const val BLOCKS_A_CHUNK = 16
    private const val HALF_A_CHUNK = 8

    fun centreOf(eye: Vec3): ChunkPos = ChunkPos.containing(BlockPos.containing(eye))

    /** The chunks for a photograph from [eye] along [yaw] seeing [shownRadius] chunks, nearest first. */
    fun around(eye: Vec3, yaw: Float, shownRadius: Int): List<ChunkPos> {
        val forward = Vec3.directionFromRotation(0.0f, yaw)
        fun isNotFarBehind(chunk: ChunkPos): Boolean {
            val acrossX = chunk.x * BLOCKS_A_CHUNK + HALF_A_CHUNK - eye.x
            val acrossZ = chunk.z * BLOCKS_A_CHUNK + HALF_A_CHUNK - eye.z
            return acrossX * forward.x + acrossZ * forward.z >= -ROWS_BEHIND * BLOCKS_A_CHUNK
        }
        val centre = centreOf(eye)
        return ChunkPos.rangeClosed(centre, streamedRadius(shownRadius)).toList()
            .filter(::isNotFarBehind)
            .sortedBy { centre.getChessboardDistance(it) }
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
    /** How many chunks out it sees before the fog: the photographing player's render distance. */
    val shownRadiusChunks: Int,
    val isFlat: Boolean,
    /** The vertical field of view, the recorder's zoom included. */
    val fieldOfView: Float,
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
                ByteBufCodecs.VAR_INT.encode(buffer, value.shownRadiusChunks)
                buffer.writeBoolean(value.isFlat)
                buffer.writeFloat(value.fieldOfView)
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
                    shownRadiusChunks = ByteBufCodecs.VAR_INT.decode(buffer),
                    isFlat = buffer.readBoolean(),
                    fieldOfView = buffer.readFloat(),
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

/**
 * What stood in front of the lens when the photograph [id] was taken, as the packets that bring each into a client's
 * view: what it is and where, its data, and what it wears and holds. A snapshot, sent once its chunks are on the way.
 */
class PhotographEntities(
    val id: UUID,
    val arrivals: List<ClientboundAddEntityPacket>,
    val data: List<ClientboundSetEntityDataPacket>,
    val equipment: List<ClientboundSetEquipmentPacket>,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PhotographEntities> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PhotographEntities> = CustomPacketPayload.Type(Imaging.id("photograph_entities"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PhotographEntities> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PhotographEntities::id,
            ClientboundAddEntityPacket.STREAM_CODEC.apply(ByteBufCodecs.list()), PhotographEntities::arrivals,
            ClientboundSetEntityDataPacket.STREAM_CODEC.apply(ByteBufCodecs.list()), PhotographEntities::data,
            ClientboundSetEquipmentPacket.STREAM_CODEC.apply(ByteBufCodecs.list()), PhotographEntities::equipment,
            ::PhotographEntities,
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
