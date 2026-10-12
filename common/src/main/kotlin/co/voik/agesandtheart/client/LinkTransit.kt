package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.LinkCancelRequest
import co.voik.agesandtheart.client.panel.LinkingPanel
import co.voik.agesandtheart.client.panel.PanelComposite
import co.voik.agesandtheart.client.panel.PanelPicture
import co.voik.agesandtheart.client.panel.PanelTarget
import co.voik.agesandtheart.client.ui.Rect
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.LoadingDotsText
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.util.Util

/** Starts a link's transit, kept apart so common code never names the screen directly. */
object LinkTransitOpener {
    /** Shows a link from [book] under way, its picture growing out of [panel] — the panel on screen, where there is one. */
    fun begin(book: BookBeingRead, panel: Rect? = null) {
        LinkTransit.begin(book, panel)
        Minecraft.getInstance().setScreenAndShow(LinkTransitScreen())
    }
}

/**
 * A link between the click and the arrival: the panel's picture grows out of the panel until it fills the view,
 * dimmed, and stays there until the player is in the other world.
 *
 * The picture is the panel's own composite — the Age where it has loaded, the mist where it has not — so the
 * transit begins from exactly what was clicked. It outlives its screen: vanilla's loading screen takes over when
 * the player is moved, and draws this behind its own words (`LevelLoadingScreenMixin`), held at its last frame,
 * since a second level render while vanilla changes level is the one thing not to risk.
 *
 * Shown from the click, before the server has said anything, so there is no pause to read as a refusal. A link
 * whose arrival is ready lands inside the first fade, and the transit is barely seen.
 */
object LinkTransit {

    private var book: BookBeingRead? = null
    private var from: Rect? = null
    private var startedAt = 0L

    /** Whether a link is between its click and its arrival. */
    val isUnderway: Boolean get() = book != null

    fun begin(book: BookBeingRead, panel: Rect?) {
        this.book = book
        from = panel
        startedAt = Util.getMillis()
        // The panel being shown already, for a hand's book, and asked for where a lectern's was not.
        LinkingPanel.ask(book)
    }

