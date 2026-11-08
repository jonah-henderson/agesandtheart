package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Tornado
import co.voik.agesandtheart.compat.ONLY_VERTEX_BINDING
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.oit.OitPipelineSet
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** What the renderer needs of a tornado, taken off it before drawing. */
class TornadoRenderState : EntityRenderState() {
    var footHalfWidth = 0.0f
    var descended = 0.0f
    var roped = 0.0f
    var heading = 0.0f
    var phase = 0.0f
    var time = 0.0f
    var length = 0.0f
}

/**
 * A tornado: a wall cloud under vanilla's cloud height ([Tornado.CLOUD_Y]), a funnel hanging from it to the ground, and a skirt of dust
 * at its foot (`notes/wind-plan.md` §4).
 *
 * **Minecraft's own shapes**: the funnel is a stack of squares narrowing downward, each joined to the next corner to
 * corner, so it is a twisted, faceted tube. Each square turns with time and sits a little off the one above, so the
 * funnel flexes and sways, and the top leans ahead of the foot along its travel. That is all worked out here, a few
 * hundred vertices a frame; what reads as the air going round is `tornado.fsh`, bands scrolling across the faces.
 *
 * Drawn as the sand column is — one render type, innermost first in one submission, writing depth, culling declined
 * — and for the same reasons, which `SandColumnRenderer` carries.
 */
class TornadoRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Tornado, TornadoRenderState>(context) {

    override fun createRenderState(): TornadoRenderState = TornadoRenderState()

    override fun extractRenderState(entity: Tornado, state: TornadoRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.footHalfWidth = entity.footHalfWidth
        state.descended = entity.descended
        state.roped = entity.roped
        state.heading = entity.yRot
        state.phase = ((entity.id * PHASE_STEP) and PHASE_MASK).toFloat() / PHASE_WHOLE
        state.time = entity.tickCount + partialTicks
        state.length = (Tornado.CLOUD_Y - state.y).toFloat()
    }

    override fun affectedByCulling(entity: Tornado): Boolean = false

    override fun submit(
        state: TornadoRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (state.footHalfWidth <= NOTHING || state.length <= WALL_BELOW) return
        val funnel = Funnel.of(state)
        collector.submitCustomGeometry(poseStack, TORNADO) { pose, buffer ->
            funnel.shell(pose, buffer, state, CORE_SHARE, CORE, CORE_SOLIDITY)
            funnel.shell(pose, buffer, state, WHOLE, OUTER, OUTER_SOLIDITY)
            dust(pose, buffer, state, funnel)
            wallCloud(pose, buffer, state)
        }
        super.submit(state, poseStack, collector, camera)
    }

    /**
     * The funnel's squares, top to bottom, at this moment: where each stands, how wide, and how far it has turned.
     * Positions are relative to the foot, which is where the entity stands.
     */
    private class Funnel(
        val centreX: FloatArray,
        val centreZ: FloatArray,
        val heightOf: FloatArray,
        val halfWidth: FloatArray,
        val turned: FloatArray,
        val top: Float,
        val fade: Float,
    ) {
        fun shell(pose: PoseStack.Pose, buffer: VertexConsumer, state: TornadoRenderState, share: Float, which: Float, solidity: Float) {
            for (ring in 0..<RINGS) {
                for (side in 0..<SIDES) {
                    val next = (side + 1) % SIDES
                    corner(pose, buffer, state, ring, side, share, side.toFloat(), which, solidity)
                    corner(pose, buffer, state, ring, next, share, side + 1.0f, which, solidity)
                    corner(pose, buffer, state, ring + 1, next, share, side + 1.0f, which, solidity)
                    corner(pose, buffer, state, ring + 1, side, share, side.toFloat(), which, solidity)
                }
            }
        }

        private fun corner(
            pose: PoseStack.Pose,
            buffer: VertexConsumer,
            state: TornadoRenderState,
            ring: Int,
            side: Int,
            share: Float,
            around: Float,
            which: Float,
            solidity: Float,
        ) {
            val angle = turned[ring] + QUARTER_TURN * side + EIGHTH_TURN
            val reach = halfWidth[ring] * share * ROOT_TWO
            val atX = centreX[ring] + cos(angle) * reach
            val atZ = centreZ[ring] - sin(angle) * reach
            buffer.addVertex(pose, atX, heightOf[ring], atZ)
                .setUv(around / SIDES, top - heightOf[ring])
                .setLight(state.lightCoords)
                // r: this tornado's own phase. g: which part. b: how far down the funnel, nought at the cloud and one
                // at the foot. a: how solid, before the bands thin it.
                .setColor(state.phase, which, ring.toFloat() / RINGS, solidity * fade)
        }

        companion object {
            fun of(state: TornadoRenderState): Funnel {
                val length = state.length
                val reach = length * state.descended
                val foot = Mth.lerp(state.roped, state.footHalfWidth, ROPE_FOOT)
                val top = Mth.lerp(state.roped, (state.footHalfWidth * TOP_PER_FOOT).coerceIn(NARROWEST_TOP, WIDEST_TOP), ROPE_TOP)
                val tip = Mth.lerp(state.descended * state.descended, TIP, foot)
                val heading = state.heading * Mth.DEG_TO_RAD
                val forwardX = -sin(heading)
                val forwardZ = cos(heading)
                val lean = (LEAN + ROPE_LEAN * state.roped) * length
                val sway = (SWAY + ROPE_SWAY * state.roped) * length
                val buried = BURIED_BY * state.descended
                val centreX = FloatArray(RINGS + 1)
                val centreZ = FloatArray(RINGS + 1)
                val heightOf = FloatArray(RINGS + 1)
                val halfWidth = FloatArray(RINGS + 1)
                val turned = FloatArray(RINGS + 1)
                for (ring in 0..RINGS) {
                    val down = ring.toFloat() / RINGS
                    val up = 1.0f - down
                    heightOf[ring] = length - down * (reach + buried)
                    halfWidth[ring] = tip + (top - tip) * up.pow(FLARE)
                    val ahead = lean * up.pow(LEAN_CURVE)
                    val swaying = sway * sin(state.time * SWAY_RATE + down * SWAY_WAVES + state.phase * FULL_TURN) * down * up * BOTH_ENDS
                    centreX[ring] = forwardX * ahead + forwardZ * swaying
                    centreZ[ring] = forwardZ * ahead - forwardX * swaying
                    turned[ring] = state.time * SPIN + down * TWIST + sin(state.time * WOBBLE_RATE + down * WOBBLE_WAVES) * WOBBLE
                }
                val fade = 1.0f - Mth.smoothstep(((state.roped - BREAKS_UP_FROM) / (1.0f - BREAKS_UP_FROM)).coerceIn(0.0f, 1.0f))
                return Funnel(centreX, centreZ, heightOf, halfWidth, turned, length, fade)
            }
        }
    }

