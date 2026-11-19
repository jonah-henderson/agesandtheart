package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookstandBlockEntity
import co.voik.agesandtheart.book.BookstandPlaces
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

class BookstandRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH

    /** Each book standing, by the first place it stands in, and how many places it takes. */
    var books: List<StandingBook> = emptyList()

    data class StandingBook(val first: Int, val width: Int, val look: BookLook)
}

/**
 * A bookstand's books, each drawn shut as itself ([AgeBookRendering]) at its full size, upright on the base with its
 * spine to the front and its front board to the right, as a shelf holds one. The base and guards are the block's own
 * model.
 */
class BookstandRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BookstandBlockEntity, BookstandRenderState> {

    private val rendering = AgeBookRendering(context.sprites())

    override fun createRenderState(): BookstandRenderState = BookstandRenderState()

    override fun extractRenderState(
        stand: BookstandBlockEntity,
        state: BookstandRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(stand, state, partialTicks, cameraPosition, breakProgress)
        state.facing = stand.facing
        state.books = stand.standing().map { (first, book) ->
            BookstandRenderState.StandingBook(first, BookstandPlaces.widthOf(book), BookLook.of(book))
        }
    }

    override fun submit(state: BookstandRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        for (book in state.books) {
            poseStack.pushPose()
            // Drawn as though the spines faced south, then turned to face where they do.
            poseStack.translate(HALF, 0.0, HALF)
            poseStack.rotate(Axis.YP.rotationDegrees(-state.facing.toYRot()))
            poseStack.translate(-HALF, 0.0, -HALF)
            poseStack.translate(pixels(BookstandPlaces.middleOf(book.first, book.width)), pixels(BOOK_MIDDLE_UP), pixels(SPINE))
            // The shut model's spine is its origin and its fore-edge along +X: turned so the fore-edge points to the back.
            poseStack.rotate(Axis.YP.rotationDegrees(QUARTER_TURN))
            rendering.submit(book.look, SHUT, poseStack, collector, state.lightCoords)
            poseStack.popPose()
        }
    }

    private fun pixels(count: Double): Double = count / PIXELS_A_BLOCK

    private companion object {
        const val HALF = 0.5
        const val PIXELS_A_BLOCK = 16.0
        const val QUARTER_TURN = 90.0f

        /** A book's middle, half its eight pixels' height over the base's one. */
        const val BOOK_MIDDLE_UP = 5.0

        /** Where every spine stands, half a pixel in from the front of the base. */
        const val SPINE = 11.5

        val SHUT = AgeBookModel.State(0.0f)
    }
}
