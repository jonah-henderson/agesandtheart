package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.location
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
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

// What an open book's panel asks for, and what comes back: a narrow redirection of vanilla's own world
// packets, for one dimension and a known ring of chunks (`notes/link-panel-research.md`).
//
// Chunk data has to come over the wire because density functions, noise settings and placed features are
// server-only registries and are never synced, so a client holding a book's words and seed still cannot
// build the generator. The geometry both sides agree about is `PanelRing`, which is not a message.

/**
 * *"Show me that book."* — sent when a bound book is opened to its panel, in a hand or on a lectern.
 *
 * Names where the book is rather than a dimension, because the Age may not exist yet: a bound book's world
 * is decided but is not made until something asks for it, and the server resolves the stack through
 * `BookAge` exactly as linking does. A lectern is checked rather than trusted — its book has to be ours,
 * lying open, and within reach of whoever asks. Design §7.5 is satisfied by the binding, not by the panel.
 */
data class PanelOpenRequest(val book: BookBeingRead) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PanelOpenRequest> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PanelOpenRequest> =
            CustomPacketPayload.Type("panel_open".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, PanelOpenRequest> =
            BookBeingRead.STREAM_CODEC.map(::PanelOpenRequest, PanelOpenRequest::book)
    }
}

/**
 * *"I have stopped looking."* — sent when the book closes, and the reason a panel costs nothing at rest.
 *
 * Carries nothing: a player views one panel at a time, so the server needs no more than the fact. It is
 * also sent on disconnect by the server's own cleanup, since a client that crashes with a book open would
 * otherwise hold a ring of chunks loaded forever.
 */
object PanelCloseRequest : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PanelCloseRequest> = TYPE

    /**
     * An `object` because `StreamCodec.unit` throws on encode unless the value it is given `equals` the
     * instance it captured, which a freshly constructed class with identity equality never would.
     */
    val TYPE: CustomPacketPayload.Type<PanelCloseRequest> =
        CustomPacketPayload.Type("panel_close".location())

    val STREAM_CODEC: StreamCodec<ByteBuf, PanelCloseRequest> = StreamCodec.unit(PanelCloseRequest)
}

/**
 * *"These never arrived."* — the client naming the chunks of its ring it still has not got.
 *
 * The stream is fire-and-forget in both directions, and a chunk can be lost three ways: a future that
 * comes back without a `LevelChunk`, a view closed while chunks are still generating, or a client cache
 * that refuses a position. The client asks rather than the server retrying, because two of the three
 * happen where the server believes it succeeded.
 */
data class PanelChunksWanted(val positions: List<ChunkPos>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PanelChunksWanted> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PanelChunksWanted> =
            CustomPacketPayload.Type("panel_chunks_wanted".location())

        /** Vanilla's own, so the cap is enforced on decode rather than by a truncation of ours. */
        val STREAM_CODEC: StreamCodec<ByteBuf, PanelChunksWanted> =
            ChunkPos.STREAM_CODEC.apply(ByteBufCodecs.list<ByteBuf, ChunkPos>(PanelRing.COUNT))
                .map(::PanelChunksWanted, PanelChunksWanted::positions)
    }
}

/**
 * Everything the client needs to stand a preview level up, sent once before any chunk.
 *
 * These are exactly `ClientLevel`'s constructor arguments that a client cannot derive: it has the registries
 * but not this level's identity, and `DimensionType` reaches the client as a registry entry it can look up
 * by id.
 */
data class PanelLevelPayload(
    val dimension: ResourceKey<Level>,
    /** The dimension type's registry id — the client resolves the holder itself. */
    val dimensionType: Identifier,
    /** What the orbit is centred on: where the book would put you — an Age's arrival, or a linking book's spot. */
    val around: BlockPos,
    /**
     * The obfuscated seed, not the world seed: a `ClientLevel` given the raw one zooms its biomes
     * differently and draws grass and water boundaries in the wrong places.
     */
    val biomeZoomSeed: Long,
    val seaLevel: Int,
    /** How many chunks are coming, so the panel knows when the picture is whole and can stop fading. */
    val chunksComing: Int,
    /**
     * The Age's own clock, which nothing else would give the preview.
     *
     * A `ClientLevel` outside `minecraft.level` is never ticked and never told the time, so without this it
     * sits at zero: the same moment of the same day for every Age, and no animated textures, since the
     * Globals UBO's time drives those.
     */
    val gameTime: Long,
    /**
     * The Age's instability index, which the panel wears as distortion (design §7.3).
     *
     * The index and nothing that went into it: the panel says *that* an Age is at odds with itself, and
     * the desk still owns diagnosis.
     */
    val instability: Int,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PanelLevelPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PanelLevelPayload> =
            CustomPacketPayload.Type("panel_level".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, PanelLevelPayload> = StreamCodec.of(
            { buffer, value ->
                ByteBufCodecs.STRING_UTF8.encode(buffer, value.dimension.identifier().toString())
                ByteBufCodecs.STRING_UTF8.encode(buffer, value.dimensionType.toString())
                BlockPos.STREAM_CODEC.encode(buffer, value.around)
                ByteBufCodecs.VAR_LONG.encode(buffer, value.biomeZoomSeed)
                ByteBufCodecs.VAR_INT.encode(buffer, value.seaLevel)
                ByteBufCodecs.VAR_INT.encode(buffer, value.chunksComing)
                ByteBufCodecs.VAR_LONG.encode(buffer, value.gameTime)
                ByteBufCodecs.VAR_INT.encode(buffer, value.instability)
            },
            { buffer ->
                val dimension = Identifier.parse(ByteBufCodecs.STRING_UTF8.decode(buffer))
                PanelLevelPayload(
                    dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension),
                    dimensionType = Identifier.parse(ByteBufCodecs.STRING_UTF8.decode(buffer)),
                    around = BlockPos.STREAM_CODEC.decode(buffer),
                    biomeZoomSeed = ByteBufCodecs.VAR_LONG.decode(buffer),
                    seaLevel = ByteBufCodecs.VAR_INT.decode(buffer),
                    chunksComing = ByteBufCodecs.VAR_INT.decode(buffer),
                    gameTime = ByteBufCodecs.VAR_LONG.decode(buffer),
                    instability = ByteBufCodecs.VAR_INT.decode(buffer),
                )
            },
        )
    }
}

/**
 * One chunk of the ring, carrying vanilla's own two payloads unchanged.
 *
 * **Not `ByteArray`**: `ClientboundLevelChunkPacketData` writes and reads itself against a
 * `RegistryFriendlyByteBuf`, so handing it the buffer costs one copy fewer and keeps us out of the business
 * of knowing what a chunk looks like on the wire.
 */
class PanelChunkPayload(
    val x: Int,
    val z: Int,
    val chunk: ClientboundLevelChunkPacketData,
    val light: ClientboundLightUpdatePacketData,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PanelChunkPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PanelChunkPayload> =
            CustomPacketPayload.Type("panel_chunk".location())

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PanelChunkPayload> = StreamCodec.of(
            { buffer, value ->
                buffer.writeInt(value.x)
                buffer.writeInt(value.z)
                value.chunk.write(buffer)
                value.light.write(buffer)
            },
            { buffer ->
                val x = buffer.readInt()
                val z = buffer.readInt()
                PanelChunkPayload(
                    x = x,
                    z = z,
                    chunk = ClientboundLevelChunkPacketData(buffer, x, z),
                    light = ClientboundLightUpdatePacketData(buffer, x, z),
                )
            },
        )
    }
}
