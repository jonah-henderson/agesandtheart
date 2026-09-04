package co.voik.agesandtheart.client.panel

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.gui.render.TextureSetup

/**
 * The off-screen surface a panel's world is drawn onto, and the flag that redirects the renderer to it.
 *
 * **Small on purpose.** A linking panel occupies a corner of a book, so the target is a few hundred pixels
 * square rather than the window's size — and because `LevelRenderer.renderLevel` sizes every internal
 * target in its frame graph from whatever `getMainRenderTarget()` hands back, that smallness is inherited
 * by the whole pass. This is why rendering a panel is not "rendering the world again".
 *
 * The flag is read by `MainRenderTargetMixin` and is live only across one render call on the render
 * thread. It is deliberately not the same mechanism as Ephemeris' `OffscreenLevelRender`: that one tells
 * *our own painters* where to draw, where this one lies to *vanilla* about where the window is. Two scopes
 * because they are two different claims, and conflating them would mean a mod that wanted one got both.
 */
object PanelTarget {

    /** Panels are square: a book's panel is a square hole in a page. */
    private const val SIZE = 384

    private var target: TextureTarget? = null
    private var redirecting = false

    /** The panel's target, made on first use and kept for the life of the client. */
    fun get(): RenderTarget = target ?: TextureTarget("Ages linking panel", SIZE, SIZE, true).also { target = it }

    /** What the Mixin asks. Null in every frame that is not drawing a panel, which is nearly all of them. */
    @JvmStatic
    fun beingDrawnOnto(): RenderTarget? = if (redirecting) target else null

    /**
     * Runs [block] with the world renderer pointed at the panel.
     *
     * Restores on failure, because a thrown exception that left the flag set would send every later frame
     * of the game into a small off-screen texture — a black window, and a bug nobody would connect to a
     * book.
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
     * The panel's colour texture, as the GUI wants it, or null before anything has been drawn into it.
     *
     * `GuiGraphicsExtractor.fill(pipeline, textureSetup, …)` is the public seam for a textured rectangle,
     * and `TextureSetup.singleTexture` takes exactly this pair — so an off-screen target reaches a screen
     * without any blit of ours.
     */
    fun textureSetup(): TextureSetup? {
        val view = target?.colorTextureView ?: return null
        return TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
    }

    /** Dropped when the client shuts down, or when a panel will not be wanted again for a long time. */
    fun forget() {
        target?.destroyBuffers()
        target = null
    }
}
