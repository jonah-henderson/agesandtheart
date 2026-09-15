package co.voik.agesandtheart.platform.services

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Sending a payload to one player, which each loader does its own way.
 *
 * A **service of its own** rather than a method on [Platform]: `Platform` reports information about the
 * loader where sending a packet is a capability. Generic in [CustomPacketPayload]
 * rather than named after skies, because books and symbols will want the same door.
 *
 * **NeoForge throws when a channel was never negotiated** (`NetworkRegistry.checkPacket`) where Fabric
 * sends and lets the client discard it — so an implementation must check first, and a caller may assume
 * this is safe against a vanilla client.
 */
interface Network {
    /**
     * Sends [payload] to [player], or does nothing if that player cannot receive it. Silent rather than
     * throwing: a player without the mod simply sees vanilla's sky.
     */
    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload)
}
