package co.voik.agesandtheart.client.panel

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView

/**
 * The off-screen surfaces a panel's world is drawn onto, and the flag that redirects the renderer.
 *
 * Two fields, because a distorted panel is interlaced out of the shot it is showing and the one before it
 * ([PanelDistortion]). Only one is ever drawn onto: [turnOver] moves on at a cut, which leaves the last
 * frame of the outgoing shot standing in the other as the older field.
 *
 * And a pair of fields at each of several *sizes*, because how coarse the render is is the panel's blur:
 * the page draws it across 104 pixels whatever it was rendered at, so anything smaller than that is being
 * filtered upwards and the softness is had for nothing. [COARSENESSES] is that ladder, sharpest first.
 *
 * Every pair is kept rather than one being resized, which is what makes [showing] a note to self that any
 * thread may leave: the fields themselves are made lazily, inside the render, and only at the sizes a world
 * actually asks for. They come to about three megabytes if every rung is used, nearly all of it the first.
 *
 * `renderLevel` sizes every internal target in its frame graph from whatever `getMainRenderTarget()`
 * returns, so a panel-sized target makes the whole pass a fraction of a full-screen world render.
 *
 * The flag is read by `MainRenderTargetMixin`. It is not the same claim as Ephemeris'
 * `OffscreenLevelRender`, which tells our own painters which level a frame is for.
 */
object PanelTarget {

    /**
     * The sizes a panel may be rendered at, sharpest first, all of them eight to five so the blit into the
     * page neither stretches nor crops whichever is showing.
     *
     * The first is a **window**, finer than the page can show at any GUI scale, and is what a coherent Age
     * gets. The rest are what an unstable one falls down: Riven's panel was a low-resolution movie, and the
     * further gone an Age is the less of one the book has left.
     */
    private val COARSENESSES = listOf(
        512 to 320,
        208 to 130,
        144 to 90,
        112 to 70,
        80 to 50,
    )

    private const val CLEAREST = 0

    private val fieldsAt: List<Array<TextureTarget?>> = COARSENESSES.map { arrayOfNulls(FIELDS) }

    private var coarseness = CLEAREST
    private var newest = 0
    private var everTurned = false
    private var redirecting = false

    /**
     * How coarsely the book about to be shown is rendered. Decided once as it opens, never inside a frame.
     *
     * A coherent Age takes the clear rung and no other, because the coarseness is part of the distortion
     * rather than part of the panel ([PanelDistortion.distorts]).
     */
    fun showing(unsettled: Float) {
        coarseness = if (!PanelDistortion.distorts(unsettled)) {
            CLEAREST
        } else {
            // Nought is the window, so the rungs an unsettled Age may take start at one.
            val rungs = COARSENESSES.size - 1
            (1 + (unsettled * rungs).toInt()).coerceAtMost(rungs)
        }
    }

    /** What the world is being drawn onto now. Made on first use and kept for the life of the client. */
    fun get(): RenderTarget = field(newest)

    /** What the Mixin asks. Null in every frame that is not drawing a panel. */
    @JvmStatic
    fun beingDrawnOnto(): RenderTarget? = if (redirecting) field(newest) else null

    /**
     * Moves on to the other field, so what was just drawn is kept as the older half of the interlace.
     *
     * Called at a cut rather than every frame: a shot is drawn over and over while it is held, and the
     * older field has to be a *different* shot for the comb to show anything.
     */
    fun turnOver() {
        newest = (newest + 1) % FIELDS
        everTurned = true
    }

    /** Forgets the older field, so a book cannot open interlaced with the last book's Age. */
    fun startOver() {
        everTurned = false
    }

    /** The newest field's colour texture, or null before anything has been drawn into it. */
    fun newestView(): GpuTextureView? = fieldsAt[coarseness][newest]?.colorTextureView

    /** The field before it, or null while the first shot of a book is all there is. */
    fun olderView(): GpuTextureView? =
        if (everTurned) fieldsAt[coarseness][(newest + 1) % FIELDS]?.colorTextureView else null

    /**
     * Runs [block] with the world renderer pointed at the panel.
     *
     * Restores on failure: a flag left set would send every later frame of the game into a small off-screen
     * texture.
     */
    fun <T> redirecting(block: () -> T): T {
        get()
        val outer = redirecting
        redirecting = true
        try {
            return block()
        } finally {
            redirecting = outer
        }
    }

    /** Clamped and linear: the panel is drawn at a size that has nothing to do with its own. */
    fun sampler(): GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

    private fun field(which: Int): RenderTarget {
        val fields = fieldsAt[coarseness]
        val (width, height) = COARSENESSES[coarseness]
        return fields[which]
            ?: TextureTarget("Ages linking panel ${width}x$height field $which", width, height, true)
                .also { fields[which] = it }
    }

    private const val FIELDS = 2
}
