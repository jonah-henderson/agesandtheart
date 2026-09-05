package co.voik.agesandtheart.client.panel

import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * One picture cut into horizontal bands, each band free to come from a different texture.
 *
 * An interlace with no pipeline of our own: `blit` already takes a rectangle and the texture coordinates to
 * fill it with, so a band is one blit of one field. Two textures alternating band by band is what a frame
 * of interlaced video is, and it costs nothing but the draws.
 *
 * Two things keep the seams from reading as ruled lines. [spill] gives each band a taller slice of its
 * source than its own rows, so neighbours overlap in what they show and the sampler averages the difference
 * across the boundary rather than cutting at it. And a band is drawn in [columns] pieces rather than one,
 * each free to sit a pixel higher or lower than its neighbour — [edgeAt] says which, and the band above and
 * the band below read the same answer for the boundary they share, so a ragged edge never opens a gap.
 *
 * The bands are submitted in one run with nothing between them, and a caller wanting to draw over them must
 * wait until it is done: `GuiRenderState` layers an element above anything it overlaps, so bands, which
 * never overlap each other, are one layer, while anything interleaved starts a new one per piece.
 */
object BandedBlit {

    /**
     * The rectangle and the texture coordinates are `blit`'s own, corners first, so a source whose origin
     * is at the bottom is expressed by giving `v` backwards exactly as it would be for a whole one.
     *
     * [edgeAt] is asked for a *boundary* — nought to [bands] — and answers how many pixels down to move it
     * in that column. It is never asked about the outer two, which stay straight: a ragged one would show
     * what is behind the picture.
     */
    fun draw(
        graphics: GuiGraphicsExtractor,
        sampler: GpuSampler,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        u0: Float,
        u1: Float,
        v0: Float,
        v1: Float,
        bands: Int,
        columns: Int,
        spill: Float,
        fieldOf: (band: Int) -> GpuTextureView,
        edgeAt: (boundary: Int, column: Int) -> Int,
    ) {
        val width = x1 - x0
        val height = y1 - y0
        val overlap = (v1 - v0) / bands * spill
        for (band in 0..<bands) {
            val field = fieldOf(band)
            val vTop = partWay(v0, v1, band, bands) - overlap
            val vBottom = partWay(v0, v1, band + 1, bands) + overlap
            for (column in 0..<columns) {
                val left = x0 + width * column / columns
                val right = x0 + width * (column + 1) / columns
                val top = boundary(y0, height, bands, band, column, edgeAt)
                val bottom = boundary(y0, height, bands, band + 1, column, edgeAt)
                // Rounding leaves an empty piece where there are more of them than pixels to give them.
                if (right <= left || bottom <= top) continue
                graphics.blit(
                    field,
                    sampler,
                    left,
                    top,
                    right,
                    bottom,
                    partWay(u0, u1, column, columns),
                    partWay(u0, u1, column + 1, columns),
                    vTop,
                    vBottom,
                )
            }
        }
    }

    private fun boundary(
        y0: Int,
        height: Int,
        bands: Int,
        which: Int,
        column: Int,
        edgeAt: (Int, Int) -> Int,
    ): Int {
        val at = y0 + height * which / bands
        if (which == 0 || which == bands) return at
        return at + edgeAt(which, column)
    }

    private fun partWay(from: Float, to: Float, step: Int, steps: Int): Float =
        from + (to - from) * step / steps
}
