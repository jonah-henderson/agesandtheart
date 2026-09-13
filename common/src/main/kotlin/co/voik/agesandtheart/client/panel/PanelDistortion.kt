package co.voik.agesandtheart.client.panel

import net.minecraft.util.ARGB
import net.minecraft.util.Mth

/**
 * The panel's picture as Riven's was: a coarse image, interlaced out of two that disagree.
 *
 * Two of the three cues cost nothing. The softness is [PanelTarget]'s own size — the Age is rendered
 * smaller than the page draws it, so the sampler's filtering is the blur — and the interlace is
 * [BandedFields] over the two fields the target keeps. Only the wash over the top is a stroke of its own.
 *
 * The instability index is the whole of what this says (design §7.3): *that* the Age is at odds with
 * itself, never what is wrong with it. **A coherent Age is a window**: nothing here happens at all, and the
 * panel is one field, whole.
 */
object PanelDistortion {

    /**
     * How far gone an Age's picture is, `0..1`, from its instability index.
     *
     * Straight, as design §7.3 says. It was square-rooted, on the reasoning that books carry the low
     * indices and would otherwise show nothing — but the whole range is worth having and the low end was
     * the part that was wrong: four is a book with a couple of contradictions in it, and it was tearing
     * like a broken one.
     */
    fun unsettledAt(index: Int): Float = (index.toFloat() / MOST_SHOWN).coerceIn(0.0f, 1.0f)

    /**
     * Whether an Age this far gone wears any of this at all.
     *
     * **Opted into rather than ramped from nought**, and read by everything the distortion reaches — the
     * coarse render target and the cut-up camera as much as the strokes. A coherent Age's panel is not a
     * faint version of a broken one's, it is a plain window: rendered fine, turning smoothly, laid once.
     */
    fun distorts(unsettled: Float): Boolean = unsettled > 0.0f

    /**
     * The Age, as strokes over the whole panel — [AgeInView.shot] being which shot is showing.
     *
     * The shot rather than a clock: everything here changes at a cut and holds still between, so a panel that
     * flicks through stills does not have noise crawling over it within one.
     *
     * How far the two fields disagree is not decided here — that is how far apart the shots stand, which is
     * [PanelShots]. What [AgeInView.unsettled] buys here is the dirt over the top of them.
     */
    fun strokes(age: AgeInView): List<PanelStroke> {
        val whole = PanelPicture.WHOLE
        // V backwards: a render target's origin is bottom-left where a page's is top-left.
        if (!distorts(age.unsettled)) return listOf(PanelStroke.Field(whole, PanelField.NEWEST, 0.0f, 1.0f, 1.0f, 0.0f))

        // No older field until a cut has happened, which is the first shot of every book: one is all there is.
        val older = if (age.hasOlderField) PanelField.OLDER else PanelField.NEWEST
        val unsettled = age.unsettled
        val frame = age.shot
        val bands = (PanelPicture.HEIGHT / BAND_HEIGHT).coerceAtLeast(1)
        val strays = Mth.lerp(unsettled, FEWEST_STRAYS, MOST_STRAYS)
        val ragged = Mth.lerp(unsettled, LEAST_RAGGED, MOST_RAGGED)

        val fields = BandedFields.cut(
            whole,
            0.0f, 1.0f, 1.0f, 0.0f,
            bands,
            COLUMNS,
            Mth.lerp(unsettled, LEAST_SPILL, MOST_SPILL),
            fieldOf = { band ->
                // Alternating, but not reliably: a clean comb reads as a pattern laid over the picture
                // where a few bands taken from the wrong field read as the picture itself being unsound.
                val itsTurn = band % 2 == 0
                val strayed = rolled(frame, band, STRAY_SALT) < strays
                if (itsTurn != strayed) PanelField.NEWEST else older
            },
            // Down or level, never up: boundaries that can only move one way stay in order, so a band is
            // never turned inside out and no piece of the picture goes missing.
            edgeAt = { boundary, column ->
                if (rolled(frame, boundary * COLUMNS + column, EDGE_SALT) < ragged) 1 else 0
            },
        )

        // Over the whole cut rather than band by band. White over everything is brightness up and contrast
        // down in one rectangle: the picture is a lit screen losing its grip rather than something with
        // shadows drawn on it.
        val wash = (Mth.lerp(unsettled, NO_WASH, MOST_WASH) * FULLY).toInt()
        if (wash <= 0) return fields
        return fields + PanelStroke.Fill(whole, ARGB.color(wash, FULLY, FULLY, FULLY))
    }

    /** `0..1` from [frame], [band] and [salt]. */
    private fun rolled(frame: Int, band: Int, salt: Long): Float {
        var bits = salt xor (frame.toLong() * FRAME_STRIDE) xor (band.toLong() * BAND_STRIDE)
        bits = (bits xor (bits ushr 33)) * FIRST_MIX
        bits = (bits xor (bits ushr 29)) * SECOND_MIX
        return (bits ushr 40).toFloat() / TWENTY_FOUR_BITS
    }

    /**
     * The index at which the panel is as bad as it gets.
     *
     * Past what any ordinary book reaches: seams and wounds together are bought by the twenties
     * (`art/manifestation/`), so an Age that shows the worst of this is one written to come apart.
     */
    private const val MOST_SHOWN = 24

    /** Two page-pixels, which is a band thin enough for two fields to comb rather than to stripe. */
    private const val BAND_HEIGHT = 2

    /**
     * How many pieces a band is laid in, which is how fine the raggedness along a boundary can be.
     *
     * Pieces multiply bands, and the boundaries are ragged by a page-pixel rather than by a shader because a
     * piece is only a quad.
     */
    private const val COLUMNS = 16

    /**
     * How often a band takes the field it was not this one's turn to take.
     *
     * Half is the top on purpose, which is a coin rather than a comb: the two views stop alternating and
     * start shuffling, and there is no longer a pattern to read the picture back out of.
     */
    private const val FEWEST_STRAYS = 0.02f
    private const val MOST_STRAYS = 0.50f

    /** How often a piece of a boundary sits a page-pixel lower than the rest of it. */
    private const val LEAST_RAGGED = 0.10f
    private const val MOST_RAGGED = 0.50f

    /** How far into its neighbours a band reaches, in bands, which is the blur over the whole picture. */
    private const val LEAST_SPILL = 0.10f
    private const val MOST_SPILL = 1.25f

    /** How much white is laid over the picture, which is brightness up and contrast down together. */
    private const val NO_WASH = 0.0f
    private const val MOST_WASH = 0.26f

    /** A whole colour channel, which a wash is a fraction of. */
    private const val FULLY = 255

    private const val FRAME_STRIDE = -0x61C8_8646_80B5_83EBL
    private const val BAND_STRIDE = 0x2545_F491_4F6C_DD1DL
    private const val FIRST_MIX = -0x00AE_5028_1AAA_7333L
    private const val SECOND_MIX = -0x3B31_4601_E57A_13ADL
    private const val STRAY_SALT = 0x1B87_3C05L
    private const val EDGE_SALT = 0x6E14_A9F3L

    private const val TWENTY_FOUR_BITS = 16_777_215.0f
}
