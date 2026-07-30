package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.Network
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Fabric's half of [Network].
 *
 * `canSend` is asked first even though Fabric would tolerate not asking — it sends and lets an unaware client
 * discard the payload, where NeoForge throws. Checking on both keeps the two halves behaving the same, which is
 * worth more than the one branch it costs.
 */
class FabricNetwork : Network {
    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
        if (!ServerPlayNetworking.canSend(player, payload.type())) return
        ServerPlayNetworking.send(player, payload)
    }
}
