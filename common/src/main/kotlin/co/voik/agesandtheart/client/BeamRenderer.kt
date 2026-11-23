package co.voik.agesandtheart.client

import co.voik.agesandtheart.optics.BeamSegment
import co.voik.agesandtheart.optics.ShinesABeam
import co.voik.ephemeris.Rgba
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4fc
import kotlin.math.sin

open class BeamRenderState : BlockEntityRenderState() {
    var segments: List<BeamSegment> = emptyList()
    var animationTime: Float = 0f
}

/**
 * A beam, segment by segment, as light: ribbons turned to face the camera, bright down the middle and fading to nothing
 * at their edges, added over what is behind them ([AgeRenderTypes.lightThroughFog]).
 *
 * A laser is a white-hot core inside a glow of its colour, shimmering a little, with a flare where it leaves its source
 * and wherever it ends or turns. A collector's diffuse light is a wide, faint shaft with no core, since it is not
 * focused.
 */
object BeamDrawing {
    private const val MIDDLE = 0.5
    private const val ANIMATION_CYCLE = 40L

    private const val LASER_CORE_HALF_WIDTH = 0.035
    private const val LASER_GLOW_HALF_WIDTH = 0.16
    private const val LASER_CORE_ALPHA = 1.0f
    private const val LASER_GLOW_ALPHA = 0.6f

    /** How much of the way to white a laser's core is: hot at the middle, its colour in the glow. */
    private const val CORE_WHITENESS = 0.65f
    private const val FLARE_RADIUS = 0.22
    private const val FLARE_ALPHA = 0.8f

    /** A laser's shimmer: how far its brightness dips, and how fast it runs, in turns per animation cycle. */
    private const val SHIMMER_DEPTH = 0.15f
    private const val SHIMMER_TURNS = 3.0

    private const val DIFFUSE_HALF_WIDTH = 0.42
    private const val DIFFUSE_INNER_HALF_WIDTH = 0.2
    private const val DIFFUSE_ALPHA = 0.32f
    private const val DIFFUSE_INNER_ALPHA = 0.3f

    /** Below this the camera is looking straight along a segment, which then has no width to turn toward it. */
    private const val END_ON = 1.0e-6

    fun extract(shining: ShinesABeam, level: Level?, state: BeamRenderState, partialTicks: Float) {
        state.segments = shining.path.segments
        val gameTime = level?.gameTime ?: 0L
        state.animationTime = Math.floorMod(gameTime, ANIMATION_CYCLE).toFloat() + partialTicks
    }

    fun submit(state: BeamRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, cameraPosition: Vec3) {
        if (state.segments.isEmpty()) return
        val origin = Vec3.atLowerCornerOf(state.blockPos)
        val cameraFromOrigin = cameraPosition.subtract(origin)
        val shimmer = shimmerAt(state.animationTime)
        collector.submitCustomGeometry(poseStack, AgeRenderTypes.lightThroughFog) { pose, buffer ->
            for (segment in state.segments) {
                val (start, end) = endsOf(segment, origin)
                val side = sideFacing(start, end, cameraFromOrigin) ?: continue
                val colour = Rgba.of(segment.colour)
                if (segment.diffuse) {
                    ribbon(pose.pose(), buffer, start, end, side.scale(DIFFUSE_HALF_WIDTH), colour, DIFFUSE_ALPHA)
                    ribbon(pose.pose(), buffer, start, end, side.scale(DIFFUSE_INNER_HALF_WIDTH), colour, DIFFUSE_INNER_ALPHA)
                } else {
                    laserRibbons(pose.pose(), buffer, start, end, side, colour, shimmer)
                }
            }
        }
        for (segment in state.segments.filterNot { it.diffuse }) {
            val (start, end) = endsOf(segment, origin)
            flares(poseStack, collector, listOf(start, end), Rgba.of(segment.colour), shimmer, cameraFromOrigin)
        }
    }

    /**
     * One laser from [start] to [end], neither on a block's axis: both measured from the pose's origin, with
     * [cameraFromOrigin] the camera from there.
     */
    fun submitLaser(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        start: Vec3,
        end: Vec3,
        colour: Rgba,
        animationTime: Float,
        cameraFromOrigin: Vec3,
    ) {
        val side = sideFacing(start, end, cameraFromOrigin) ?: return
        val shimmer = shimmerAt(animationTime)
        collector.submitCustomGeometry(poseStack, AgeRenderTypes.lightThroughFog) { pose, buffer ->
            laserRibbons(pose.pose(), buffer, start, end, side, colour, shimmer)
        }
        flares(poseStack, collector, listOf(start, end), colour, shimmer, cameraFromOrigin)
    }

