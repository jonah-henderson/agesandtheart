package co.voik.agesandtheart.book.panel

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

/**
 * What an open book's panel asks for, and what comes back (`notes/link-panel-research.md`).
 *
 * **A narrow redirection of vanilla's own world packets**, which is the technique Immersive Portals needs
 * in full generality and we need for one dimension and a known ring of chunks. The chunk and light payloads
 * below carry `ClientboundLevelChunkPacketData` and `ClientboundLightUpdatePacketData` verbatim — both are
 * public, both write and read themselves — so nothing here reimplements chunk serialisation and a format
 * change in vanilla is a format change we inherit rather than one we chase.
 *
 * **The client never generates.** Density functions, noise settings and placed features are server-only
 * registries and are never synced, so a client holding a book's words and seed still cannot build the
 * generator. Chunk data comes over the wire or the panel stays black.
 */
object PanelProtocol {

    /**
     * How far the orbit can see, in chunks, and therefore exactly what is force-loaded.
     *
     * **Fixed rather than derived from the render distance**, because the point of a fixed orbit is that
     * the chunk set is known and constant (`link-panel-research.md`). A player on a low render distance
     * gets the same panel as anyone else, and nobody's setting can make this unbounded.
     */
    const val RING_RADIUS_CHUNKS = 3

    /** The ring's side, in chunks — seven across at [RING_RADIUS_CHUNKS] three. */
    val RING_SIDE: Int get() = RING_RADIUS_CHUNKS * 2 + 1

    /** How many chunks one panel force-loads and streams. */
    val RING_CHUNKS: Int get() = RING_SIDE * RING_SIDE

    /**
     * Every chunk of the ring around [centre], **nearest first**.
     *
     * Nearest first because the fade *is* the load (§7.8.1): what a viewer sees first should be what the
     * camera is closest to, so the picture assembles outwards from the arrival rather than in a raster from
     * one corner.
     *
     * Chebyshev distance rather than Euclidean, because the ring is a square and the rings of a square are
     * what a square grows in — sorting by true distance would interleave the corners of one ring with the
     * edges of the next for no gain anybody could see.
     */
    fun ringAround(centre: ChunkPos): List<ChunkPos> = buildList {
        for (dx in -RING_RADIUS_CHUNKS..RING_RADIUS_CHUNKS) {
            for (dz in -RING_RADIUS_CHUNKS..RING_RADIUS_CHUNKS) {
                add(ChunkPos(centre.x + dx, centre.z + dz))
            }
        }
    }.sortedBy { maxOf(kotlin.math.abs(it.x - centre.x), kotlin.math.abs(it.z - centre.z)) }
}

/**
 * *"Show me this Age."* — sent when a bound book is opened to its panel.
 *
 * Names a dimension rather than a recipe, which is the whole of §7.5's guarantee holding here: **a panel
 * can only ask about an Age that already exists**, so opening a book can never write, generate or pay for
 * a world. A request naming a dimension the server does not know is dropped.
 */
data class PanelOpenRequest(val dimension: ResourceKey<Level>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PanelOpenRequest> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PanelOpenRequest> =
            CustomPacketPayload.Type("panel_open".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, PanelOpenRequest> = StreamCodec.of(
            { buffer, value -> ByteBufCodecs.STRING_UTF8.encode(buffer, value.dimension.identifier().toString()) },
            { buffer ->
                val id = Identifier.parse(ByteBufCodecs.STRING_UTF8.decode(buffer))
                PanelOpenRequest(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id))
            },
        )
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
     * **An `object`, and that is load-bearing rather than tidy.** `StreamCodec.unit` captures one instance
     * and *throws* on encode unless the value it is given `equals` it — so a class with identity equality,
     * constructed fresh at each send, makes every close request throw instead of sending. The ring would
     * then be held until the viewer disconnected, which is precisely the leak [PanelViews] is written to
     * make impossible.
     */
    val TYPE: CustomPacketPayload.Type<PanelCloseRequest> =
        CustomPacketPayload.Type("panel_close".location())

    val STREAM_CODEC: StreamCodec<ByteBuf, PanelCloseRequest> = StreamCodec.unit(PanelCloseRequest)
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
    /** What the orbit is centred on: the Age's arrival point. */
    val around: BlockPos,
    /**
     * The **obfuscated** biome zoom seed, so biome colours land where a visitor would see them.
     *
     * Not the world seed: `ServerLevel` is built with `BiomeManager.obfuscateSeed(seed)` — Ephemeris'
     * `RuntimeLevels` does exactly that for every Age — and a `ClientLevel` given the raw seed zooms its
     * biomes differently, drawing grass and water boundaries in the wrong places.
     */
    val biomeZoomSeed: Long,
    val seaLevel: Int,
    /** How many chunks are coming, so the panel knows when the picture is whole and can stop fading. */
    val chunksComing: Int,
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
