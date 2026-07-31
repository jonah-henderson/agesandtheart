package co.voik.agesandtheart.client

import co.voik.agesandtheart.desk.DeskCommandPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * The screen's way back to the server.
 *
 * An indirection because sending from the client is loader-specific, and the screen should not know
 * which loader it is on. Each loader's client entrypoint fills [sender] in.
 */
object ClientDeskNetwork {
    var sender: ((CustomPacketPayload) -> Unit)? = null

    fun send(payload: DeskCommandPayload) {
        sender?.invoke(payload)
    }
}
