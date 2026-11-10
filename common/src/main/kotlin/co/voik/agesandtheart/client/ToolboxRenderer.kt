package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.ToolboxBlock
import co.voik.agesandtheart.content.ToolboxBlockEntity
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
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.Vec3

class ToolboxRenderState : BlockEntityRenderState() {
    val shown: List<ItemStackRenderState> = List(ToolboxContents.SHOWN) { ItemStackRenderState() }
    val stances: Array<ToolboxContents.Stance> = Array(ToolboxContents.SHOWN) { ToolboxContents.Stance.TOOL }
    var facing: Direction = Direction.NORTH
}

/** What a placed toolbox holds, standing in it ([ToolboxContents]). The box itself is its blockstate's model. */
class ToolboxRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ToolboxBlockEntity, ToolboxRenderState> {

    private val itemModelResolver: ItemModelResolver = context.itemModelResolver()

    override fun createRenderState(): ToolboxRenderState = ToolboxRenderState()

    override fun extractRenderState(
        toolbox: ToolboxBlockEntity,
        state: ToolboxRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(toolbox, state, partialTicks, cameraPosition, breakProgress)
        state.facing = toolbox.blockState.getValue(ToolboxBlock.FACING)
        val shown = ToolboxContents.shownOf((0..<toolbox.containerSize).map(toolbox::getItem))
        val seed = toolbox.blockPos.asLong().toInt()
        for ((index, itemState) in state.shown.withIndex()) {
            val stack = shown.getOrNull(index)
            if (stack == null) {
                itemState.clear()
                continue
            }
            itemModelResolver.updateForTopItem(itemState, stack, ItemDisplayContext.FIXED, toolbox.level, null, seed + index)
            state.stances[index] = ToolboxContents.Stance.of(stack)
        }
    }

    override fun submit(state: ToolboxRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        for ((index, itemState) in state.shown.withIndex()) {
            if (itemState.isEmpty) continue
            poseStack.pushPose()
            turnAsTheBlockIs(state.facing, poseStack)
            ToolboxContents.submitStanding(index, itemState, state.stances[index], poseStack, collector, state.lightCoords, NO_OUTLINE)
            poseStack.popPose()
        }
    }

    /** As the blockstate turns the model, which faces north unturned: a quarter clockwise from above a step. */
    private fun turnAsTheBlockIs(facing: Direction, poseStack: PoseStack) {
        val clockwise = (facing.toYRot() + HALF_TURN) % FULL_TURN
        poseStack.translate(MIDDLE, MIDDLE, MIDDLE)
        poseStack.rotate(Axis.YP.rotationDegrees(-clockwise))
        poseStack.translate(-MIDDLE, -MIDDLE, -MIDDLE)
    }

    private companion object {
        const val MIDDLE = 0.5f
        const val HALF_TURN = 180.0f
        const val FULL_TURN = 360.0f
        const val NO_OUTLINE = 0
    }
}
