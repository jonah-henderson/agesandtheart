package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.Scarab
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.animal.bee.AdultBeeModel
import net.minecraft.client.model.animal.bee.BabyBeeModel
import net.minecraft.client.model.animal.bee.BeeModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.client.renderer.block.BlockModelResolver
import net.minecraft.client.renderer.block.model.BlockDisplayContext
import net.minecraft.client.renderer.entity.AgeableMobRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.state.BeeRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier

/**
 * The scarab, drawn as a bee in a beetle's green until the asset pass gives it a body of its own (design
 * §7.1.2), and with the sand it is carrying hung underneath, as an enderman holds its block.
 *
 * **Vanilla's model and vanilla's texture**, the golem's bargain: nothing shipped, and the tint is what keeps
 * it from reading as a bee. The stinger is hidden, a beetle having none.
 */
@Suppress("DEPRECATION") // `AgeableMobRenderer` is how vanilla's own bee swaps in its baby model.
class ScarabRenderer(context: EntityRendererProvider.Context) :
    AgeableMobRenderer<Scarab, ScarabRenderState, BeeModel>(
        context,
        AdultBeeModel(context.bakeLayer(ModelLayers.BEE)),
        BabyBeeModel(context.bakeLayer(ModelLayers.BEE_BABY)),
        SHADOW,
    ) {

    private val blockModels: BlockModelResolver = context.blockModelResolver

    init {
        addLayer(CarriedSand(this))
    }

    override fun createRenderState(): ScarabRenderState = ScarabRenderState()

    override fun extractRenderState(entity: Scarab, state: ScarabRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.hasStinger = false
        state.isOnGround = entity.onGround() && entity.deltaMovement.lengthSqr() < STILL
        val carried = entity.carried
        if (carried == null) state.carriedBlock.clear() else blockModels.update(state.carriedBlock, carried, CARRIED)
    }

    override fun getTextureLocation(state: ScarabRenderState): Identifier = if (state.isBaby) YOUNG else ADULT

    override fun getModelTint(state: ScarabRenderState): Int = SCARAB_GREEN

    private companion object {
        val ADULT: Identifier = Identifier.withDefaultNamespace("textures/entity/bee/bee.png")
        val YOUNG: Identifier = Identifier.withDefaultNamespace("textures/entity/bee/bee_baby.png")
        val CARRIED: BlockDisplayContext = BlockDisplayContext.create()

        /** An opaque ARGB tint over the borrowed texture: the bee's yellow comes out a beetle's green. */
        const val SCARAB_GREEN = 0xFF4FB0A0.toInt()

        const val SHADOW = 0.4f
        const val STILL = 1.0e-7
    }
}

class ScarabRenderState : BeeRenderState() {
    val carriedBlock = BlockModelRenderState()
}

/**
 * The sand, a small block slung under the body. Placed in the model's own space, where y runs down and x is
 * mirrored: the block's scale mirrors both back, and the offsets centre it under the body's lower face.
 */
private class CarriedSand(parent: RenderLayerParent<ScarabRenderState, BeeModel>) :
    RenderLayer<ScarabRenderState, BeeModel>(parent) {

    override fun submit(
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        lightCoords: Int,
        state: ScarabRenderState,
        yRot: Float,
        xRot: Float,
    ) {
        val carried = state.carriedBlock
        if (carried.isEmpty) return
        poseStack.pushPose()
        poseStack.translate(SIZE / 2, UNDER_THE_BODY + SIZE, -SIZE / 2)
        poseStack.scale(-SIZE, -SIZE, SIZE)
        carried.submit(poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor)
        poseStack.popPose()
    }

    private companion object {
        const val SIZE = 0.4f

        /** The body's lower face: the bone sits at 19 pixels and the body reaches three below it. */
        const val UNDER_THE_BODY = 22.0f / 16.0f
    }
}
