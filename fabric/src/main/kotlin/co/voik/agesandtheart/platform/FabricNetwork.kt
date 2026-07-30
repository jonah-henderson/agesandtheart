package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.Network
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Fabric's half of [Network]. `canSend` is asked first even though Fabric would tolerate not asking,
 * so both loaders behave the same.
 */
class FabricNetwork : Network {
    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
        if (!ServerPlayNetworking.canSend(player, payload.type())) return
        ServerPlayNetworking.send(player, payload)
    }
}
