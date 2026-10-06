package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.book.BookBeingRead
import java.util.UUID

/**
 * How often each player may make the server open a panel, and what becomes of a request that comes too soon.
 *
 * Opening generates up to [PanelRing.COUNT] chunks and a client decides when it happens, so it is paced — but a
 * request inside the pace is **held, not dropped**, and served the moment the pace allows. A dropped request was
 * answered by nothing, and a client cannot tell silence from a slow Age: it waited twenty seconds to ask again,
 * so a book shut and opened again inside half a second sat in mist all that while.
 *
 * A hand and a book lying open, on a lectern or fallen, keep separate clocks, because a hand outranks every book
 * lying open (design §7.8.2): one taken up a moment ago must never hold up a book opened in a hand. And a newer request replaces a held
 * one, since what a client wants is the book it asked for last.
 */
class PanelPacing(private val gap: Long) {

    private val openedInHand = mutableMapOf<UUID, Long>()
    private val openedLyingOpen = mutableMapOf<UUID, Long>()
    private val held = mutableMapOf<UUID, BookBeingRead>()

    /** Whether [viewer]'s request for [book] may be served [now]; one that may not is held until it may. */
    fun admit(viewer: UUID, book: BookBeingRead, now: Long): Boolean {
        if (!isClear(viewer, book, now)) {
            held[viewer] = book
            return false
        }
        stamp(viewer, book, now)
        held.remove(viewer)
        return true
    }

    /** The held requests the pace now lets through, each stamped and given up as it is handed back. */
    fun due(now: Long): Map<UUID, BookBeingRead> {
        val ready = held.filter { (viewer, book) -> isClear(viewer, book, now) }
        for ((viewer, book) in ready) {
            stamp(viewer, book, now)
            held.remove(viewer)
        }
        return ready
    }

    /** Drops whatever is held for [viewer], who has stopped wanting it. */
    fun cancel(viewer: UUID) {
        held.remove(viewer)
    }

    /** Forgets [viewer] altogether, for a player who has left. */
    fun forget(viewer: UUID) {
        cancel(viewer)
        openedInHand.remove(viewer)
        openedLyingOpen.remove(viewer)
    }

    private fun clockFor(book: BookBeingRead): MutableMap<UUID, Long> = when (book) {
        // A viewer's screen outranks every lectern as a book's does, so it keeps the hand's clock.
        is BookBeingRead.InHand, BookBeingRead.AtACrystalViewer -> openedInHand
        is BookBeingRead.OnALectern, is BookBeingRead.OnTheGround -> openedLyingOpen
    }

    private fun isClear(viewer: UUID, book: BookBeingRead, now: Long): Boolean {
        val previously = clockFor(book)[viewer] ?: return true
        return now - previously >= gap
    }

    private fun stamp(viewer: UUID, book: BookBeingRead, now: Long) {
        clockFor(book)[viewer] = now
    }
}
