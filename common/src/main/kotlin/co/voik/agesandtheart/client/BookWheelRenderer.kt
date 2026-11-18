package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookWheelBlock
import co.voik.agesandtheart.book.BookWheelBlockEntity
import co.voik.agesandtheart.book.WheelAxle
import co.voik.agesandtheart.book.WheelForm
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

class BookWheelRenderState : BlockEntityRenderState() {
    var form: WheelForm = WheelForm.SMALL
    var facing: Direction = Direction.NORTH

    /** Each slot's book, or null where it is empty. */
    var looks: List<BookLook?> = emptyList()

    /** The tray at the front, fractional while the wheel turns. */
    var shownFront: Float = 0.0f
}

/**
 * A book wheel's books, each drawn as itself ([AgeBookRendering]), on trays going round the wheel's axle. The frame,
 * axle and trays are the block's own model; this draws only the books, where the trays would carry them.
 *
 * On a wheel whose axle runs across, every book is kept level as it goes round, as the cars of a Ferris wheel are and
 * as Ramelli's planetary gearing kept his: it lies on its back board, its spine toward the front. On a carousel each
 * stands on its tail, spine out, turning with its tray.
 *
 * Drawn in the frame the block's model is, facing north, from the corner of the block holding the books: a large
 * wheel's second block is to the west of it (the right, from the front), a carousel's above.
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
    }

    /** A two-block wheel reaches past its own block, and is drawn while that one is out of sight. */
    override fun shouldRenderOffScreen(): Boolean = true

    override fun submit(state: BookWheelRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.translate(MIDDLE, MIDDLE, MIDDLE)
        poseStack.rotate(Axis.YP.rotationDegrees(-BookbinderRenderer.clockwiseTurnOf(state.facing)))
        poseStack.translate(-MIDDLE, -MIDDLE, -MIDDLE)
        for ((slot, look) in state.looks.withIndex()) {
            look ?: continue
            val tray = slot / state.form.booksPerTray
            val along = slot % state.form.booksPerTray
            val roundFromTheFront = (tray - state.shownFront) * QUARTER_TURN_RADIANS
            poseStack.pushPose()
            when (state.form.axle) {
                WheelAxle.ACROSS -> onATrayAcross(along, roundFromTheFront, poseStack)
                WheelAxle.UPRIGHT -> onATrayUpright(along, roundFromTheFront, poseStack)
            }
            rendering.submit(look, SHUT, poseStack, collector, state.lightCoords)
            poseStack.popPose()
        }
        poseStack.popPose()
    }

    /**
     * Onto the tray [roundFromTheFront] radians over the top from the front, at place [along] on it: lying level on its
     * back board, its spine to the front and its head to the left.
     */
    private fun onATrayAcross(along: Int, roundFromTheFront: Float, poseStack: PoseStack) {
        // A large wheel's second place on each tray is a block to the west, toward its second block.
        val x = MIDDLE - along
        val y = MIDDLE + RADIUS * sin(roundFromTheFront) + BOOK_THICKNESS / 2
        val z = MIDDLE - RADIUS * cos(roundFromTheFront)
        poseStack.translate(x, y, z)
        // The shut model runs spine to fore-edge along +X, up the book along +Y and its thickness along +Z, the front
        // board toward +Z. Laid down: the front board up, the fore-edge back (+Z) and the head to the left (+X).
        poseStack.rotate(Axis.YP.rotationDegrees(-QUARTER_TURN))
        poseStack.rotate(Axis.XP.rotationDegrees(-QUARTER_TURN))
        poseStack.scale(BOOK_SCALE, BOOK_SCALE, BOOK_SCALE)
        poseStack.translate(-HALF_A_BOARD, 0.0f, 0.0f)
    }

    /**
     * Onto the tray [roundFromTheFront] radians round to the right from the front, in tier [along]: standing on its
     * tail, spine out and the fore-edge toward the axle.
     */
    private fun onATrayUpright(along: Int, roundFromTheFront: Float, poseStack: PoseStack) {
        poseStack.translate(MIDDLE, along + TIER_FLOOR + BOOK_HEIGHT / 2, MIDDLE)
        poseStack.rotate(Axis.YP.rotation(roundFromTheFront))
        poseStack.translate(0.0f, 0.0f, -RADIUS)
        poseStack.rotate(Axis.YP.rotationDegrees(-QUARTER_TURN))
        poseStack.scale(BOOK_SCALE, BOOK_SCALE, BOOK_SCALE)
        poseStack.translate(-HALF_A_BOARD, 0.0f, 0.0f)
    }

    private companion object {
        val SHUT = AgeBookModel.State(0.0f)
        const val MIDDLE = 0.5f
        const val QUARTER_TURN = 90.0f
        const val QUARTER_TURN_RADIANS = (Math.PI / 2).toFloat()
        const val PIXEL = 1.0f / 16.0f

        /**
         * The books are drawn smaller than a lectern's, to fit four round a wheel a block across. These and the radius
         * are the art's to set: where the model's trays are, the books go.
         */
        const val BOOK_SCALE = 0.6f

        /** From the axle to where a tray carries its book. */
        const val RADIUS = 4.5f * PIXEL

        /** The carousel's tier floor, above the bottom of its block. */
        const val TIER_FLOOR = 2.0f * PIXEL

        /** The shut model's measures, in the block's units at [BOOK_SCALE]: two pixels thick and eight tall. */
        const val BOOK_THICKNESS = 2.0f * PIXEL * BOOK_SCALE
        const val BOOK_HEIGHT = 8.0f * PIXEL * BOOK_SCALE

        /** Half of the six-pixel boards, in the model's own units: from the spine to the middle of the book. */
        const val HALF_A_BOARD = 3.0f * PIXEL
    }
}
