package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.BookRectangle
import co.voik.agesandtheart.book.LecternBookPlane
import co.voik.agesandtheart.client.panel.PanelComposite
import co.voik.agesandtheart.client.panel.PanelPicture
import co.voik.agesandtheart.client.panel.PanelRenderTypes
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType

/**
 * A panel's finished picture ([PanelComposite]) laid on a page of the [AgeBookModel], for every renderer that
 * draws one: a lectern's ([LecternBookRenderer]) and a fallen book's ([BookEntityRenderer]). It is laid in the
 * page's own frame, so it rides the page as the book swings open or shut.
 */
object PanelOnThePage {

    /**
     * The panel on [page] of [model] posed at [state]: the book's own [mapPicture] where panels are maps and it has
     * one, the live picture where [isTheShownOne] and it has been laid down, and the misted one otherwise.
     */
    fun submit(
        page: BookPage,
        isTheShownOne: Boolean,
        mapPicture: RenderType?,
        model: AgeBookModel,
        state: AgeBookModel.State,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        val picture = mapPicture ?: pictureFor(isTheShownOne) ?: return
        val framed = framed(LecternBookPlane.panelOn(page))
        poseStack.pushPose()
        model.setupAnim(state)
        model.poseOntoPage(page, poseStack)
        collector.submitCustomGeometry(poseStack, picture) { pose, buffer -> layOnThePage(framed, pose, buffer) }
        poseStack.popPose()
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
    private fun layOnThePage(rectangle: BookRectangle, pose: PoseStack.Pose, buffer: VertexConsumer) {
        val left = rectangle.across.endInclusive.toFloat()
        val right = rectangle.across.start.toFloat()
        val top = rectangle.up.endInclusive.toFloat()
        val bottom = rectangle.up.start.toFloat()
        corner(pose, buffer, left, top, 0.0f, 1.0f)
        corner(pose, buffer, left, bottom, 0.0f, 0.0f)
        corner(pose, buffer, right, bottom, 1.0f, 0.0f)
        corner(pose, buffer, right, top, 1.0f, 1.0f)
    }

    /** A corner at [across] and [up] as [BookRectangle] measures them, in the page's frame ([AgeBookModel.poseOntoPage]). */
    private fun corner(pose: PoseStack.Pose, buffer: VertexConsumer, across: Float, up: Float, u: Float, v: Float) {
        buffer.addVertex(pose, -across, -up, -PICTURE_LIFT).setUv(u, v).setColor(UNTINTED)
    }

    private const val MODEL_UNITS_PER_BLOCK = 16.0f

    /** Clear of the page, so the two never fight for the same depth. */
    private const val PICTURE_LIFT = 0.1f / MODEL_UNITS_PER_BLOCK

    private const val UNTINTED = -1
}
