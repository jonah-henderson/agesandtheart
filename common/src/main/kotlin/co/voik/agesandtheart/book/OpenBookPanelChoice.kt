package co.voik.agesandtheart.book

/** An open book of ours near a viewer, on a lectern or fallen, and how far off it is. */
data class NearbyOpenBook(val book: BookBeingRead, val distance: Double)

/**
 * Which open book's panel a client shows (design §7.8.2): the nearest within reach — but the one already
 * showing keeps it until another is clearly nearer, or it falls out of reach.
 *
 * A client draws one panel, and every change of book is a fresh ring and a fade from mist. The two margins are
 * what stop a player standing between two books, or at the edge of one, from paying that over and over.
 *
 * **The switching margin is a share of the distance, not a number of blocks.** How much nearer one book can be
 * than another is bounded by how far apart the two lie, so a margin in blocks would stop books closer together
 * than it from ever handing the panel over.
 */
object OpenBookPanelChoice {

    /** Another book takes the panel once it is nearer than this share of the showing one's distance. */
    const val TAKES_OVER_WITHIN_SHARE_OF_DISTANCE = 0.6

    /** How far off the showing book may get before it is let go: a block past where one is first taken up. */
    const val LET_GO_BEYOND_BLOCKS = LecternBooks.REACH_BLOCKS + 1.0

    fun choose(showing: BookBeingRead?, nearby: List<NearbyOpenBook>): BookBeingRead? {
        val nearest = nearby.filter { it.distance <= LecternBooks.REACH_BLOCKS }.minByOrNull { it.distance }
        val stillShowing = nearby.firstOrNull { it.book == showing && it.distance <= LET_GO_BEYOND_BLOCKS }
        return when {
            stillShowing == null -> nearest?.book
            nearest == null -> stillShowing.book
            nearest.distance < stillShowing.distance * TAKES_OVER_WITHIN_SHARE_OF_DISTANCE -> nearest.book
            else -> stillShowing.book
        }
    }
}