    /** The dust whirled up at the foot: a short flaring skirt, spinning up as the funnel comes down to it. */
    private fun dust(pose: PoseStack.Pose, buffer: VertexConsumer, state: TornadoRenderState, funnel: Funnel) {
        val rising = Mth.smoothstep(((state.descended - DUST_FROM) / (1.0f - DUST_FROM)).coerceIn(0.0f, 1.0f))
        val solidity = DUST_SOLIDITY * rising * funnel.fade
        if (solidity <= NOTHING) return
        val foot = funnel.halfWidth[RINGS]
        val footX = funnel.centreX[RINGS]
        val footZ = funnel.centreZ[RINGS]
        val turned = state.time * DUST_SPIN
        for (level in 0..<DUST_LEVELS) {
            val lowShare = level.toFloat() / DUST_LEVELS
            val highShare = (level + 1).toFloat() / DUST_LEVELS
            for (side in 0..<SIDES) {
                fun skirt(share: Float, corner: Int, around: Float) {
                    val angle = turned + share * DUST_TWIST + QUARTER_TURN * corner + EIGHTH_TURN
                    val reach = foot * (DUST_FOOT + (DUST_TOP - DUST_FOOT) * share) * ROOT_TWO
                    buffer.addVertex(pose, footX + cos(angle) * reach, -BURIED_BY + share * (DUST_HEIGHT + BURIED_BY), footZ - sin(angle) * reach)
                        .setUv(around / SIDES, (1.0f - share) * DUST_HEIGHT)
                        .setLight(state.lightCoords)
                        .setColor(state.phase, DUST, share, solidity)
                }
                val next = (side + 1) % SIDES
                skirt(lowShare, side, side.toFloat())
                skirt(lowShare, next, side + 1.0f)
                skirt(highShare, next, side + 1.0f)
                skirt(highShare, side, side.toFloat())
            }
        }
    }

    /** The lowered, slowly turning cloud the funnel hangs from: a squat square narrowing downward, and its floor. */
    private fun wallCloud(pose: PoseStack.Pose, buffer: VertexConsumer, state: TornadoRenderState) {
        val forming = Mth.smoothstep((state.time / WALL_FORMS_OVER).coerceIn(0.0f, 1.0f))
        val solidity = WALL_SOLIDITY * forming * (1.0f - WALL_THINS_BY * state.roped)
        if (solidity <= NOTHING) return
        val turned = state.time * WALL_SPIN
        val top = state.length + WALL_ABOVE
        val bottom = state.length - WALL_BELOW
        fun at(height: Float, half: Float, corner: Int, around: Float, down: Float, which: Float = WALL) {
            val angle = turned + QUARTER_TURN * corner + EIGHTH_TURN
            buffer.addVertex(pose, cos(angle) * half * ROOT_TWO, height, -sin(angle) * half * ROOT_TWO)
                .setUv(around, down)
                .setLight(state.lightCoords)
                .setColor(state.phase, which, 0.0f, solidity)
        }
        for (side in 0..<SIDES) {
            val next = (side + 1) % SIDES
            at(bottom, WALL_BOTTOM_HALF, side, side.toFloat() / SIDES, top - bottom)
            at(bottom, WALL_BOTTOM_HALF, next, (side + 1.0f) / SIDES, top - bottom)
            at(top, WALL_TOP_HALF, next, (side + 1.0f) / SIDES, 0.0f)
            at(top, WALL_TOP_HALF, side, side.toFloat() / SIDES, 0.0f)
        }
        // Its floor, which is most of what is seen of it from the ground.
        for (corner in 0..<SIDES) at(bottom, WALL_BOTTOM_HALF, corner, corner.toFloat() / SIDES, top - bottom)
    }

