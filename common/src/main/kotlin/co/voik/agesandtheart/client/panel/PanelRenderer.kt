package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.book.panel.PanelRing
import net.minecraft.client.DeltaTracker

/**
 * Draws a panel's preview level into the panel's target, once per frame a book is open (design §7.8.1): the shot
 * showing, cut to rather than panned, painted by a [PreviewPainter] onto the newest of [PanelTarget]'s fields.
 */
object PanelRenderer {

    private val painter = PreviewPainter()

    /** Which shot the newest field holds, so a cut is noticed once rather than every frame. */
    private var drawn = NO_SHOT

    /** No shot has been drawn since the panel last started over. */
    private const val NO_SHOT = -1

    /** The Age's distant haze as an opaque colour, from the frame last drawn ([PreviewPainter.haze]). */
    val haze: Int get() = painter.haze

    /** Forgets what has been drawn, so one book's panel never interlaces with the last one's Age. */
    fun startOver() {
        drawn = NO_SHOT
        painter.startOver()
        PanelTarget.startOver()
    }

    /**
     * Renders one frame of [preview], answering whether anything was drawn.
     *
     * False until a chunk has arrived, which is the ordinary case for the first moments of a book.
     */
    fun draw(preview: PreviewLevel, delta: DeltaTracker): Boolean {
        if (!preview.load.hasAnything) return false

        // Before the target is asked for: turning over is what decides which field the frame lands in.
        val shot = preview.shots.number
        if (shot != drawn) {
            // Never for a book's first shot, which has nothing behind it worth keeping.
            if (drawn != NO_SHOT) PanelTarget.turnOver()
            drawn = shot
        }

        val target = PanelTarget.get()
        preview.camera.placeAt(preview.shots.showing(), target.width, target.height)
        // The shown radius rather than the streamed one, so the fog ends before the outermost ring, which is sent only
        // to let the ring inside it mesh.
        painter.paint(preview.world, preview.camera, target, PanelRing.SHOWN_RADIUS_CHUNKS, delta, preview::advanceLight)
        return true
    }
}
