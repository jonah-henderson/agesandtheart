package co.voik.agesandtheart.sky

import co.voik.runtimelevels.sky.Look
import co.voik.runtimelevels.sky.SkySpec

import co.voik.agesandtheart.Constants
import com.mojang.serialization.Codec
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level

/**
 * **What an Age looks like, on its way to the client** — its sky, and the air the eye sees through.
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
data class LookPayload(val skies: List<Entry>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LookPayload> = TYPE

    /**
     * One Age, the sky it has, and how the air is painted — Age-wide and in each corner a sentence
     * confined something to.
     *
     * Both halves ride one entry because both are the same fact: what this client should show for that
     * dimension. A second payload would be a second codec, a second dispatch and a second chance for the
     * two to disagree about an Age.
     */
    data class Entry(
        val dimension: ResourceKey<Level>,
        val spec: SkySpec,
        val look: Look = Look.NOTHING,
        val corners: Map<Identifier, Look> = emptyMap(),
    )

    companion object {
        /**
         * Built with [Identifier.fromNamespaceAndPath] rather than `CustomPacketPayload.createType`,
         * because that helper is `withDefaultNamespace` — it would silently claim `minecraft:skies`.
         */
        val TYPE: CustomPacketPayload.Type<LookPayload> = CustomPacketPayload.Type(
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
            ByteBufCodecs.fromCodec(Look.CODEC),
            Entry::look,
            ByteBufCodecs.fromCodec(Codec.unboundedMap(Identifier.CODEC, Look.CODEC)),
            Entry::corners,
            ::Entry,
        )

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, LookPayload> = StreamCodec.composite(
            ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list()),
            LookPayload::skies,
            ::LookPayload,
        )
    }
}
