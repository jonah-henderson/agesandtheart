package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.BookRectangle
import co.voik.agesandtheart.book.LecternBookPlane
import co.voik.agesandtheart.client.panel.PanelComposite
import co.voik.agesandtheart.client.panel.PanelPicture
import co.voik.agesandtheart.client.panel.PanelRenderTypes
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.`object`.book.BookModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A panel's finished picture ([PanelComposite]) laid on a page of the open book model, for every renderer that
 * draws one lying open: a lectern's ([LecternBookRenderer]) and a fallen book's ([BookEntityRenderer]).
 *
 * The pose stack is the book model's own frame: X stands off the page, Y runs up it, and Z toward the reader's
 * left.
 */
object PanelOnThePage {

    /** The open book at [openness], its two flip pages tucked under the leaves so neither stands over a panel. */
    fun pose(openness: Float): BookModel.State = BookModel.State(openness, -TUCKED, 1.0f + TUCKED)

    /**
     * The panel on [page] of a book open at [openness]: the live picture where [isTheShownOne], and the misted
     * one otherwise.
     */
    fun submit(
        page: BookPage,
        isTheShownOne: Boolean,
        openness: Float,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        val picture = pictureFor(isTheShownOne) ?: return
        val framed = framed(LecternBookPlane.panelOn(page))
        val surface = PageSurface(openness)
        collector.submitCustomGeometry(poseStack, picture) { pose, buffer -> layOnThePage(framed, surface, pose, buffer) }
    }

    /** Whichever picture this book shows, of those that have been laid down. */
    private fun pictureFor(isTheShownOne: Boolean): RenderType? {
        val showsTheAge = isTheShownOne && PanelComposite.liveView() != null
        return when {
            showsTheAge -> PanelRenderTypes.livePicture
            PanelComposite.mistedView() != null -> PanelRenderTypes.mistedPicture
            else -> null
        }
    }

    /** [panel] with the frame round it that the picture carries, at the panel's own scale. */
    private fun framed(panel: BookRectangle): BookRectangle {
        val pagePixel = (panel.across.endInclusive - panel.across.start) / PanelPicture.WIDTH
        val frame = pagePixel * PanelPicture.FRAME_WIDTH
        return BookRectangle(
            across = (panel.across.start - frame)..(panel.across.endInclusive + frame),
            up = (panel.up.start - frame)..(panel.up.endInclusive + frame),
        )
    }

    /**
     * [rectangle] laid on the page, the picture the right way up for the reader: its left edge on the
     * reader's left, which is `across`'s larger end, and its top up the page. V runs backwards because a
     * render target's origin is at its bottom.
     */
    private fun layOnThePage(rectangle: BookRectangle, surface: PageSurface, pose: PoseStack.Pose, buffer: VertexConsumer) {
        val left = rectangle.across.endInclusive.toFloat()
        val right = rectangle.across.start.toFloat()
        val top = rectangle.up.endInclusive.toFloat()
        val bottom = rectangle.up.start.toFloat()
        corner(pose, buffer, surface, left, top, 0.0f, 1.0f)
        corner(pose, buffer, surface, left, bottom, 0.0f, 0.0f)
        corner(pose, buffer, surface, right, bottom, 1.0f, 0.0f)
        corner(pose, buffer, surface, right, top, 1.0f, 1.0f)
    }

    private fun corner(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        surface: PageSurface,
        across: Float,
        up: Float,
        u: Float,
        v: Float,
    ) {
        buffer.addVertex(pose, surface.at(across) + PICTURE_LIFT, up, across).setUv(u, v).setColor(UNTINTED)
    }

    /**
     * How far off the model's origin the page's surface lies across a book open at [openness]: the leaves' pivot
     * stands proud by `sin(openness)` model units, and each leaf rises toward its fore-edge by the little it
     * falls short of lying flat.
     */
    private class PageSurface(openness: Float) {
        private val atTheSpine = (sin(openness) + LEAF_TOP_UNITS) / MODEL_UNITS_PER_BLOCK
        private val rise = cos(openness) / sin(openness)

        fun at(across: Float): Float = atTheSpine + rise * abs(across)
    }

    private const val MODEL_UNITS_PER_BLOCK = 16.0f

    /**
     * How far under the leaves the two flip pages are tucked. Vanilla lifts them off the leaves, and a sheet
     * standing over the page would stand over the panel too.
     */
    private const val TUCKED = 0.02f

    /** The top of a leaf's box, which sits this far past its pivot. */
    private const val LEAF_TOP_UNITS = 0.01f

    /** Clear of the page, so the two never fight for the same depth. */
    private const val PICTURE_LIFT = 0.1f / MODEL_UNITS_PER_BLOCK

    private const val UNTINTED = -1
}
