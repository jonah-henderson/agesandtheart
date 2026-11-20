package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.BookWheelBlock
import co.voik.agesandtheart.book.BookWheelBlockEntity
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.SmallBookWheelBlock
import co.voik.agesandtheart.book.SmallWheelShelves
import co.voik.agesandtheart.book.WheelPiece
import co.voik.agesandtheart.book.WheelAxle
import co.voik.agesandtheart.book.WheelForm
import co.voik.agesandtheart.book.WheelTrays
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.client.renderer.block.BlockModelResolver
import net.minecraft.client.renderer.block.model.BlockDisplayContext
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

class BookWheelRenderState : BlockEntityRenderState() {
    var form: WheelForm = WheelForm.SMALL
    var facing: Direction = Direction.NORTH

    /** Each slot's book, or null where it is empty. */
    var looks: List<BookLook?> = emptyList()

    /** The tray at the front, fractional while the wheel turns. */
    var shownFront: Float = 0.0f

    /** How far each slot's book has swung open, shut (0) to open (1). */
    var openness: FloatArray = FloatArray(0)

    /** Each slot's book's panel while it is open at all, where it is one of ours. */
    var panels: List<OpenOnAStand.Panel?> = emptyList()

    /** Which page each slot's book carries its panel on, where it is one of ours. */
    var pages: List<BookPage?> = emptyList()

    /** The small wheel's moving parts, in its wood; empty for the others. */
    val shelf = BlockModelRenderState()
    val arms = BlockModelRenderState()
}

/**
 * A book wheel's books, each drawn as itself ([AgeBookRendering]) and as a lectern holds one (Jonah, 2026-11-19): on
 * the slope, its front board to the reader while it is shut, swinging open and shut as a lectern's does. On the large
 * wheel and the carousel the frame, axle and trays are the block's own model, and this draws only the books, where the
 * trays carry them. The small wheel's block model is only its stand: this draws its arms turning on the axle and its
 * shelves carried round on them ([SmallWheelShelves]), each book lying on its shelf's top, tilted with it.
 *
 * On a wheel whose axle runs across, every book is kept level as it goes round, as the cars of a Ferris wheel are and
 * as Ramelli's planetary gearing kept his, read from the front; on a carousel each turns with its tray, read from out
 * beyond it.
 */
class BookWheelRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BookWheelBlockEntity, BookWheelRenderState> {

    private val rendering = AgeBookRendering(context.sprites())
    private val blockModels: BlockModelResolver = context.blockModelResolver()

    override fun createRenderState(): BookWheelRenderState = BookWheelRenderState()

    override fun extractRenderState(
        wheel: BookWheelBlockEntity,
        state: BookWheelRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(wheel, state, partialTicks, cameraPosition, breakProgress)
        state.form = wheel.form
        state.facing = wheel.blockState.getValue(BookWheelBlock.FACING)
        state.looks = List(wheel.form.books) { slot -> wheel.bookIn(slot).takeUnless { it.isEmpty }?.let(BookLook::of) }
        state.shownFront = wheel.turning.shownAt(partialTicks, wheel.frontTray)
        state.pages = List(wheel.form.books) { slot -> LecternBooks.panelPageOf(wheel.bookIn(slot)) }
        state.openness = FloatArray(wheel.form.books) { slot ->
            if (wheel.bookIn(slot).isEmpty) 0.0f else StandSwings.opennessOf(wheel, slot, wheel.isOpen(slot), partialTicks)
        }
        state.panels = List(wheel.form.books) { slot ->
            val isShowingAPage = state.pages[slot] != null && state.openness[slot] > 0.0f
            if (isShowingAPage) OpenOnAStand.panelOf(wheel.level, wheel.blockPos, slot) else null
        }
        if (wheel.form == WheelForm.SMALL) {
            blockModels.update(state.shelf, wheel.blockState.setValue(SmallBookWheelBlock.PIECE, WheelPiece.SHELF), SHELF)
            blockModels.update(state.arms, wheel.blockState.setValue(SmallBookWheelBlock.PIECE, WheelPiece.ARMS), ARMS)
        } else {
            state.shelf.clear()
            state.arms.clear()
        }
    }

    /** A two-block wheel reaches past its own block, and is drawn while that one is out of sight. */
    override fun shouldRenderOffScreen(): Boolean = true

