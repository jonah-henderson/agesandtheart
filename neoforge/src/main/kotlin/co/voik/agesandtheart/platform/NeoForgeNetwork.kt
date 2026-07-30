package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.Network
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.NetworkRegistry

/**
 * NeoForge's half of [Network]. **The `hasChannel` guard is not optional**: `NetworkRegistry.checkPacket`
 * throws when a channel was never negotiated, so sending blind to a vanilla client would take down the
 * send site rather than being ignored.
 */
class NeoForgeNetwork : Network {
    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
        if (!NetworkRegistry.hasChannel(player.connection, payload.type().id)) return
        PacketDistributor.sendToPlayer(player, payload)
    }
}
