package co.voik.agesandtheart.platform.services

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Sending a payload to one player, which each loader does its own way.
 *
 * A **second service** rather than a method on [Platform], following the [AgeBackend] precedent: `Platform`
 * reports *information* about the loader, and sending a packet is a capability. Deliberately generic in
 * [CustomPacketPayload] rather than named after skies, because the books and symbols will want the same door
 * and a `SkyChannel` would have to be widened or duplicated the first time they do.
 *
 * The payload *types* are vanilla (`CustomPacketPayload`, `StreamCodec`), so they live in `common`; only this
 * send and the registration each loader does at its own init are divergent. Receiving is client-side loader code
 * and needs no service at all.
 *
 * **The two loaders differ in one way worth knowing at the call site**: NeoForge *throws* when a channel was
 * never negotiated (`NetworkRegistry.checkPacket`), where Fabric sends and lets the client discard it. So an
 * implementation must check before sending, and a caller may assume this is safe against a vanilla client.
 */
interface Network {
    /**
     * Sends [payload] to [player], or does nothing if that player cannot receive it.
     *
     * Silent rather than throwing, because every caller's honest response to "this client has no such channel"
     * is to carry on: a player without the mod simply sees vanilla's sky.
     */
    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload)
}
