package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Sends [payload] to the server this client is connected to, or does nothing when it is connected to none.
 *
 * Vanilla's own packet, which both loaders route for any payload type registered to be sent to the server.
 */
fun sendToServer(payload: CustomPacketPayload) {
    val connection = Minecraft.getInstance().connection
    if (connection == null) {
        Constants.LOG.debug("A payload was sent with no connection to carry it")
        return
    }
    connection.send(ServerboundCustomPayloadPacket(payload))
}
