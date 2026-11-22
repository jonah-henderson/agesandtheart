package co.voik.agesandtheart.client

import co.voik.agesandtheart.optics.AstriteEmitterBlockEntity
import co.voik.agesandtheart.optics.BeamSegment
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BeaconRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

class BeamRenderState : BlockEntityRenderState() {
    var segments: List<BeamSegment> = emptyList()
    var animationTime: Float = 0f
}

/**
 * An emitter's beam, segment by segment: vanilla's beacon beam, narrower, laid along each segment's direction rather
 * than straight up.
 */
class BeamRenderer : BlockEntityRenderer<AstriteEmitterBlockEntity, BeamRenderState> {

    override fun createRenderState(): BeamRenderState = BeamRenderState()

    override fun extractRenderState(
        emitter: AstriteEmitterBlockEntity,
        state: BeamRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(emitter, state, partialTicks, cameraPosition, breakProgress)
        state.segments = emitter.path.segments
        val gameTime = emitter.level?.gameTime ?: 0L
        state.animationTime = Math.floorMod(gameTime, ANIMATION_CYCLE).toFloat() + partialTicks
    }

    override fun submit(state: BeamRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        for (segment in state.segments) {
            val wholeBlocks = ceil(segment.length).toInt().coerceAtLeast(1)
            poseStack.pushPose()
            poseStack.translate(
                (segment.from.x - state.blockPos.x) + MIDDLE,
                (segment.from.y - state.blockPos.y) + MIDDLE,
                (segment.from.z - state.blockPos.z) + MIDDLE,
            )
            poseStack.rotate(segment.direction.rotation)
            poseStack.translate(0f, segment.shiftedStart, 0f)
            poseStack.scale(1f, segment.length / wholeBlocks, 1f)
            // The beacon's beam is laid from the middle of a block's floor, so it is set down half a block across.
            poseStack.translate(-MIDDLE, 0f, -MIDDLE)
            BeaconRenderer.submitBeaconBeam(
                poseStack,
                collector,
                BeaconRenderer.BEAM_LOCATION,
                1f,
                state.animationTime,
                0,
                wholeBlocks,
                segment.colour,
                SOLID_RADIUS,
                GLOW_RADIUS,
            )
            poseStack.popPose()
        }
    }

    /** A beam reaches well past its emitter's block, so it is drawn whether or not that block is in view. */
    override fun shouldRenderOffScreen(): Boolean = true

    private companion object {
        const val MIDDLE = 0.5f
        const val ANIMATION_CYCLE = 40L
        const val SOLID_RADIUS = 0.06f
        const val GLOW_RADIUS = 0.1f
    }
}
