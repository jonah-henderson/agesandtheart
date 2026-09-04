package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelCloseRequest
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.book.panel.PanelOpenRequest
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand

/**
 * The one panel a client is looking at, if any.
 *
 * **One at a time, and it belongs to a screen.** A panel exists while a book is open and not otherwise
 * (§7.8.1) — not in an inventory, not on the ground, not on a lectern across the room — which is the ruling
 * that turns this from a dimension held per book carried into one held briefly while a screen is up.
 *
 * The client asks and the server answers: [ask] sends a request naming a dimension, the payloads arrive,
 * and [release] gives the ring back. Everything here is one field and its lifetime.
 */
object LinkingPanel {

    private var showing: PreviewLevel? = null
    private var asked = false

    /** What is being shown, or null while nothing is or the first chunks are still coming. */
    val preview: PreviewLevel? get() = showing

    /**
     * Asks the server to show whatever bound book is in [hand].
     *
     * **The hand rather than a world, because the Age may not exist yet**: a bound book's Age is decided
     * but is only made when something asks for it, and the server resolves the held stack through
     * `BookAge` exactly as linking does.
     *
     * Idempotent, so a screen may call it more than once without meaning to: asking twice would have the
     * server drop and retake the ring, which is a stutter and a wasted round trip.
     */
    fun ask(hand: InteractionHand) {
        if (asked) return
        asked = true
        send(PanelOpenRequest(hand))
    }

    /** Called when the level payload arrives, which is the server agreeing to show it. */
    fun accept(payload: PanelLevelPayload) {
        if (!asked) {
            // A panel we stopped waiting for. Tell the server so its ring does not outlive our interest.
            send(PanelCloseRequest)
            return
        }
        showing?.close()
        showing = PreviewLevel.open(payload)
        if (showing == null) send(PanelCloseRequest)
    }

    /** Called for each chunk of the ring. Chunks for a panel we have closed are dropped. */
    fun accept(payload: PanelChunkPayload) {
        showing?.accept(payload)
    }

    /**
     * Gives the ring back and tears the level down.
     *
     * **Safe to call more often than needed**, and it should be: a screen closing, a disconnect, a second
     * book opened. The failure this guards against is a preview level surviving its screen, which would
     * hold a server-side ring for as long as the client ran.
     */
    fun release() {
        val had = showing != null || asked
        showing?.close()
        showing = null
        asked = false
        if (had) send(PanelCloseRequest)
    }

    /** Dropped without telling the server, for a disconnect — where there is nobody left to tell. */
    fun forget() {
        showing?.close()
        showing = null
        asked = false
    }

    private fun send(payload: net.minecraft.network.protocol.common.custom.CustomPacketPayload) {
        val connection = Minecraft.getInstance().connection
        if (connection == null) {
            Constants.LOG.debug("A panel wanted to say something with no connection to say it on")
            return
        }
        connection.send(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(payload))
    }
}
