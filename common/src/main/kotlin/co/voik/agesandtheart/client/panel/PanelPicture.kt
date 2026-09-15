package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.client.ui.ParchmentSurface

/** A rectangle of a panel in page-pixels, measured from the picture's top-left corner. */
data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Which of [PanelTarget]'s two fields a stroke takes its picture from. */
enum class PanelField { NEWEST, OLDER }

/** One thing laid onto a panel. A panel's picture is a list of these, back to front. */
sealed interface PanelStroke {

    val rect: PanelRect

    /** A flat colour. */
    data class Fill(override val rect: PanelRect, val colour: Int) : PanelStroke

    /**
     * A piece of one field, [u0]–[u1] and [v0]–[v1] of it stretched over [rect], `v` given top first — so a
     * whole field reads one to nought, a render target's origin being at its bottom.
     */
    data class Field(
        override val rect: PanelRect,
        val field: PanelField,
        val u0: Float,
        val u1: Float,
        val v0: Float,
        val v1: Float,
    ) : PanelStroke

    /** The mist, at [strength] from nothing to opaque. */
    data class Mist(override val rect: PanelRect, val strength: Float) : PanelStroke
}

/** What a panel's picture needs to know of the Age behind it, this frame. */
data class AgeInView(val haze: Int, val shot: Int, val unsettled: Float, val hasOlderField: Boolean) {

    companion object {
        /** [preview] as it stands just after [PanelRenderer] has drawn it. */
        fun of(preview: PreviewLevel) = AgeInView(
            haze = PanelRenderer.haze,
            shot = preview.shots.number,
            unsettled = preview.unsettled,
            hasOlderField = PanelTarget.olderView() != null,
        )
    }
}

/**
 * What a panel looks like, wherever it is — as strokes rather than as drawing, so that a book screen and a
 * lectern are shown one picture and cannot come to disagree ([PanelComposite] lays it down).
 */
object PanelPicture {

    /** Wider than it is tall, as the games depict a panel — about eight to five. In page-pixels. */
    const val WIDTH = 104
    const val HEIGHT = 65

    /** The frame round the picture, which is part of it: a lectern's panel wears one as a book's does. */
    const val FRAME_WIDTH = 1

    val WHOLE = PanelRect(0, 0, WIDTH, HEIGHT)

    private val FRAMED = PanelRect(-FRAME_WIDTH, -FRAME_WIDTH, WIDTH + FRAME_WIDTH, HEIGHT + FRAME_WIDTH)

    /** The page's edge ink, which the frame is drawn in. */
    private val FRAME = ParchmentSurface.EDGE

    /** Black until it can show the Age. */
    private val BLACK = 0xFF07070C.toInt()

    /**
     * Everything a panel shows, back to front: the frame, the black, the Age where there is a picture of it
     * this frame ([age]), and the mist at [mist] — the strength the ring is still missing.
     */
    fun strokes(age: AgeInView?, mist: Float): List<PanelStroke> = buildList {
        add(PanelStroke.Fill(FRAMED, FRAME))
        // Under everything, so a mist that thins never shows the page through it.
        add(PanelStroke.Fill(WHOLE, BLACK))
        if (age != null) {
            // A level render leaves its background transparent rather than coloured, so everything the Age
            // does not cover — the band under the horizon and past the ring — needs the haze behind it.
            add(PanelStroke.Fill(WHOLE, age.haze))
            addAll(PanelDistortion.strokes(age))
        }
        // Over the Age rather than behind it: the fade *is* the load (design §7.8.1), so the Age comes through
        // the mist as it arrives rather than replacing it.
        val strength = mist.coerceIn(0.0f, 1.0f)
        if (strength > 0.0f) add(PanelStroke.Mist(WHOLE, strength))
    }
}
