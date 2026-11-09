package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookbinderBlock
import co.voik.agesandtheart.book.BookbinderBlockEntity
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

class BookbinderRenderState : BlockEntityRenderState() {
    var look: BookLook? = null
    var facing: Direction = Direction.NORTH
}

/**
 * The book in a bookbinder, shut and standing on its fore-edge between the clamps, spine up, as the book itself is
 * drawn everywhere else ([AgeBookRendering]): its banner, its layout and its name show as they are changed. The press
 * is the block's own model, its clamps set wide or narrow by its blockstate.
 */
class BookbinderRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BookbinderBlockEntity, BookbinderRenderState> {

    private val rendering = AgeBookRendering(context.sprites())

    override fun createRenderState(): BookbinderRenderState = BookbinderRenderState()

    override fun extractRenderState(
        binder: BookbinderBlockEntity,
        state: BookbinderRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(binder, state, partialTicks, cameraPosition, breakProgress)
        state.look = binder.book.takeUnless { it.isEmpty }?.let(BookLook::of)
        state.facing = binder.blockState.getValue(BookbinderBlock.FACING)
    }

    override fun submit(state: BookbinderRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val look = state.look ?: return
        poseStack.pushPose()
        turnAsTheBlockIs(state.facing, poseStack)
        poseStack.translate(MIDDLE, SPINE_TOP, MIDDLE)
        // The shut model runs spine to fore-edge along +X, up the book along +Y and its thickness along Z, the front
        // board toward +Z. Standing in the press as a book is held to be read: its fore-edge down, its head away from
        // whoever stands at the front of the press (which the model has north), and its front board on their right.
        poseStack.rotate(Axis.YP.rotationDegrees(-QUARTER_TURN))
        poseStack.rotate(Axis.ZP.rotationDegrees(-QUARTER_TURN))
        rendering.submit(look, SHUT, poseStack, collector, state.lightCoords)
        poseStack.popPose()
    }

    /** As the blockstate turns the press's model: unturned facing north, a quarter clockwise from above for each step round. */
    private fun turnAsTheBlockIs(facing: Direction, poseStack: PoseStack) {
        poseStack.translate(MIDDLE, MIDDLE, MIDDLE)
        poseStack.rotate(Axis.YP.rotationDegrees(-clockwiseTurnOf(facing)))
        poseStack.translate(-MIDDLE, -MIDDLE, -MIDDLE)
    }

    companion object {
        /** The press's model is drawn facing north; a blockstate turns it this far clockwise for [facing]. */
        fun clockwiseTurnOf(facing: Direction): Float = (facing.toYRot() + HALF_TURN) % FULL_TURN

        private val SHUT = AgeBookModel.State(0.0f)
        private const val MIDDLE = 0.5f
        private const val QUARTER_TURN = 90.0f
        private const val HALF_TURN = 180.0f
        private const val FULL_TURN = 360.0f

        /** The spine's top, eleven pixels up, as the book stood in Jonah's model. */
        private const val SPINE_TOP = 11.0f / 16.0f
    }
}