    override fun submit(state: BookWheelRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (state.form == WheelForm.SMALL) submitSmall(state, poseStack, collector) else submitOnTrays(state, poseStack, collector)
    }

    /** The small wheel's arms and shelves, and each book on its shelf, drawn in the model's own frame, facing north. */
    private fun submitSmall(state: BookWheelRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val front = easedRound(state.shownFront)
        poseStack.pushPose()
        poseStack.translate(HALF, 0.0, HALF)
        poseStack.rotate(Axis.YP.rotationDegrees(-turnFromNorthOf(state.facing)))
        poseStack.translate(-HALF, 0.0, -HALF)

        poseStack.pushPose()
        val axle = SmallWheelShelves.AXLE.scale(1.0 / PIXELS)
        poseStack.translate(axle.x, axle.y, axle.z)
        poseStack.rotate(Axis.XP.rotationDegrees(SmallWheelShelves.armsTurnedDegrees(front).toFloat()))
        poseStack.translate(-axle.x, -axle.y, -axle.z)
        state.arms.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
        poseStack.popPose()

        for (tray in 0 until WheelForm.TRAYS) {
            val pose = SmallWheelShelves.poseAt(tray - front)
            poseStack.pushPose()
            val middle = pose.middle.scale(1.0 / PIXELS)
            poseStack.translate(middle.x, middle.y, middle.z)
            poseStack.rotate(Axis.XP.rotationDegrees(pose.tiltDegrees.toFloat()))
            poseStack.translate(-HALF, -HALF, -HALF)
            state.shelf.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            poseStack.popPose()

            val look = state.looks.getOrNull(tray) ?: continue
            val open = state.openness[tray]
            poseStack.pushPose()
            val top = pose.top.scale(1.0 / PIXELS)
            OpenOnAStand.poseLyingOn(poseStack, top, OpenOnAStand.readerYRotOf(Direction.NORTH), pose.leanedBackDegrees.toFloat(), look.binding, open)
            OpenOnAStand.submit(rendering, look, state.pages[tray], state.panels[tray], open, poseStack, collector, state.lightCoords)
            poseStack.popPose()
        }
        poseStack.popPose()
    }

    private fun submitOnTrays(state: BookWheelRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        for ((slot, look) in state.looks.withIndex()) {
            look ?: continue
            val along = slot % state.form.booksPerTray
            val round = ((slot / state.form.booksPerTray) - state.shownFront).toDouble() * WheelTrays.QUARTER_TURN
            val middle = WheelTrays.openBookMiddle(state.form, state.facing, along, round)
            val readerYRot = when (state.form.axle) {
                WheelAxle.ACROSS -> OpenOnAStand.readerYRotOf(state.facing)
                // Read from out beyond the tray, away from the axle.
                WheelAxle.UPRIGHT -> {
                    val tray = WheelTrays.trayMiddle(state.form, state.facing, along, round)
                    OpenOnAStand.readerYRotOf(Vec3(tray.x - AXLE, 0.0, tray.z - AXLE))
                }
            }
            val open = state.openness[slot]
            poseStack.pushPose()
            OpenOnAStand.poseOpen(poseStack, middle, readerYRot, look.binding, open)
            OpenOnAStand.submit(rendering, look, state.pages[slot], state.panels[slot], open, poseStack, collector, state.lightCoords)
            poseStack.popPose()
        }
    }

    private companion object {
        /** A carousel's axle, up the middle of its blocks. */
        const val AXLE = 0.5

        const val HALF = 0.5
        const val PIXELS = 16.0
        const val NO_OUTLINE = 0
        val SHELF: BlockDisplayContext = BlockDisplayContext.create()
        val ARMS: BlockDisplayContext = BlockDisplayContext.create()

        /** How far a blockstate turns a model built facing north to face [facing], clockwise from above. */
        fun turnFromNorthOf(facing: Direction): Float = (facing.toYRot() + 180.0f) % 360.0f

        /** The tray at the front, eased in and out of each quarter turn so the shelves start and settle gently. */
        fun easedRound(shownFront: Float): Double {
            val whole = floor(shownFront.toDouble())
            val share = shownFront - whole
            return whole + share * share * (3.0 - 2.0 * share)
        }
    }
}