    /** The server's word that the link is not going: the transit closes and the refusal shows. */
    fun ended() {
        if (!isUnderway) return
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.screen() is LinkTransitScreen) minecraft.gui.setScreen(null)
        finish()
    }

    /** Escape on the transit: the server stops waiting, and the player stays. */
    fun cancel() {
        if (!isUnderway) return
        sendToServer(LinkCancelRequest)
        finish()
    }

    /** Ends the transit once neither it nor vanilla's loading screen is up, which is the player having arrived. */
    fun tick(minecraft: Minecraft) {
        if (!isUnderway) return
        val screen = minecraft.gui.screen()
        if (screen is LinkTransitScreen || screen is LevelLoadingScreen) {
            LinkingPanel.tick()
            return
        }
        finish()
    }

    /** Leaving a server mid-link. */
    fun forget() {
        book = null
        from = null
    }

    private fun finish() {
        forget()
        LinkingPanel.release()
    }

    /** The transit's own picture, live: laid down this frame from the panel's preview. */
    fun drawLive(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        val picture = PanelComposite.composeLive(LinkingPanel.preview, Minecraft.getInstance().deltaTracker)
        draw(graphics, picture, width, height, grown = grownBy(Util.getMillis() - startedAt))
    }

    /** Behind vanilla's loading screen: the picture as last laid down, filling the view. */
    fun drawHeld(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        val picture = PanelComposite.liveView() ?: PanelComposite.composeMisted()
        draw(graphics, picture, width, height, grown = WHOLLY)
    }

    /** "Linking", and vanilla's dots, where vanilla's loading screen puts its own words. */
    fun drawWords(graphics: GuiGraphicsExtractor, screen: Screen) {
        val grown = grownBy(Util.getMillis() - startedAt)
        if (grown < WHOLLY) return
        val font = Minecraft.getInstance().font
        val middle = screen.width / 2
        val top = screen.height / 2 - WORDS_ABOVE_THE_MIDDLE
        graphics.centeredText(font, UNDERWAY, middle, top, WHITE)
        graphics.centeredText(font, LoadingDotsText.get(Util.getMillis()), middle, top + LINE, WHITE)
    }

    /**
     * [picture] in a rectangle [grown] of the way from the panel to the whole view, cropped to its own frame and
     * keeping its shape, so it covers the view as it fills it rather than stretching to it.
     */
    private fun draw(graphics: GuiGraphicsExtractor, picture: GpuTextureView, width: Int, height: Int, grown: Float) {
        val filling = filling(width, height)
        val start = from ?: centred(width, height)
        val eased = Mth.smoothstep(grown)
        val left = Mth.lerp(eased, start.x.toFloat(), filling.x.toFloat()).toInt()
        val top = Mth.lerp(eased, start.y.toFloat(), filling.y.toFloat()).toInt()
        val right = Mth.lerp(eased, start.right.toFloat(), filling.right.toFloat()).toInt()
        val bottom = Mth.lerp(eased, start.bottom.toFloat(), filling.bottom.toFloat()).toInt()
        // Inside the frame: the composite carries the panel's border, which a view full of somewhere else does not.
        val frameAcross = PanelPicture.FRAME_WIDTH.toFloat() / (PanelPicture.WIDTH + 2 * PanelPicture.FRAME_WIDTH)
        val frameDown = PanelPicture.FRAME_WIDTH.toFloat() / (PanelPicture.HEIGHT + 2 * PanelPicture.FRAME_WIDTH)
        // V backwards: a render target's origin is at its bottom.
        graphics.blit(
            picture, PanelTarget.sampler(), left, top, right, bottom,
            frameAcross, 1.0f - frameAcross, 1.0f - frameDown, frameDown,
        )
        graphics.fill(left, top, right, bottom, ARGB.color((DIMMED_TO * eased * FULLY).toInt(), 0, 0, 0))
    }

    /** The picture's shape scaled until it covers [width] by [height], centred on it. */
    private fun filling(width: Int, height: Int): Rect {
        val scale = maxOf(width.toFloat() / PanelPicture.WIDTH, height.toFloat() / PanelPicture.HEIGHT)
        val across = (PanelPicture.WIDTH * scale).toInt()
        val down = (PanelPicture.HEIGHT * scale).toInt()
        return Rect((width - across) / 2, (height - down) / 2, across, down)
    }

    /** Where a lectern's picture starts: a panel's size in the middle of the view, as the book screen would show it. */
    private fun centred(width: Int, height: Int): Rect =
        Rect((width - PanelPicture.WIDTH) / 2, (height - PanelPicture.HEIGHT) / 2, PanelPicture.WIDTH, PanelPicture.HEIGHT)

    private fun grownBy(elapsedMillis: Long): Float = (elapsedMillis.toFloat() / GROWING_MILLIS).coerceIn(0.0f, WHOLLY)

    private val UNDERWAY: Component = Component.translatable("link.agesandtheart.underway")

    /** Long enough to read as falling into the picture, short enough that a ready arrival lands inside it. */
    private const val GROWING_MILLIS = 600L

    /** How dark the picture is once it fills the view: there, but behind the words. */
    private const val DIMMED_TO = 0.45f

    private const val WHOLLY = 1.0f
    private const val FULLY = 255
    private const val WHITE = -1
    private const val LINE = 12
    private const val WORDS_ABOVE_THE_MIDDLE = 20
}

/**
 * The transit's screen, shown from the click until vanilla's loading screen takes over or the link is refused.
 *
 * Never a pause screen: in a single-player world that would stop the server building the very arrival this is
 * waiting for. Escape gives up on the link.
 */
class LinkTransitScreen : Screen(Component.translatable("link.agesandtheart.underway")) {

    override fun isPauseScreen(): Boolean = false

    /** The world stays in view behind the picture until the picture covers it. */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) = Unit

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        LinkTransit.drawLive(graphics, width, height)
        super.extractRenderState(graphics, mouseX, mouseY, a)
        LinkTransit.drawWords(graphics, this)
    }

    override fun onClose() {
        LinkTransit.cancel()
        super.onClose()
    }
}
