package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.item.ItemDisplayContext

/** What the renderer needs to know, extracted off the entity before drawing. */
class BookRenderState : EntityRenderState() {
    val item = ItemStackRenderState()
}

/**
 * A book resting on the ground.
 *
 * Laid flat rather than standing, because it was dropped rather than placed. It is drawn as its item for
 * now — the 3D model that would let a cover and a title be seen is asset work, and this is the placeholder
 * that keeps the mechanics walkable until then.
 */
class BookEntityRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<BookEntity, BookRenderState>(context) {

    private val items = context.itemModelResolver

    override fun createRenderState(): BookRenderState = BookRenderState()

    override fun extractRenderState(entity: BookEntity, state: BookRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        items.updateForTopItem(
            state.item,
            entity.book,
            ItemDisplayContext.GROUND,
            entity.level(),
            null,
            entity.id,
        )
    }

    override fun submit(
        state: BookRenderState,
        poseStack: PoseStack,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.0f, LIFT, 0.0f)
        // Flat on its face, the way a dropped book lands.
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(FLAT))
        state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0)
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    private companion object {
        /** Off the ground just enough not to z-fight with the block below. */
        const val LIFT = 0.03f
        const val FLAT = 90.0f
    }
}
