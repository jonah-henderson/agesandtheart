package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.BookRectangle
import co.voik.agesandtheart.book.LecternBookPlane
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.LecternOpening
import co.voik.agesandtheart.client.panel.LecternPanels
import co.voik.agesandtheart.client.panel.PanelComposite
import co.voik.agesandtheart.client.panel.PanelPicture
import co.voik.agesandtheart.client.panel.PanelRenderTypes
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.`object`.book.BookModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer
import net.minecraft.client.renderer.blockentity.LecternRenderer
import net.minecraft.client.renderer.blockentity.state.LecternRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A lectern's render state, carrying a book of ours when there is one on it. */
class LecternBookRenderState : LecternRenderState() {

    /** Which page carries the book's panel, or null where the book is not ours and vanilla draws it. */
    var panelPage: BookPage? = null

    var open: Boolean = false

    /** Whether this is the lectern whose panel the client is showing — the one that may show its Age. */
    var isTheShownOne: Boolean = false
}

/**
 * Vanilla's lectern renderer, drawing a book of ours shut or open (design §7.8.2) and every other book as
 * it always has.
 *
 * Registered in vanilla's place by each loader, and it extends rather than wraps so that vanilla's books go
 * through the class they always did — on NeoForge, patched render bounds included. The cover is still the
 * enchanting table's; ours is asset work.
 */
class LecternBookRenderer(context: BlockEntityRendererProvider.Context) : LecternRenderer(context) {

    private val sprites = context.sprites()
    private val bookModel = BookModel(context.bakeLayer(ModelLayers.BOOK))

    override fun createRenderState(): LecternRenderState = LecternBookRenderState()

    override fun extractRenderState(
        lectern: LecternBlockEntity,
        state: LecternRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(lectern, state, partialTicks, cameraPosition, breakProgress)
        if (state !is LecternBookRenderState) return
        state.panelPage = LecternBooks.panelPageOf(lectern.book)
        state.open = LecternOpening.isOpen(lectern.blockState)
        state.isTheShownOne = false
        if (state.panelPage == null || !state.open) return

        // Only a lectern in the player's own world may show the Age. One inside an Age a panel is showing — even
        // this very lectern, where a book lies on the spot its own panel frames — wears the mist, or the picture
        // would be drawn into itself.
        val inThePlayersWorld = lectern.level === Minecraft.getInstance().level
        state.isTheShownOne = inThePlayersWorld && lectern.blockPos == LecternPanels.shown
        if (state.isTheShownOne) LecternPanels.sawTheShownLectern() else LecternPanels.sawAMistedLectern()
    }

    override fun submit(
        state: LecternRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val ours = ourBookIn(state) ?: return super.submit(state, poseStack, collector, camera)
        poseStack.pushPose()
        poseStack.translate(HALF_A_BLOCK, LecternBookPlane.CENTRE_HEIGHT, HALF_A_BLOCK)
        poseStack.mulPose(Axis.YP.rotationDegrees(-ours.yRot))
        poseStack.mulPose(Axis.ZP.rotationDegrees(LecternBookPlane.TILT_DEGREES.toFloat()))
        poseStack.translate(0.0, -LecternBookPlane.DOWN_THE_SLOPE, 0.0)
        if (ours.open) {
            submitTheBook(ours, OPEN, poseStack, collector)
            submitThePanel(ours, poseStack, collector)
        } else {
            layShut(poseStack)
            submitTheBook(ours, SHUT, poseStack, collector)
        }
        poseStack.popPose()
    }

    /** [state] as ours where the lectern holds a book of ours, and null to leave it to vanilla. */
    private fun ourBookIn(state: LecternRenderState): LecternBookRenderState? {
        val ours = state as? LecternBookRenderState ?: return null
        return ours.takeIf { it.hasBook && it.panelPage != null }
    }

    private fun submitTheBook(
        state: LecternBookRenderState,
        pose: BookModel.State,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        collector.submitModel(
            bookModel,
            pose,
            poseStack,
            state.lightCoords,
            OverlayTexture.NO_OVERLAY,
            UNTINTED,
            EnchantTableRenderer.BOOK_TEXTURE,
            sprites,
            NO_OUTLINE,
            state.breakProgress,
        )
    }

