package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.client.panel.LinkingPanel
import co.voik.agesandtheart.client.panel.PanelComposite
import co.voik.agesandtheart.client.panel.PanelPicture
import co.voik.agesandtheart.client.panel.PanelTarget
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.desk.CrystalViewerMenu
import co.voik.agesandtheart.desk.ViewerFinding
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The crystal viewer's window: the same picture a book's panel shows ([PanelComposite]), drawn as large as
 * the window allows. With nothing to show it wears the mist, and a line under it says why.
 */
class CrystalViewerScreen(menu: CrystalViewerMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<CrystalViewerMenu>(
        menu, inventory, title, framedWidthAt(scale), framedHeightAt(scale),
    ) {

    /** How many page-pixels' worth of screen each of the panel's page-pixels takes, fixed as it opens. */
    private val panelScale = scale

    /** Whether this screen has asked for the panel, which is what it gives back as it closes. */
    private var asked = false

    /** When the panel was asked for, which is what its wait is measured against. */
    private var askedAt = 0L

    override fun init() {
        super.init()
        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(imageWidth, imageHeight)
            },
        )
    }

    /** Asks for the panel once the server has said there is an Age to show, which arrives a tick after the screen. */
    override fun containerTick() {
        super.containerTick()
        if (!asked && menu.finding == ViewerFinding.PREVIEWING) {
            asked = true
            askedAt = System.nanoTime()
            LinkingPanel.ask(BookBeingRead.AtACrystalViewer)
        }
        if (asked) LinkingPanel.tick()
    }

    /** Gives the ring back on every way out. */
    override fun removed() {
        super.removed()
        if (asked) LinkingPanel.release()
    }

    /** Title only: there is no inventory on this screen, so vanilla's second label would name nothing. */
    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, title, titleLabelX, titleLabelY, Palette.TEXT, false)
    }

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractContents(graphics, mouseX, mouseY, a)
        val x = leftPos + MARGIN + PanelPicture.FRAME_WIDTH
        val y = topPos + CONTENT_TOP + PanelPicture.FRAME_WIDTH
        val width = (PanelPicture.WIDTH * panelScale).toInt()
        val height = (PanelPicture.HEIGHT * panelScale).toInt()
        val frame = PanelPicture.FRAME_WIDTH
        val picture = if (asked) {
            PanelComposite.composeLive(LinkingPanel.preview, Minecraft.getInstance().deltaTracker)
        } else {
            PanelComposite.composeMisted()
        }
        // V backwards: a render target's origin is at its bottom.
        graphics.blit(picture, PanelTarget.sampler(), x - frame, y - frame, x + width + frame, y + height + frame, 0.0f, 1.0f, 1.0f, 0.0f)
        if (asked) drawWaiting(graphics, x, y + height, width)
        val why = sayingWhy() ?: return
        graphics.textWithWordWrap(font, why, x, y + height + frame + LINE_GAP, width, Palette.TEXT)
    }

    /** Why there is no Age in the panel, or null while there is one, or nothing has been heard yet. */
    private fun sayingWhy(): Component? = when (menu.finding) {
        ViewerFinding.UNHEARD, ViewerFinding.PREVIEWING -> null
        ViewerFinding.NO_DESK -> translated("no_desk")
        ViewerFinding.IDLE_DESK -> translated("idle_desk")
        ViewerFinding.UNWRITABLE -> translated("unwritable")
        ViewerFinding.NOT_AN_AGE -> translated("not_an_age")
    }

    /**
     * A mark travelling along the panel's foot while there is still nothing in it, as a book's does: nothing
     * for the first [BEFORE_SAYING_SO_NANOS], and gone the instant a chunk is drawn.
     */
    private fun drawWaiting(graphics: GuiGraphicsExtractor, x: Int, foot: Int, width: Int) {
        if (!LinkingPanel.isWaiting) return
        val waited = System.nanoTime() - askedAt
        if (waited < BEFORE_SAYING_SO_NANOS) return
        val throughSweep = ((waited % SWEEP_NANOS).toDouble() / SWEEP_NANOS).toFloat()
        // Back and forth, so the mark never jumps from one end of the border to the other.
        val alongTheTrack = if (throughSweep < 0.5f) throughSweep * 2 else (1.0f - throughSweep) * 2
        val mark = (WAITING_MARK * panelScale).toInt()
        val from = x + ((width - mark) * alongTheTrack).toInt()
        graphics.fill(from, foot, from + mark, foot + WAITING_HEIGHT, WAITING_INK)
    }

    private fun translated(suffix: String): Component =
        Component.translatable("container.agesandtheart.crystal_viewer.$suffix")

    private companion object {
        /** Clear of the title, as every container screen's contents are. */
        const val CONTENT_TOP = 18
        const val MARGIN = 8

        /** Room under the panel for two lines of why there is nothing in it. */
        const val UNDER_THE_PANEL = 26
        const val LINE_GAP = 4

        /** Air round the whole window, so it is never pressed against the edges of the screen. */
        const val PADDING = 16

        /** A book's size, at the least: smaller than that is not a viewer. */
        const val SMALLEST_SCALE = 1.0f

        /** Past this the render is being stretched more than it was drawn for. */
        const val LARGEST_SCALE = 6.0f

        /** A book's, so the two waits read as the same wait. */
        const val BEFORE_SAYING_SO_NANOS = 2_000_000_000L
        const val SWEEP_NANOS = 1_600_000_000L
        const val WAITING_HEIGHT = 2
        const val WAITING_MARK = 22

        /** Read against the panel's frame, which it travels along. */
        val WAITING_INK = 0xAA2B2118.toInt()

        /**
         * **As large as the window will take**, read as the screen is built because a container screen's
         * size is final — the panel's eight to five kept, whichever of width or height runs out first.
         */
        val scale: Float
            get() {
                val window = Minecraft.getInstance().window
                val across = (window.guiScaledWidth - PADDING * 2 - MARGIN * 2).toFloat() / PanelPicture.WIDTH
                val down = (window.guiScaledHeight - PADDING * 2 - CONTENT_TOP - UNDER_THE_PANEL).toFloat() / PanelPicture.HEIGHT
                return minOf(across, down).coerceIn(SMALLEST_SCALE, LARGEST_SCALE)
            }

        fun framedWidthAt(scale: Float): Int =
            (PanelPicture.WIDTH * scale).toInt() + PanelPicture.FRAME_WIDTH * 2 + MARGIN * 2

        fun framedHeightAt(scale: Float): Int =
            CONTENT_TOP + (PanelPicture.HEIGHT * scale).toInt() + PanelPicture.FRAME_WIDTH * 2 + UNDER_THE_PANEL
    }
}
