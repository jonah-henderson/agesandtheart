package co.voik.agesandtheart.client

import co.voik.agesandtheart.optics.HoldsAMirror
import co.voik.agesandtheart.optics.MirrorTurning
import co.voik.agesandtheart.optics.OpticPiece
import co.voik.agesandtheart.optics.ReflectorBlock
import co.voik.agesandtheart.optics.ShinesABeam
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.client.renderer.block.BlockModelResolver
import net.minecraft.client.renderer.block.model.BlockDisplayContext
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

class OpticMirrorRenderState : BeamRenderState() {
    val mirror = BlockModelRenderState()
    val lens = BlockModelRenderState()
    var mirrorTurn: Quaternionf = Quaternionf()
    var lensTurn: Quaternionf? = null
}

/**
 * A reflector, splitter or merger's mirror plane, standing at 45 degrees between its two faces inside the open frame the
 * block's own model is, and easing round when they change ([MirrorTurning]); on a splitter or merger, the lens on its
 * straight face; and a merger's beam.
 */
class OpticMirrorRenderer<T>(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<T, OpticMirrorRenderState> where T : BlockEntity, T : HoldsAMirror {

    private val blockModels: BlockModelResolver = context.blockModelResolver()

    override fun createRenderState(): OpticMirrorRenderState = OpticMirrorRenderState()

    override fun extractRenderState(
        holder: T,
        state: OpticMirrorRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(holder, state, partialTicks, cameraPosition, breakProgress)
        if (holder is ShinesABeam) BeamDrawing.extract(holder, holder.level, state, partialTicks) else state.segments = emptyList()
        val blockState = holder.blockState
        blockModels.update(state.mirror, blockState.setValue(ReflectorBlock.PIECE, OpticPiece.MIRROR), MIRROR)
        blockModels.update(state.lens, blockState.setValue(ReflectorBlock.PIECE, OpticPiece.LENS), LENS)
        val faces = holder.faces
        state.mirrorTurn = holder.turning.orientationNow(faces, System.nanoTime())
        state.lensTurn = if (holder.isHalfSilvered) MirrorTurning.lensOrientationOf(faces.first) else null
    }

    override fun submit(state: OpticMirrorRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        BeamDrawing.submit(state, poseStack, collector, camera.pos)
        submitTurned(state.mirror, state.mirrorTurn, state, poseStack, collector)
        state.lensTurn?.let { submitTurned(state.lens, it, state, poseStack, collector) }
    }

    private fun submitTurned(
        model: BlockModelRenderState,
        turn: Quaternionf,
        state: OpticMirrorRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        poseStack.pushPose()
        poseStack.translate(MIDDLE, MIDDLE, MIDDLE)
        poseStack.rotate(turn)
        poseStack.translate(-MIDDLE, -MIDDLE, -MIDDLE)
        model.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
        poseStack.popPose()
    }

    /** A merger's beam reaches well past its block. */
    override fun shouldRenderOffScreen(): Boolean = true

    private companion object {
        const val MIDDLE = 0.5f
        const val NO_OUTLINE = 0
        val MIRROR: BlockDisplayContext = BlockDisplayContext.create()
        val LENS: BlockDisplayContext = BlockDisplayContext.create()
    }
}
