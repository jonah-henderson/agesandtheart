package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.client.panel.LinkingPanel
import co.voik.agesandtheart.client.panel.PanelComposite
import co.voik.agesandtheart.client.panel.PanelPicture
import co.voik.agesandtheart.client.panel.PanelTexture
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.LoadingDotsText
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Util

/** Starts a link's transit, kept apart so common code never names the screen directly. */
object LinkTransitOpener {
    fun begin(book: BookBeingRead) {
        LinkTransit.begin(book)
        Minecraft.getInstance().setScreenAndShow(LinkTransitScreen())
    }
}

/**
 * A link between the click and the arrival: the panel's picture fading in to fill the view, dimmed, until the
 * player is in the other world.
 *
 * The picture is the panel's own composite — the Age where it has loaded, the mist where it has not — so the
 * view goes to what was clicked. It outlives its screen: vanilla's loading screen replaces it the moment the
 * server moves the player, and draws this behind its own words (`LevelLoadingScreenMixin`), held at its last
 * frame, since a second level render while vanilla changes level is the one thing not to risk.
 *
 * Shown from the click, before the server has said anything, so there is no pause to read as a refusal.
 */
object LinkTransit {

    private var book: BookBeingRead? = null
    private var startedAt = 0L

    /** Whether a link is between its click and its arrival. */
    val isUnderway: Boolean get() = book != null

    fun begin(book: BookBeingRead) {
        this.book = book
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
    }

    private fun finish() {
        forget()
        LinkingPanel.release()
    }

    /** The transit's own picture, live: laid down this frame from the panel's preview. */
    fun drawLive(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        PanelComposite.composeLive(LinkingPanel.preview, Minecraft.getInstance().deltaTracker)
        draw(graphics, PanelTexture.LIVE.location, width, height)
    }

    /** Behind vanilla's loading screen: the Age as last drawn, under mist that goes on moving. */
    fun drawHeld(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        val picture = if (PanelComposite.recomposeLive() != null) {
            PanelTexture.LIVE.location
        } else {
            PanelComposite.composeMisted()
            PanelTexture.MISTED.location
        }
        draw(graphics, picture, width, height)
    }

    /** "Linking", and vanilla's dots, where vanilla's loading screen puts its own words. */
    fun drawWords(graphics: GuiGraphicsExtractor, screen: Screen) {
        val font = Minecraft.getInstance().font
        val middle = screen.width / 2
        val top = screen.height / 2 - WORDS_ABOVE_THE_MIDDLE
        val colour = ARGB.color((fadedIn() * FULLY).toInt(), FULLY, FULLY, FULLY)
        graphics.centeredText(font, UNDERWAY, middle, top, colour)
        graphics.centeredText(font, LoadingDotsText.get(Util.getMillis()), middle, top + LINE, colour)
    }

    /**
     * [picture] over the whole view, cropped to inside its frame and keeping its shape, fading in from the
     * click and dimmed by its tint.
     *
     * In texture units of [UNITS] across the framed picture, since the tinted blit takes whole numbers; a
     * negative height runs V backwards, which a render target's bottom-left origin needs.
     */
    private fun draw(graphics: GuiGraphicsExtractor, picture: Identifier, width: Int, height: Int) {
        val scale = maxOf(width.toFloat() / PanelPicture.WIDTH, height.toFloat() / PanelPicture.HEIGHT)
        val across = (PanelPicture.WIDTH * scale).toInt()
        val down = (PanelPicture.HEIGHT * scale).toInt()
        val frameAcross = UNITS * PanelPicture.FRAME_WIDTH / (PanelPicture.WIDTH + 2 * PanelPicture.FRAME_WIDTH)
        val frameDown = UNITS * PanelPicture.FRAME_WIDTH / (PanelPicture.HEIGHT + 2 * PanelPicture.FRAME_WIDTH)
        val shade = ((1.0f - DIMMED_BY) * FULLY).toInt()
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, picture,
            (width - across) / 2, (height - down) / 2,
            frameAcross.toFloat(), (UNITS - frameDown).toFloat(),
            across, down,
            UNITS - 2 * frameAcross, -(UNITS - 2 * frameDown),
            UNITS, UNITS,
            ARGB.color((fadedIn() * FULLY).toInt(), shade, shade, shade),
        )
    }

    private fun fadedIn(): Float = ((Util.getMillis() - startedAt).toFloat() / FADING_IN_MILLIS).coerceIn(0.0f, 1.0f)

    private val UNDERWAY: Component = Component.translatable("link.agesandtheart.underway")

    private const val FADING_IN_MILLIS = 400L

    /** How much darker the picture is than the panel: there, but behind the words. */
    private const val DIMMED_BY = 0.45f

    private const val UNITS = 10_000
    private const val FULLY = 255
    private const val LINE = 12
    private const val WORDS_ABOVE_THE_MIDDLE = 20
}

/**
 * The transit's screen, from the click until vanilla's loading screen takes over or the link is refused.
 *
 * Never a pause screen: in a single-player world that would stop the server moving the player at all. And not
 * closed by escape, any more than vanilla's loading screen is: the link has already been asked for.
 */
class LinkTransitScreen : Screen(Component.translatable("link.agesandtheart.underway")) {

    override fun isPauseScreen(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = false

    /** The world shows through until the picture has faded in over it. */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) = Unit

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        LinkTransit.drawLive(graphics, width, height)
        super.extractRenderState(graphics, mouseX, mouseY, a)
        LinkTransit.drawWords(graphics, this)
    }
}
