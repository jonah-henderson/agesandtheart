package co.voik.agesandtheart.client.panel

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView

/**
 * The off-screen surface a panel's world is drawn onto, and the flag that redirects the renderer to it.
 *
 * `renderLevel` sizes every internal target in its frame graph from whatever `getMainRenderTarget()`
 * returns, so a panel-sized target makes the whole pass a fraction of a full-screen world render.
 *
 * The flag is read by `MainRenderTargetMixin`. It is not the same claim as Ephemeris'
 * `OffscreenLevelRender`, which tells our own painters which level a frame is for.
 */
object PanelTarget {

    /** A book's panel is a square hole in a page. */
    private const val SIZE = 384

    private var target: TextureTarget? = null
    private var redirecting = false

    /** Made on first use and kept for the life of the client. */
    fun get(): RenderTarget = target ?: TextureTarget("Ages linking panel", SIZE, SIZE, true).also { target = it }

    /** What the Mixin asks. Null in every frame that is not drawing a panel. */
    @JvmStatic
    fun beingDrawnOnto(): RenderTarget? = if (redirecting) target else null

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

    /**
     * The panel's colour texture, or null before anything has been drawn into it.
     *
     * For `blit` and not `fill`: a fill writes no texture coordinates, and `GUI_TEXTURED`'s vertex format
     * demands them.
     */
    fun colourView(): GpuTextureView? = target?.colorTextureView

    /** Clamped and linear: the panel is drawn at a size that has nothing to do with its own. */
    fun sampler(): GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
}