    private fun shimmerAt(animationTime: Float): Float =
        1f - SHIMMER_DEPTH * (0.5f + 0.5f * sin(animationTime / ANIMATION_CYCLE * SHIMMER_TURNS * TURN).toFloat())

    /** The unit sideways from a run, square to it and to the camera, or null where the camera looks straight along it. */
    private fun sideFacing(start: Vec3, end: Vec3, cameraFromOrigin: Vec3): Vec3? {
        val towardCamera = cameraFromOrigin.subtract(start.add(end).scale(MIDDLE))
        val across = end.subtract(start).cross(towardCamera)
        return if (across.lengthSqr() < END_ON) null else across.normalize()
    }

    private fun laserRibbons(pose: Matrix4fc, buffer: VertexConsumer, start: Vec3, end: Vec3, side: Vec3, colour: Rgba, shimmer: Float) {
        ribbon(pose, buffer, start, end, side.scale(LASER_GLOW_HALF_WIDTH), colour, LASER_GLOW_ALPHA * shimmer)
        ribbon(pose, buffer, start, end, side.scale(LASER_CORE_HALF_WIDTH), colour.lerp(WHITE, CORE_WHITENESS), LASER_CORE_ALPHA)
    }

    private fun flares(
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        points: List<Vec3>,
        colour: Rgba,
        shimmer: Float,
        cameraFromOrigin: Vec3,
    ) {
        val flare = colour.lerp(WHITE, CORE_WHITENESS).let { Rgba(it.red, it.green, it.blue, FLARE_ALPHA * shimmer) }
        for (point in points) {
            poseStack.pushPose()
            poseStack.translate(point.x, point.y, point.z)
            AddedLight.halo(collector, poseStack, cameraFromOrigin.subtract(point), flare, FLARE_RADIUS, AgeRenderTypes.lightThroughFog)
            poseStack.popPose()
        }
    }

    /** Where [segment] starts and ends, from the corner of the block drawing it at [origin]. */
    private fun endsOf(segment: BeamSegment, origin: Vec3): Pair<Vec3, Vec3> {
        val direction = Vec3(segment.direction.stepX.toDouble(), segment.direction.stepY.toDouble(), segment.direction.stepZ.toDouble())
        val start = Vec3.atCenterOf(segment.from).subtract(origin).add(direction.scale(segment.shiftedStart.toDouble()))
        return start to start.add(direction.scale(segment.length.toDouble()))
    }

    /**
     * A ribbon from [start] to [end], [halfWidth] out each side: [alpha] down the middle and none at the edges, wound
     * both ways so it shows from either side.
     */
    private fun ribbon(pose: Matrix4fc, buffer: VertexConsumer, start: Vec3, end: Vec3, halfWidth: Vec3, colour: Rgba, alpha: Float) {
        fun vertex(at: Vec3, vertexAlpha: Float) {
            buffer.addVertex(pose, at.x.toFloat(), at.y.toFloat(), at.z.toFloat()).setColor(colour.red, colour.green, colour.blue, vertexAlpha)
        }
        fun quad(nearSide: Vec3, farSide: Vec3) {
            val corners = listOf(start.add(nearSide) to 0f, end.add(nearSide) to 0f, end.add(farSide) to alpha, start.add(farSide) to alpha)
            for (winding in listOf(corners, corners.reversed())) {
                vertex(winding[0].first, winding[0].second); vertex(winding[1].first, winding[1].second); vertex(winding[2].first, winding[2].second)
                vertex(winding[0].first, winding[0].second); vertex(winding[2].first, winding[2].second); vertex(winding[3].first, winding[3].second)
            }
        }
        quad(halfWidth, Vec3.ZERO)
        quad(halfWidth.reverse(), Vec3.ZERO)
    }

    private val WHITE = Rgba(1f, 1f, 1f)
    private const val TURN = 2.0 * Math.PI
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
        BeamDrawing.submit(state, poseStack, collector, camera.pos)
    }

    /** A beam reaches well past its block, so it is drawn whether or not that block is in view. */
    override fun shouldRenderOffScreen(): Boolean = true
}
