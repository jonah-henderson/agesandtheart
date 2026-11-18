package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.ImageRecorderBlock
import co.voik.agesandtheart.imaging.ImageRecorderBlockEntity
import co.voik.agesandtheart.imaging.ImagingContent
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.Vec3

class MountedRecorderRenderState : BlockEntityRenderState() {
    var recorder: MovingBlockRenderState? = null
    var yaw = 0.0f
    var pitch = 0.0f
}

/**
 * The recorder on a tripod's head, drawn as the recorder on the ground is, by its own block model facing north, and
 * turned about the middle of its base to its aim: as a piston draws the block it moves, so the model is the one
 * Jonah drew, lit as the world is where it stands. A recorder on the ground is its block's model and draws nothing here.
 */
class MountedRecorderRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ImageRecorderBlockEntity, MountedRecorderRenderState> {

    override fun createRenderState() = MountedRecorderRenderState()

    override fun extractRenderState(
        recorder: ImageRecorderBlockEntity,
        state: MountedRecorderRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(recorder, state, partialTicks, cameraPosition, breakProgress)
        state.recorder = null
        val level = recorder.level as? ClientLevel ?: return
        if (!recorder.blockState.getValue(ImageRecorderBlock.MOUNTED)) return
        val headPos = recorder.blockPos.above()
        state.recorder = MovingBlockRenderState().apply {
            randomSeedPos = recorder.blockPos
            blockPos = headPos
            blockState = AS_ON_THE_GROUND
            biome = level.getBiome(headPos)
            cardinalLighting = level.cardinalLighting()
            lightEngine = level.lightEngine
        }
        state.yaw = recorder.aimYaw
        state.pitch = recorder.aimPitch
    }

    override fun submit(state: MountedRecorderRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val recorder = state.recorder ?: return
        poseStack.pushPose()
        // From the block's corner up to the tripod's head; the moving block draws its model here and is lit from the head's block.
        poseStack.translate(HALF_A_BLOCK, ImageRecorderBlock.MOUNTED_PIVOT_HEIGHT, HALF_A_BLOCK)
        // The model faces north, which is a yaw of half a turn; the pitch tips it about its own sideways axis.
        poseStack.rotateDegrees(Axis.YP, HALF_A_TURN - state.yaw)
        poseStack.rotateDegrees(Axis.XP, -state.pitch)
        poseStack.translate(-HALF_A_BLOCK, 0.0, -HALF_A_BLOCK)
        collector.submitMovingBlock(poseStack, recorder, NO_OUTLINE)
        poseStack.popPose()
    }

    private companion object {
        const val HALF_A_BLOCK = 0.5
        const val HALF_A_TURN = 180.0f
        const val NO_OUTLINE = 0

        /** The recorder as it stands on the ground facing north, unturned: the model that is turned to the aim. */
        val AS_ON_THE_GROUND by lazy { ImagingContent.IMAGE_RECORDER_BLOCK.defaultBlockState() }
    }
}
