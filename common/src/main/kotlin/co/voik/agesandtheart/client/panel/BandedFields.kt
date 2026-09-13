package co.voik.agesandtheart.client.panel

/**
 * One picture cut into horizontal bands, each band free to come from a different field.
 *
 * Two fields alternating band by band is what a frame of interlaced video is. Two things keep the seams from
 * reading as ruled lines. [spill] gives each band a taller slice of its field than its own rows, so neighbours
 * overlap in what they show and the sampler averages the difference across the boundary rather than cutting at
 * it. And a band is laid in [columns] pieces rather than one, each free to sit a page-pixel lower than its
 * neighbour — [edgeAt] says which, and the band above and the band below read the same answer for the boundary
 * they share, so a ragged edge never opens a gap.
 *
 * The pieces come back grouped by field rather than in band order. Bands never overlap, so the order they are
 * laid in changes nothing, and grouped they are two draws rather than one a band.
 */
object BandedFields {

    /**
     * [rect] cut into [bands] bands of [columns] pieces. The texture coordinates are a whole field's, top first,
     * so a source whose origin is at the bottom is given `v` backwards exactly as it would be whole.
     *
     * [edgeAt] is asked for a *boundary* — nought to [bands] — and answers how many page-pixels down to move it
     * in that column. It is never asked about the outer two, which stay straight: a ragged one would show what
     * is behind the picture.
     */
    fun cut(
        rect: PanelRect,
        u0: Float,
        u1: Float,
        v0: Float,
        v1: Float,
        bands: Int,
        columns: Int,
        spill: Float,
        fieldOf: (band: Int) -> PanelField,
        edgeAt: (boundary: Int, column: Int) -> Int,
    ): List<PanelStroke.Field> {
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        val overlap = (v1 - v0) / bands * spill
        val pieces = mutableListOf<PanelStroke.Field>()
        for (band in 0..<bands) {
            val field = fieldOf(band)
            val vTop = partWay(v0, v1, band, bands) - overlap
            val vBottom = partWay(v0, v1, band + 1, bands) + overlap
            for (column in 0..<columns) {
                val left = rect.left + width * column / columns
                val right = rect.left + width * (column + 1) / columns
                val top = boundary(rect.top, height, bands, band, column, edgeAt)
                val bottom = boundary(rect.top, height, bands, band + 1, column, edgeAt)
                // Rounding leaves an empty piece where there are more of them than page-pixels to give them.
                if (right <= left || bottom <= top) continue
                pieces += PanelStroke.Field(
                    PanelRect(left, top, right, bottom),
                    field,
                    partWay(u0, u1, column, columns),
                    partWay(u0, u1, column + 1, columns),
                    vTop,
                    vBottom,
                )
            }
        }
        return pieces.sortedBy { it.field }
    }

    private fun boundary(
        top: Int,
        height: Int,
        bands: Int,
        which: Int,
        column: Int,
        edgeAt: (Int, Int) -> Int,
    ): Int {
        val at = top + height * which / bands
        if (which == 0 || which == bands) return at
        return at + edgeAt(which, column)
    }

    private fun partWay(from: Float, to: Float, step: Int, steps: Int): Float =
        from + (to - from) * step / steps
}
