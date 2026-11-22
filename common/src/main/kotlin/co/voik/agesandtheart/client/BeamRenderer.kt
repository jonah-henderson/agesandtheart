package co.voik.agesandtheart.client

import co.voik.agesandtheart.optics.BeamSegment
import co.voik.agesandtheart.optics.ShinesABeam
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BeaconRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

open class BeamRenderState : BlockEntityRenderState() {
    var segments: List<BeamSegment> = emptyList()
    var animationTime: Float = 0f
}

/**
 * A beam, segment by segment: vanilla's beacon beam laid along each segment's direction rather than straight up, narrow
 * for a laser and wide and faint for a collector's diffuse light.
 */
object BeamDrawing {
    private const val MIDDLE = 0.5f
    private const val ANIMATION_CYCLE = 40L
    private const val LASER_SOLID_RADIUS = 0.06f
    private const val LASER_GLOW_RADIUS = 0.1f

    /** The beacon's solid core divides by its radius, so a diffuse beam keeps a hair of one. */
    private const val DIFFUSE_SOLID_RADIUS = 0.01f
    private const val DIFFUSE_GLOW_RADIUS = 0.3f

    fun extract(shining: ShinesABeam, level: Level?, state: BeamRenderState, partialTicks: Float) {
        state.segments = shining.path.segments
        val gameTime = level?.gameTime ?: 0L
        state.animationTime = Math.floorMod(gameTime, ANIMATION_CYCLE).toFloat() + partialTicks
    }

    fun submit(state: BeamRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
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
                if (segment.diffuse) DIFFUSE_SOLID_RADIUS else LASER_SOLID_RADIUS,
                if (segment.diffuse) DIFFUSE_GLOW_RADIUS else LASER_GLOW_RADIUS,
            )
            poseStack.popPose()
        }
    }
}

/** A block entity's beam and nothing else: the emitter's laser. */
class BeamRenderer<T> : BlockEntityRenderer<T, BeamRenderState> where T : BlockEntity, T : ShinesABeam {

    override fun createRenderState(): BeamRenderState = BeamRenderState()

    override fun extractRenderState(
        shining: T,
        state: BeamRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(shining, state, partialTicks, cameraPosition, breakProgress)
        BeamDrawing.extract(shining, shining.level, state, partialTicks)
    }

    override fun submit(state: BeamRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        BeamDrawing.submit(state, poseStack, collector)
    }

    /** A beam reaches well past its block, so it is drawn whether or not that block is in view. */
    override fun shouldRenderOffScreen(): Boolean = true
}
