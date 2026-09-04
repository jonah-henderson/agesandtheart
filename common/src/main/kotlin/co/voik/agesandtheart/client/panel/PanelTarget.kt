package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.Constants
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTextureView

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
    private var checkedTheRedirect = false

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
        val mine = get()
        val outer = redirecting
        redirecting = true
        try {
            // **Once, and it is the question no log has answered yet.** Everything downstream assumes the
            // Mixin on `getMainRenderTarget` is applying; if it is not, the world render goes to the window
            // behind the book and the panel is black with nothing wrong anywhere else.
            if (!checkedTheRedirect) {
                checkedTheRedirect = true
                val seen = Minecraft.getInstance().mainRenderTarget
                Constants.LOG.info(
                    "Panel: the renderer is being handed {}",
                    if (seen === mine) "the panel's target, so the Mixin is applying"
                    else "THE WINDOW — the Mixin on getMainRenderTarget is not applying",
                )
            }
            return block()
        } finally {
            redirecting = outer
        }
    }

    /**
     * The panel's colour texture, or null before anything has been drawn into it.
     *
     * **Handed to `GuiGraphicsExtractor.blit` and not to `fill`.** `fill(pipeline, textureSetup, …)` builds
     * a `ColoredRectangleRenderState`, which writes position and colour and **no texture coordinates** — so
     * pairing it with `GUI_TEXTURED`, whose vertex format demands `UV0`, throws *Missing elements in
     * vertex: UV0* the moment the GUI mesh is built. `blit` is the seam that emits them.
     */
    fun colourView(): GpuTextureView? = target?.colorTextureView

    /** Clamped and linear: the panel is drawn at a size that has nothing to do with its own. */
    fun sampler(): GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

}
