package co.voik.agesandtheart.client.panel

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView

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
 * actually asks for. They come to about three megabytes if every rung a book uses is, nearly all of it the
 * book's window, and another ten once a crystal viewer has been looked through.
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
     * The first two are **windows**, what a coherent Age gets: the crystal viewer's, which draws the panel
     * several times the size a book does, and the book's, finer than the page can show at any GUI scale. The
     * rest are what an unstable one falls down, in a viewer as in a book: Riven's panel was a low-resolution
     * movie, and the further gone an Age is the less of one the book has left.
     */
    private val COARSENESSES = listOf(
        1024 to 640,
        512 to 320,
        208 to 130,
        144 to 90,
        112 to 70,
        80 to 50,
    )

    /**
     * The sharpest rung, which is what a hand-built `LevelRenderer` is sized to: it sizes its own targets
     * from the size it is given, and every coarser rung fits inside this one.
     */
    val SHARPEST_WIDTH: Int = COARSENESSES.first().first
    val SHARPEST_HEIGHT: Int = COARSENESSES.first().second

    private const val A_VIEWERS_WINDOW = 0
    private const val A_BOOKS_WINDOW = 1

    /** The first rung an unsettled Age may take: past both windows. */
    private const val FIRST_BLURRED = 2

    private val fieldsAt: List<Array<TextureTarget?>> = COARSENESSES.map { arrayOfNulls(FIELDS) }

    private var coarseness = A_BOOKS_WINDOW
    private var newest = 0
    private var everTurned = false
    private var redirecting = false

    /**
     * How coarsely the book about to be shown is rendered. Decided once as it opens, never inside a frame.
     *
     * A coherent Age takes a window and no other rung — the viewer's where the panel is [large] — because the
     * coarseness is part of the distortion rather than part of the panel ([PanelDistortion.distorts]).
     */
    fun showing(unsettled: Float, large: Boolean) {
        coarseness = when {
            !PanelDistortion.distorts(unsettled) -> if (large) A_VIEWERS_WINDOW else A_BOOKS_WINDOW
            else -> {
                val rungs = COARSENESSES.size - FIRST_BLURRED
                (FIRST_BLURRED + (unsettled * rungs).toInt()).coerceAtMost(COARSENESSES.size - 1)
            }
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
            ?: TextureTarget(
                "Ages linking panel ${width}x$height field $which",
                width,
                height,
                GpuFormat.RGBA8_UNORM,
                GpuFormat.D32_FLOAT,
            )
                .also { fields[which] = it }
    }

    private const val FIELDS = 2
}
