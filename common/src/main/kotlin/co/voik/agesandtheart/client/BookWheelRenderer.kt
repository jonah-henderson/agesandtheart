package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.BookWheelBlock
import co.voik.agesandtheart.book.BookWheelBlockEntity
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.WheelAxle
import co.voik.agesandtheart.book.WheelForm
import co.voik.agesandtheart.book.WheelTrays
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

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
}

/**
 * A book wheel's books, each drawn as itself ([AgeBookRendering]) and as a lectern holds one (Jonah, 2026-11-19): on
 * the slope, its front board to the reader while it is shut, swinging open and shut as a lectern's does. The frame,
 * axle and trays are the block's own model; this draws only the books, where the trays carry them.
 *
 * On a wheel whose axle runs across, every book is kept level as it goes round, as the cars of a Ferris wheel are and
 * as Ramelli's planetary gearing kept his, read from the front; on a carousel each turns with its tray, read from out
 * beyond it.
 */
class BookWheelRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BookWheelBlockEntity, BookWheelRenderState> {

    private val rendering = AgeBookRendering(context.sprites())

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
    }

    /** A two-block wheel reaches past its own block, and is drawn while that one is out of sight. */
    override fun shouldRenderOffScreen(): Boolean = true

    override fun submit(state: BookWheelRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
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
    }
}
