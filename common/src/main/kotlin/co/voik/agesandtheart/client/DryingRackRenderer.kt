package co.voik.agesandtheart.client

import co.voik.agesandtheart.station.DryingRackBlock
import co.voik.agesandtheart.station.DryingRackBlockEntity
import co.voik.agesandtheart.station.DryingRackHangers
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.Vec3

class DryingRackRenderState : BlockEntityRenderState() {
    val inputs: List<ItemStackRenderState> = List(DryingRackBlockEntity.INPUT_SLOTS) { ItemStackRenderState() }
    var facing: Direction = Direction.NORTH
}

/**
 * What hangs on a drying rack, drying or dried, at its spots ([DryingRackHangers]). The rack itself is its
 * blockstate's model, two blocks tall; these are drawn from the lower half.
 */
class DryingRackRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<DryingRackBlockEntity, DryingRackRenderState> {

    private val itemModelResolver: ItemModelResolver = context.itemModelResolver()

    override fun createRenderState(): DryingRackRenderState = DryingRackRenderState()

    override fun extractRenderState(
        rack: DryingRackBlockEntity,
        state: DryingRackRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(rack, state, partialTicks, cameraPosition, breakProgress)
        state.facing = rack.blockState.getValue(DryingRackBlock.FACING)
        val seed = rack.blockPos.asLong().toInt()
        for ((slot, itemState) in state.inputs.withIndex()) {
            itemModelResolver.updateForTopItem(itemState, rack.getItem(slot), ItemDisplayContext.FIXED, rack.level, null, seed + slot)
        }
    }

    override fun submit(state: DryingRackRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        for ((slot, itemState) in state.inputs.withIndex()) {
            if (itemState.isEmpty) continue
            val spot = DryingRackHangers.spotOf(slot)
            poseStack.pushPose()
            turnAsTheBlockIs(state.facing, poseStack)
            poseStack.translate(spot.across / UNITS_PER_BLOCK, spot.up / UNITS_PER_BLOCK, spot.out / UNITS_PER_BLOCK)
            poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)
            itemState.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            poseStack.popPose()
        }
    }

    /** As the blockstate turns the rack's model, which faces north unturned: a quarter clockwise from above a step. */
    private fun turnAsTheBlockIs(facing: Direction, poseStack: PoseStack) {
        poseStack.translate(MIDDLE, MIDDLE, MIDDLE)
        poseStack.rotate(Axis.YP.rotationDegrees(-DryingRackHangers.clockwiseTurnOf(facing).toFloat()))
        poseStack.translate(-MIDDLE, -MIDDLE, -MIDDLE)
    }

    private companion object {
        /** Six pixels square: two side by side fill the space between the legs. */
        const val ITEM_SCALE = 6.0f / 16.0f

        const val UNITS_PER_BLOCK = 16.0
        const val MIDDLE = 0.5f
        const val NO_OUTLINE = 0
    }
}
