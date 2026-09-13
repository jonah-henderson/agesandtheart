package co.voik.agesandtheart.book

import net.minecraft.core.BlockPos

/** An open book of ours near a viewer, and how far off it is. */
data class NearbyLectern(val pos: BlockPos, val distance: Double)

/**
 * Which lectern's panel a client shows (design §7.8.2): the nearest open book within reach — but the one
 * already showing keeps it until another is clearly nearer, or it falls out of reach.
 *
 * A client draws one panel, and every change of lectern is a fresh ring and a fade from mist. The two
 * margins are what stop a player standing between two lecterns, or at the edge of one, from paying that
 * over and over.
 */
object LecternPanelChoice {

    /** How much nearer another lectern has to be before it takes the panel from the one showing. */
    const val SWITCH_MARGIN_BLOCKS = 2.0

    /** How far off the showing lectern may get before it is let go: a block past where one is first taken up. */
    const val LET_GO_BEYOND_BLOCKS = LecternBooks.REACH_BLOCKS + 1.0

    fun choose(showing: BlockPos?, nearby: List<NearbyLectern>): BlockPos? {
        val nearest = nearby.filter { it.distance <= LecternBooks.REACH_BLOCKS }.minByOrNull { it.distance }
        val stillShowing = nearby.firstOrNull { it.pos == showing && it.distance <= LET_GO_BEYOND_BLOCKS }
        return when {
            stillShowing == null -> nearest?.pos
            nearest == null -> stillShowing.pos
            nearest.distance + SWITCH_MARGIN_BLOCKS < stillShowing.distance -> nearest.pos
            else -> stillShowing.pos
        }
    }
}