    /** A shut book lies cover-up on the slope, its spine running up the page and itself to one side of it. */
    private fun layShut(poseStack: PoseStack) {
        poseStack.translate(SHUT_LIFT, 0.0f, SHUT_SHIFT_TOWARD_THE_LEFT)
        poseStack.mulPose(Axis.YP.rotationDegrees(QUARTER_TURN))
    }

    /**
     * The panel on its page: one quad of its finished picture ([PanelComposite]), frame and all — the live one
     * on the lectern being shown, the misted one on every other. The same picture a book screen shows.
     */
    private fun submitThePanel(state: LecternBookRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val page = state.panelPage ?: return
        val picture = pictureFor(state) ?: return
        val framed = framed(LecternBookPlane.panelOn(page))
        collector.submitCustomGeometry(poseStack, picture) { pose, buffer -> layOnThePage(framed, pose, buffer) }
    }

    /** Whichever picture this lectern shows, of those that have been laid down. */
    private fun pictureFor(state: LecternBookRenderState): RenderType? {
        val showsTheAge = state.isTheShownOne && PanelComposite.liveView() != null
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

    /** In the book's own frame: X stands off the page, Y runs up it, and Z toward the reader's left. */
    private fun corner(pose: PoseStack.Pose, buffer: VertexConsumer, across: Float, up: Float, u: Float, v: Float) {
        buffer.addVertex(pose, pageSurfaceAt(across) + PICTURE_LIFT, up, across).setUv(u, v).setColor(UNTINTED)
    }

    /**
     * How far off the model's origin the page's surface lies at [across] from the spine: the leaves' pivot
     * stands proud by `sin(openness)` model units, and each leaf rises toward its fore-edge by the little it
     * falls short of lying flat.
     */
    private fun pageSurfaceAt(across: Float): Float = PAGE_AT_THE_SPINE + PAGE_RISE * abs(across)

    private companion object {
        const val HALF_A_BLOCK = 0.5
        const val MODEL_UNITS_PER_BLOCK = 16.0f

        /** Vanilla's own opening, which `LecternRenderer` passes to `forAnimation` as its last argument. */
        const val VANILLA_OPENING = 1.2f

        val OPENNESS: Float = BookModel.State.forAnimation(0.0f, 0.0f, 0.0f, VANILLA_OPENING).openness()

        /**
         * How far under the leaves the two flip pages are tucked. Vanilla lifts them off the leaves, and a
         * sheet standing over the page would stand over the panel too.
         */
        const val TUCKED = 0.02f

        val OPEN = BookModel.State(OPENNESS, -TUCKED, 1.0f + TUCKED)

        val SHUT = BookModel.State(0.0f, 0.0f, 0.0f)

        /** The top of a leaf's box, which sits this far past its pivot. */
        const val LEAF_TOP_UNITS = 0.01f

        val PAGE_AT_THE_SPINE: Float = (sin(OPENNESS) + LEAF_TOP_UNITS) / MODEL_UNITS_PER_BLOCK
        val PAGE_RISE: Float = cos(OPENNESS) / sin(OPENNESS)

        /** Clear of the page, so the two never fight for the same depth. */
        const val PICTURE_LIFT = 0.1f / MODEL_UNITS_PER_BLOCK

        /** The shut model stands straight out of the slope, and a quarter turn about its spine lays it down. */
        const val QUARTER_TURN = 90.0f

        /** Lifted by one cover's thickness, so the lower cover rests on the slope rather than in it. */
        const val SHUT_LIFT = 1.0f / MODEL_UNITS_PER_BLOCK

        /** Slid by half its width, so a shut book sits centred rather than hanging off its spine. */
        const val SHUT_SHIFT_TOWARD_THE_LEFT = 3.0f / MODEL_UNITS_PER_BLOCK

        const val UNTINTED = -1
        const val NO_OUTLINE = 0
    }
}
