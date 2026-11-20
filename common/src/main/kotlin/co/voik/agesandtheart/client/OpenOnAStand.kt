package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.LecternBookPlane
import co.voik.agesandtheart.client.panel.BookMaps
import co.voik.agesandtheart.client.panel.OpenBookPanels
import co.voik.agesandtheart.client.panel.PanelComposite
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2

/**
 * A book lying open on a stand of ours ([co.voik.agesandtheart.book.BookStand]), drawn as a lectern's is
 * ([LecternBookRenderer]): on the lectern's slope and turned to its reader, with its panel showing the Age, the mist,
 * or where panels are maps, its own map.
 */
object OpenOnAStand {

    /** What the panel on an open book shows this frame. */
    class Panel(val isTheShownOne: Boolean, val mapPicture: RenderType?)

    /** The panel of the book at [place] on the stand at [pos], told to the panels as a lectern's renderer tells them. */
    fun panelOf(level: Level?, pos: BlockPos, place: Int): Panel {
        val thisBook = BookBeingRead.OnAStand(pos, place)
        val inThePlayersWorld = level != null && level === Minecraft.getInstance().level
        if (BookMaps.isOn) {
            val map = if (inThePlayersWorld) BookMaps.pictureOnAPage(thisBook) else null
            if (map == null) OpenBookPanels.sawAMistedBook()
            return Panel(isTheShownOne = false, mapPicture = map)
        }
        val isShown = inThePlayersWorld && OpenBookPanels.shown == thisBook
        if (isShown) OpenBookPanels.sawTheShownBook() else OpenBookPanels.sawAMistedBook()
        // Until the Age has first been drawn, the shown panel wears the mist too.
        if (isShown && PanelComposite.liveView() == null) OpenBookPanels.sawAMistedBook()
        return Panel(isShown, null)
    }

    /**
     * [poseStack] carried onto a lectern's slope whose book's middle is at [middle] from the pose's origin, read by
     * somebody looking [readerYRot] (a lectern's own: its facing's clockwise turn), and the book [open] of the way open.
     */
    fun poseOpen(poseStack: PoseStack, middle: Vec3, readerYRot: Float, binding: BookBinding, open: Float) {
        poseStack.translate(middle.x, middle.y, middle.z)
        poseStack.rotate(Axis.YP.rotationDegrees(-readerYRot))
        poseStack.rotate(Axis.ZP.rotationDegrees(LecternBookPlane.TILT_DEGREES.toFloat()))
        poseStack.translate(-LecternBookPlane.ONTO_THE_SLOPE, -LecternBookPlane.DOWN_THE_SLOPE, 0.0)
        BookOnTheSlope.layOnItsBack(open, binding, poseStack)
    }

    /**
     * [poseStack] carried onto a surface whose middle is at [top], read by somebody looking [readerYRot], the surface
     * leaning back [leanedBackDegrees] from upright (a lectern's slope 67.5, a flat shelf 90), and the book [open] of
     * the way open.
     */
    fun poseLyingOn(poseStack: PoseStack, top: Vec3, readerYRot: Float, leanedBackDegrees: Float, binding: BookBinding, open: Float) {
        poseStack.translate(top.x, top.y, top.z)
        poseStack.rotate(Axis.YP.rotationDegrees(-readerYRot))
        poseStack.rotate(Axis.ZP.rotationDegrees(leanedBackDegrees))
        BookOnTheSlope.layOnItsBack(open, binding, poseStack)
    }

    /** As a lectern facing [facing] is read. */
    fun readerYRotOf(facing: Direction): Float = facing.clockWise.toYRot()

    /** As a lectern facing out along [outward], in the world's axes, is read: for a stand that turns its books round. */
    fun readerYRotOf(outward: Vec3): Float = Math.toDegrees(atan2(-outward.x, outward.z)).toFloat() + QUARTER_TURN

    /** The book [look] lying [open] where [poseStack] has been posed ([poseOpen]), with its [panel] on [page]. */
    fun submit(
        rendering: AgeBookRendering,
        look: BookLook,
        page: BookPage?,
        panel: Panel?,
        open: Float,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        light: Int,
    ) {
        val state = BookOnTheSlope.bookAt(open)
        rendering.submit(look, state, poseStack, collector, light)
        if (page == null || panel == null || open <= 0.0f) return
        PanelOnThePage.submit(page, panel.isTheShownOne, panel.mapPicture, rendering.modelFor(look.binding), state, poseStack, collector)
    }

    private const val QUARTER_TURN = 90.0f
}
