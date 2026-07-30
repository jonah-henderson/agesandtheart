package co.voik.agesandtheart.sky

import co.voik.agesandtheart.Constants
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level

/**
 * What an Age's sky looks like, on its way to the client.
 *
 * **This payload is the whole reason per-Age skies are possible**, and it exists because the obvious route is
 * closed. A `DimensionType` cannot be composed per Age — `DimensionType.STREAM_CODEC` is
 * `ByteBufCodecs.holderRegistry`, which writes registry ids only, so an unregistered one cannot be encoded in the
 * join packet at all (the plan's step 8). Registries freeze at startup and Ages are made at runtime, so no
 * registry can hold this. Plain data on a channel can.
 *
 * **One payload type carries both jobs**, rather than one for "this Age" and one for "everything you know":
 * a list of entries covers a single Age before travel *and* the whole set on join, and a second type would be
 * two codecs to keep in step for no gain.
 *
 * Clientbound payloads cap at 1 MiB and a sky is a few hundred bytes, so even a player with a hundred Ages fits
 * comfortably. A client without the mod decodes this as vanilla's `DiscardedPayload` and ignores it rather than
 * disconnecting — worth knowing, because it means sending is safe but arrival is not guaranteed.
 */
data class SkyPayload(val skies: List<Entry>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SkyPayload> = TYPE

    /** One Age and the sky it has. */
    data class Entry(val dimension: ResourceKey<Level>, val spec: SkySpec)

    companion object {
        /**
         * Built with [ResourceLocation.fromNamespaceAndPath] rather than `CustomPacketPayload.createType`,
         * because that helper is `withDefaultNamespace` — it would silently claim `minecraft:skies`.
         */
        val TYPE: CustomPacketPayload.Type<SkyPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "skies"),
        )

        /**
         * A dimension key writes as a bare `ResourceLocation`, needing no registry — which matters, since the
         * whole point is that these Ages are in no registry the client can look up.
         *
         * The spec itself rides through [ByteBufCodecs.fromCodec], reusing the DFU codec rather than being
         * hand-written a second time. It costs an NBT round-trip and buys one definition of the format.
         */
        private val ENTRY_STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, Entry> = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION),
            Entry::dimension,
            ByteBufCodecs.fromCodec(SkySpec.CODEC),
            Entry::spec,
            ::Entry,
        )

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, SkyPayload> = StreamCodec.composite(
            ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list()),
            SkyPayload::skies,
            ::SkyPayload,
        )
    }
}
