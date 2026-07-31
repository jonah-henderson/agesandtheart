package co.voik.agesandtheart.sky

import co.voik.agesandtheart.Constants
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level

/**
 * What an Age's sky looks like, on its way to the client.
 *
 * **The whole reason per-Age skies are possible.** `DimensionType.STREAM_CODEC` is
 * `ByteBufCodecs.holderRegistry` and writes registry ids only, so a per-Age dimension type cannot be
 * encoded in the join packet at all; registries freeze at startup and Ages are made at runtime, so no
 * registry can hold one. Plain data on a channel can.
 *
 * **One payload type for both jobs** — a list of entries covers a single Age before travel and the whole
 * set on join, where a second type would be two codecs to keep in step.
 *
 * A client without the mod decodes this as `DiscardedPayload` and ignores it rather than disconnecting, so
 * sending is safe but arrival is not guaranteed.
 */
data class SkyPayload(val skies: List<Entry>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SkyPayload> = TYPE

    /** One Age and the sky it has. */
    data class Entry(val dimension: ResourceKey<Level>, val spec: SkySpec)

    companion object {
        /**
         * Built with [Identifier.fromNamespaceAndPath] rather than `CustomPacketPayload.createType`,
         * because that helper is `withDefaultNamespace` — it would silently claim `minecraft:skies`.
         */
        val TYPE: CustomPacketPayload.Type<SkyPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "skies"),
        )

        /**
         * A dimension key writes as a bare `Identifier`, needing no registry — the whole point being
         * that these Ages are in none the client can look up. The spec rides through
         * [ByteBufCodecs.fromCodec], costing an NBT round-trip and buying one definition of the format.
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