    companion object {
        private const val RINGS = 28
        private const val SIDES = 4

        /** The top flares to a little over twice the foot, forty to sixty across. */
        private const val TOP_PER_FOOT = 2.2f
        private const val NARROWEST_TOP = 20.0f
        private const val WIDEST_TOP = 30.0f
        /** How sharply it flares near the cloud: higher keeps it narrow for longer. */
        private const val FLARE = 3.0f
        /** The tip's half-width as it reaches down, before it has found the ground. */
        private const val TIP = 1.5f
        /** A roped-out funnel: a rope at the foot, a little wider at the cloud. */
        private const val ROPE_FOOT = 1.2f
        private const val ROPE_TOP = 6.0f
        /** It breaks up over the last of its roping out. */
        private const val BREAKS_UP_FROM = 0.75f

        /** How far the top leans ahead of the foot, as a share of the length; a rope leans far more. */
        private const val LEAN = 0.1f
        private const val ROPE_LEAN = 0.3f
        private const val LEAN_CURVE = 1.5f
        /** How far it sways from side to side, as a share of the length, and how fast. */
        private const val SWAY = 0.025f
        private const val ROPE_SWAY = 0.08f
        private const val SWAY_RATE = 0.03f
        private const val SWAY_WAVES = 4.0f
        private const val BOTH_ENDS = 4.0f

        /** How fast the squares turn, in radians a tick: anticlockwise from above, a turn in about five seconds. */
        private const val SPIN = 0.06f
        /** How far the squares are turned from the top to the foot, which joins them into a twist. */
        private const val TWIST = 2.5f
        private const val WOBBLE = 0.25f
        private const val WOBBLE_RATE = 0.05f
        private const val WOBBLE_WAVES = 6.0f

        private const val CORE_SHARE = 0.55f
        private const val WHOLE = 1.0f
        private const val CORE_SOLIDITY = 1.0f
        private const val OUTER_SOLIDITY = 0.85f

        /** The dust rises once the funnel is nearly down, to about this high, and twice the foot wide at its top. */
        private const val DUST_FROM = 0.6f
        private const val DUST_HEIGHT = 10.0f
        private const val DUST_FOOT = 1.2f
        private const val DUST_TOP = 2.0f
        private const val DUST_LEVELS = 3
        private const val DUST_SPIN = 0.1f
        private const val DUST_TWIST = 0.8f
        private const val DUST_SOLIDITY = 0.8f

        /** About a hundred and fifty across at the cloud, narrowing to a hundred and ten at its floor ten below. */
        private const val WALL_TOP_HALF = 75.0f
        private const val WALL_BOTTOM_HALF = 55.0f
        private const val WALL_ABOVE = 2.0f
        private const val WALL_BELOW = Tornado.WALL_CLOUD_BELOW.toFloat()
        private const val WALL_SPIN = 0.004f
        private const val WALL_FORMS_OVER = 100.0f
        private const val WALL_THINS_BY = 0.7f
        private const val WALL_SOLIDITY = 0.9f

        /** Which part a vertex belongs to, as the shader reads it — see `tornado.fsh`. */
        private const val OUTER = 0.0f
        private const val CORE = 0.25f
        private const val DUST = 0.5f
        private const val WALL = 0.75f

        private const val BURIED_BY = 4.0f
        private const val NOTHING = 0.0f
        private const val ROOT_TWO = 1.4142135f
        private const val QUARTER_TURN = Mth.HALF_PI
        private const val EIGHTH_TURN = Mth.HALF_PI / 2.0f
        private const val FULL_TURN = Mth.TWO_PI

        private const val PHASE_STEP = 2654435761L
        private const val PHASE_MASK = 0xFFFFL
        private const val PHASE_WHOLE = 65536.0f

        private const val NAMESPACE = "agesandtheart"

        /** As `SandColumnRenderer`'s, which says why each line is there. */
        private val SHARED: RenderPipeline.Snippet = RenderPipeline.builder()
            .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "tornado"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "tornado"))
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                    .build(),
            )
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build(),
            )
            .withCull(false)
            .withVertexBinding(ONLY_VERTEX_BINDING, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .buildSnippet()

        private val PIPELINE: RenderPipeline = RenderPipeline.builder(SHARED)
            .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/tornado"))
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .build()

        // NeoForge deprecates this for an extension of its own, which common cannot call.
        @Suppress("DEPRECATION")
        private val SORTED_PIPELINES: OitPipelineSet =
            OitPipelineSet.builder("tornado", RenderPipeline.builder(SHARED)).build()

        private val TORNADO: RenderType = RenderType.create(
            "tornado",
            RenderSetup.builder(PIPELINE)
                .useLightmap()
                .setOitPipelines(SORTED_PIPELINES)
                .createRenderSetup(),
        )
    }
}
