package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.panel.PanelChunkPayload
import co.voik.agesandtheart.book.panel.PanelChunksWanted
import co.voik.agesandtheart.book.panel.PanelCloseRequest
import co.voik.agesandtheart.book.panel.PanelLevelPayload
import co.voik.agesandtheart.book.panel.PanelOpenRequest
import co.voik.agesandtheart.client.sendToServer

/**
 * The one panel a client is looking at, if any: its lifetime and its half of the conversation.
 *
 * A panel exists while a book is open and not otherwise (design §7.8.1) — not in an inventory, not on the
 * ground, and of the books open on lecterns only the nearest one's (§7.8.2, [LecternPanels]). How one draws
 * is elsewhere; this decides only when one exists.
 */
object LinkingPanel {

    private var showing: PreviewLevel? = null
    private var asked = false

    /** Kept so an unanswered request can be repeated verbatim. */
    private var askedFor: BookBeingRead? = null

    /** Which book the panel is for, or null while it is nobody's. */
    val showingFor: BookBeingRead? get() = if (asked) askedFor else null
    private var askedAt = 0L
    private var asksSoFar = 0

    /** What is being shown, or null while nothing is or the first chunks are still coming. */
    val preview: PreviewLevel? get() = showing

    /**
     * Whether there is still something to wait for, which is what the screen says out loud.
     *
     * False once a chunk has arrived, and false again once the server has been asked as often as it is
     * going to be: a panel that has given up should stop suggesting otherwise.
     */
    val isWaiting: Boolean
        get() {
            val load = showing?.load ?: return asked && asksSoFar < MOST_ASKS
            return !load.hasAnything
        }

    /**
     * Asks the server to show [book] — a bound book in a hand, or one lying open on a lectern.
     *
     * Idempotent for the same book, so a screen may call it more than once: asking twice would have the
     * server drop and retake the ring. A different book takes the panel over.
     */
    fun ask(book: BookBeingRead) {
        if (asked && askedFor == book) return
        // Torn down here without a word to the server, which drops the old ring when this request arrives.
        if (asked) drop()
        asked = true
        askedFor = book
        askedAt = System.nanoTime()
        asksSoFar = 0
        sendToServer(PanelOpenRequest(book))
    }

    /**
     * The client tick, which is the beat a preview level does not have one of its own.
     *
     * Two things want a tick rather than a frame: the camera's environment probe, which interpolates
     * across one, and the wait for an answer the server may never send.
     */
    fun tick() {
        showing?.camera?.tickProbe()
        retryIfUnanswered()
        chaseMissingChunks()
    }

    /**
     * Asks again when the server said nothing at all.
     *
     * `open` refuses silently in three places — a second book inside the rate limit, a stack that is not a
     * book, an Age that will not open — and the client has no way to tell those apart from a slow one. It
     * asked once and would otherwise wait for ever on a black panel, since everything downstream is behind
     * the null preview this leaves in place.
     */
    private fun retryIfUnanswered() {
        if (!asked || showing != null) return
        if (asksSoFar >= MOST_ASKS) return
        val now = System.nanoTime()
        if (now - askedAt < BEFORE_ASKING_AGAIN_NANOS) return
        askedAt = now
        asksSoFar++
        Constants.LOG.info("Panel: no answer to the last request, asking again ({} of {})", asksSoFar, MOST_ASKS)
        sendToServer(PanelOpenRequest(askedFor ?: return))
    }

    /** Called when the level payload arrives, which is the server agreeing to show it. */
    fun accept(payload: PanelLevelPayload) {
        if (!asked) {
            // A panel we stopped waiting for. Tell the server so its ring does not outlive our interest.
            sendToServer(PanelCloseRequest)
            return
        }
        showing?.close()
        PanelRenderer.startOver()
        showing = PreviewLevel.open(payload, large = askedFor == BookBeingRead.AtACrystalViewer)
        if (showing == null) {
            Constants.LOG.warn(
                "Panel: the level payload for {} arrived and no preview could be built",
                payload.dimension.identifier(),
            )
            sendToServer(PanelCloseRequest)
        }
    }

    /** Called for each chunk of the ring. Chunks for a panel we have closed are dropped. */
    fun accept(payload: PanelChunkPayload) {
        showing?.accept(payload)
    }

    /**
     * Asks the server again for whatever the ring is still missing, if it is time to.
     *
     * Whether to ask is [RingLoad]'s to decide; this only carries the answer.
     */
    private fun chaseMissingChunks() {
        val load = showing?.load ?: return
        val missing = load.toChase() ?: return
        Constants.LOG.info(
            "Panel: {} of the ring's chunks have not arrived, asking again ({} of {})",
            missing.size, load.asksSoFar, RingLoad.MOST_CHASES,
        )
        sendToServer(PanelChunksWanted(missing))
    }

    /**
     * Gives the ring back and tears the level down.
     *
     * Safe to call more often than needed, and it should be: a preview level surviving its screen would
     * hold a server-side ring for as long as the client ran.
     */
    fun release() {
        if (drop()) sendToServer(PanelCloseRequest)
    }

    /** Dropped without telling the server, for a disconnect — where there is nobody left to tell. */
    fun forget() {
        drop()
    }

    /** Tears down whatever is held, answering whether there was anything. */
    private fun drop(): Boolean {
        val had = showing != null || asked
        showing?.close()
        showing = null
        asked = false
        askedFor = null
        asksSoFar = 0
        return had
    }

    /**
     * How long the server may say nothing at all before it is asked again.
     *
     * Longer than a cold open takes to answer: finding an Age's footing and scheduling its ring blocks the
     * server thread, and at five seconds this fired every time rather than only when a request was lost.
     */
    private const val BEFORE_ASKING_AGAIN_NANOS = 20_000_000_000L

    private const val MOST_ASKS = 3
}
