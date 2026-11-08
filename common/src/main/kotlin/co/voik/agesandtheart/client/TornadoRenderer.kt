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
import net.minecraft.core.BlockPos
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.oit.OitPipelineSet
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.level.LightLayer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the renderer needs of a tornado, taken off it before drawing. */
class TornadoRenderState : EntityRenderState() {
    var footHalfWidth = 0.0f
    var brewed = 0.0f
    var descended = 0.0f
    var roped = 0.0f
    var heading = 0.0f
    var phase = 0.0f
    var time = 0.0f
    var length = 0.0f
}

/**
 * A tornado: an overcast of blocky cloud just under vanilla's ([Tornado.OVERCAST_FLOOR]), a funnel hanging from it to
 * the ground, and a skirt of dust at its foot (`notes/wind-plan.md` §4).
 *
 * **Minecraft's own shapes**: the funnel is a stack of square slabs narrowing downward, each turned a little from the
 * one above and set a little off it, so it is a stepped, twisted tower that flexes and sways, the top leaning ahead of
 * the foot along its travel. A translucent sleeve of streaks stands about the solid funnel. That is all worked out
 * here; what reads as the air going round is `tornado.fsh`, bands scrolling across the faces.
 *
 * Drawn as the sand column is — one render type, one submission, writing depth, culling declined, the inside before
 * the outside — and for the same reasons, which `SandColumnRenderer` carries. Everything but the sleeve is opaque, and
 * the sleeve's gaps are discarded rather than blended, since a translucent face writing depth hides the water behind
 * it; the sleeve thins away at both ends, where water and the overcast are behind it.
 */
class TornadoRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Tornado, TornadoRenderState>(context) {

    override fun createRenderState(): TornadoRenderState = TornadoRenderState()

    override fun extractRenderState(entity: Tornado, state: TornadoRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.footHalfWidth = entity.footHalfWidth
        state.brewed = entity.brewed
        state.descended = entity.descended
        state.roped = entity.roped
        state.heading = entity.yRot
        state.phase = ((entity.id * PHASE_STEP) and PHASE_MASK).toFloat() / PHASE_WHOLE
        state.time = entity.tickCount + partialTicks
        state.length = (Tornado.OVERCAST_FLOOR - COLLAR_DROP + HANGS_INTO_THE_OVERCAST - state.y).toFloat()
    }

    override fun affectedByCulling(entity: Tornado): Boolean = false

    // Lit from the top of its funnel, out in the open under the overcast: the foot eases after the ground and sits in it
    // as often as not, where the light is nought, so lighting by where it stands flickered it black.
    override fun getSkyLightLevel(entity: Tornado, position: BlockPos): Int =
        entity.level().getBrightness(LightLayer.SKY, inTheOpenOver(entity, position))

    override fun getBlockLightLevel(entity: Tornado, position: BlockPos): Int =
        entity.level().getBrightness(LightLayer.BLOCK, inTheOpenOver(entity, position))

    private fun inTheOpenOver(entity: Tornado, position: BlockPos): BlockPos =
        BlockPos(position.x, Mth.floor(Tornado.OVERCAST_FLOOR) - COLLAR_DROP, position.z)

    override fun submit(
        state: TornadoRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (state.footHalfWidth <= NOTHING) return
        val funnel = Funnel.of(state)
        val hangs = state.length > NOTHING && state.descended > NOTHING
        // The opaque parts first, so the sleeve blends over whatever of them is behind it.
        collector.submitCustomGeometry(poseStack, TORNADO) { pose, buffer ->
            if (hangs) {
                funnel.slabs(pose, buffer, state, WHOLE, FUNNEL, capped = true)
                dust(pose, buffer, state, funnel)
            }
            overcast(pose, buffer, state, funnel)
            if (hangs) funnel.slabs(pose, buffer, state, SLEEVE_SHARE, SLEEVE, capped = false)
        }
        super.submit(state, poseStack, collector, camera)
    }

    /**
     * The funnel's slabs, top to bottom, at this moment: where each stands, how wide, and how far it has turned, taken
     * at its middle, and the heights between them. Positions are relative to the foot, which is where the entity stands.
     */
    private class Funnel(
        val centreX: FloatArray,
        val centreZ: FloatArray,
        val halfWidth: FloatArray,
        val turned: FloatArray,
        val heightOf: FloatArray,
        val length: Float,
        val topHalfWidth: Float,
        val fade: Float,
    ) {
        /**
         * Each slab's four walls at [share] of the funnel's width, and with [capped] its underside too, which is what
         * shows where it overhangs the one below.
         */
        fun slabs(pose: PoseStack.Pose, buffer: VertexConsumer, state: TornadoRenderState, share: Float, which: Float, capped: Boolean) {
            for (slab in 0..<SLABS) {
                val upper = heightOf[slab]
                val lower = heightOf[slab + 1]
                for (side in 0..<SIDES) {
                    val next = (side + 1) % SIDES
                    corner(pose, buffer, state, slab, side, upper, side.toFloat(), share, which)
                    corner(pose, buffer, state, slab, next, upper, side + 1.0f, share, which)
                    corner(pose, buffer, state, slab, next, lower, side + 1.0f, share, which)
                    corner(pose, buffer, state, slab, side, lower, side.toFloat(), share, which)
                }
                if (capped) for (side in 0..<SIDES) corner(pose, buffer, state, slab, side, lower, side.toFloat(), share, which)
            }
        }

        private fun corner(
            pose: PoseStack.Pose,
            buffer: VertexConsumer,
            state: TornadoRenderState,
            slab: Int,
            side: Int,
            height: Float,
            around: Float,
            share: Float,
            which: Float,
        ) {
            val angle = turned[slab] + QUARTER_TURN * side + EIGHTH_TURN
            val reach = halfWidth[slab] * share * ROOT_TWO
            val atX = centreX[slab] + cos(angle) * reach
            val atZ = centreZ[slab] - sin(angle) * reach
            buffer.addVertex(pose, atX, height, atZ)
                .setUv(around / SIDES, length - height)
                .setLight(state.lightCoords)
                // r: this slab's own seed, for its bands. g: which part. b: how far down the funnel, nought at the cloud
                // and one at the foot. a: how much of it is left as it breaks up.
                .setColor(seedOf(state, slab, which), which, (length - height) / length, fade)
        }

        companion object {
            fun of(state: TornadoRenderState): Funnel {
                val length = state.length.coerceAtLeast(NOTHING)
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
                val centreX = FloatArray(SLABS)
                val centreZ = FloatArray(SLABS)
                val halfWidth = FloatArray(SLABS)
                val turned = FloatArray(SLABS)
                for (slab in 0..<SLABS) {
                    val down = (slab + HALF) / SLABS
                    val up = 1.0f - down
                    halfWidth[slab] = tip + (top - tip) * up.pow(FLARE)
                    val ahead = lean * up.pow(LEAN_CURVE)
                    val swaying = sway * sin(state.time * SWAY_RATE + down * SWAY_WAVES + state.phase * FULL_TURN) * down * up * BOTH_ENDS
                    centreX[slab] = forwardX * ahead + forwardZ * swaying
                    centreZ[slab] = forwardZ * ahead - forwardX * swaying
                    turned[slab] = state.time * SPIN + down * TWIST + sin(state.time * WOBBLE_RATE + down * WOBBLE_WAVES) * WOBBLE
                }
                val heightOf = FloatArray(SLABS + 1) { length - it.toFloat() / SLABS * (reach + buried) }
                val fade = 1.0f - Mth.smoothstep(((state.roped - BREAKS_UP_FROM) / (1.0f - BREAKS_UP_FROM)).coerceIn(0.0f, 1.0f))
                return Funnel(centreX, centreZ, halfWidth, turned, heightOf, length, top, fade)
            }
        }
    }

    /** The dust whirled up at the foot: a short flaring skirt, spinning up as the funnel comes down to it. */
    private fun dust(pose: PoseStack.Pose, buffer: VertexConsumer, state: TornadoRenderState, funnel: Funnel) {
        val rising = Mth.smoothstep(((state.descended - DUST_FROM) / (1.0f - DUST_FROM)).coerceIn(0.0f, 1.0f))
        val solidity = DUST_SOLIDITY * rising * funnel.fade
        if (solidity <= NOTHING) return
        val foot = funnel.halfWidth[SLABS - 1]
        val footX = funnel.centreX[SLABS - 1]
        val footZ = funnel.centreZ[SLABS - 1]
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
                        .setColor(seedOf(state, level, DUST), DUST, share, solidity)
                }
                val next = (side + 1) % SIDES
                skirt(lowShare, side, side.toFloat())
                skirt(lowShare, next, side + 1.0f)
                skirt(highShare, next, side + 1.0f)
                skirt(highShare, side, side.toFloat())
            }
        }
    }

    /**
     * The storm's overcast: vanilla-sized cells of cloud in a disc about the foot, solid at the middle and ragged at
     * the edge, spreading before the funnel comes down and drawing in as it ropes out. Each cell is a box, its sides
     * drawn only where the next cell is open or stands higher, as vanilla's clouds are. About the funnel's top it lowers
     * in steps into a collar the funnel hangs from, a wall cloud in blocks.
     */
    private fun overcast(pose: PoseStack.Pose, buffer: VertexConsumer, state: TornadoRenderState, funnel: Funnel) {
        val waning = 1.0f - Mth.smoothstep(((state.roped - OVERCAST_WANES_FROM) / (1.0f - OVERCAST_WANES_FROM)).coerceIn(0.0f, 1.0f))
        val reach = OVERCAST_REACH * Mth.smoothstep(state.brewed) * waning
        if (reach <= NOTHING) return
        val floor = (Tornado.OVERCAST_FLOOR - state.y).toFloat()
        val roof = floor + Tornado.OVERCAST_THICKNESS.toFloat()
        val lowering = Mth.smoothstep(((state.brewed - COLLAR_LOWERS_FROM) / (1.0f - COLLAR_LOWERS_FROM)).coerceIn(0.0f, 1.0f))
        val collarFlat = funnel.topHalfWidth * COLLAR_FLAT_PER_TOP
        val collarX = funnel.centreX[0]
        val collarZ = funnel.centreZ[0]

        fun covers(cellX: Int, cellZ: Int): Boolean {
            val centreX = (cellX + HALF) * OVERCAST_CELL
            val centreZ = (cellZ + HALF) * OVERCAST_CELL
            val outward = Mth.sqrt(centreX * centreX + centreZ * centreZ) / reach
            return outward < OVERCAST_SOLID_TO + (1.0f - OVERCAST_SOLID_TO) * cellNoise(cellX, cellZ, COVER_SALT)
        }

        fun floorOf(cellX: Int, cellZ: Int): Float {
            val awayX = (cellX + HALF) * OVERCAST_CELL - collarX
            val awayZ = (cellZ + HALF) * OVERCAST_CELL - collarZ
            val beyond = ((Mth.sqrt(awayX * awayX + awayZ * awayZ) - collarFlat) / COLLAR_REACH).coerceIn(0.0f, 1.0f)
            return floor - (COLLAR_DROP.toFloat() * lowering * (1.0f - Mth.smoothstep(beyond))).roundToInt()
        }

        fun face(cellX: Int, cellZ: Int, shade: Float, vararg corners: Float) {
            val mottle = cellNoise(cellX, cellZ, MOTTLE_SALT)
            for (corner in corners.indices step VERTEX_FLOATS) {
                buffer.addVertex(pose, corners[corner], corners[corner + 1], corners[corner + 2])
                    .setUv(NOTHING, NOTHING)
                    .setLight(state.lightCoords)
                    .setColor(mottle, OVERCAST, shade, WHOLE)
            }
        }

        /** The side of a cell along one edge, from its own floor up to where the neighbour beyond the edge covers it. */
        fun side(cellX: Int, cellZ: Int, ownFloor: Float, shade: Float, toX: Int, toZ: Int, fromX: Float, fromZ: Float, endX: Float, endZ: Float) {
            val upTo = if (covers(cellX + toX, cellZ + toZ)) floorOf(cellX + toX, cellZ + toZ) else roof
            if (upTo <= ownFloor) return
            face(cellX, cellZ, shade, fromX, ownFloor, fromZ, endX, ownFloor, endZ, endX, upTo, endZ, fromX, upTo, fromZ)
        }

        val cells = Mth.ceil(OVERCAST_REACH / OVERCAST_CELL)
        for (cellX in -cells..<cells) {
            for (cellZ in -cells..<cells) {
                if (!covers(cellX, cellZ)) continue
                val west = cellX * OVERCAST_CELL
                val east = west + OVERCAST_CELL
                val north = cellZ * OVERCAST_CELL
                val south = north + OVERCAST_CELL
                val bottom = floorOf(cellX, cellZ)
                face(cellX, cellZ, UNDERSIDE, west, bottom, north, east, bottom, north, east, bottom, south, west, bottom, south)
                face(cellX, cellZ, TOPSIDE, west, roof, north, west, roof, south, east, roof, south, east, roof, north)
                side(cellX, cellZ, bottom, EAST_WEST_SIDE, -1, 0, west, north, west, south)
                side(cellX, cellZ, bottom, EAST_WEST_SIDE, 1, 0, east, north, east, south)
                side(cellX, cellZ, bottom, NORTH_SOUTH_SIDE, 0, -1, west, north, east, north)
                side(cellX, cellZ, bottom, NORTH_SOUTH_SIDE, 0, 1, west, south, east, south)
            }
        }
    }

    /** A number in 0..1 for a cell of the overcast, separated by [salt]. */
    private fun cellNoise(cellX: Int, cellZ: Int, salt: Int): Float {
        val mixed = cellX * CELL_X_PRIME + cellZ * CELL_Z_PRIME + salt
        val stirred = (mixed xor (mixed ushr HASH_SHIFT)) * HASH_MIX
        return ((stirred xor (stirred ushr HASH_SHIFT)) and HASH_MASK).toFloat() / HASH_WHOLE
    }
    companion object {
        /** How many slabs the funnel is stacked of: about four blocks each once it is down. */
        private const val SLABS = 28
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

        private const val WHOLE = 1.0f

        /** The dust rises once the funnel is nearly down, to about this high, and twice the foot wide at its top. */
        private const val DUST_FROM = 0.6f
        private const val DUST_HEIGHT = 10.0f
        private const val DUST_FOOT = 1.2f
        private const val DUST_TOP = 2.0f
        private const val DUST_LEVELS = 3
        private const val DUST_SPIN = 0.1f
        private const val DUST_TWIST = 0.8f
        private const val DUST_SOLIDITY = 1.0f

        /** How far up into the collar the funnel reaches, so its top is hidden in it. */
        private const val HANGS_INTO_THE_OVERCAST = 3.0
        /**
         * The collar: the overcast lowered this many blocks under the funnel's top, flat out to half again its width
         * and stepping back up over this many blocks beyond. It lowers over the second half of the brewing.
         */
        private const val COLLAR_DROP = 8
        private const val COLLAR_FLAT_PER_TOP = 1.6f
        private const val COLLAR_REACH = 36.0f
        private const val COLLAR_LOWERS_FROM = 0.5f
        /** The sleeve of streaks stands this much wider than the solid funnel. */
        private const val SLEEVE_SHARE = 1.3f
        /** Vanilla's cloud cell, in a disc about five hundred blocks across, solid to the inner seven tenths. */
        private const val OVERCAST_CELL = 12.0f
        private const val OVERCAST_REACH = 250.0f
        private const val OVERCAST_SOLID_TO = 0.7f
        /** It draws in over the end of the roping out. */
        private const val OVERCAST_WANES_FROM = 0.3f
        /** How bright each face of a cell is, as vanilla shades its clouds. */
        private const val UNDERSIDE = 0.7f
        private const val TOPSIDE = 1.0f
        private const val EAST_WEST_SIDE = 0.9f
        private const val NORTH_SOUTH_SIDE = 0.8f
        private const val VERTEX_FLOATS = 3
        private const val HALF = 0.5f
        private const val COVER_SALT = 0x434F5652
        private const val MOTTLE_SALT = 0x4D4F5454
        private const val CELL_X_PRIME = 73856093
        private const val CELL_Z_PRIME = 19349663
        private const val HASH_SHIFT = 16
        private const val HASH_MIX = 0x45D9F3B
        private const val HASH_MASK = 0xFFFF
        private const val HASH_WHOLE = 65535.0f

        /** Which part a vertex belongs to, as the shader reads it — see `tornado.fsh`. */
        private const val FUNNEL = 0.0f
        private const val SLEEVE = 0.25f
        private const val DUST = 0.5f
        private const val OVERCAST = 0.75f

        private const val BURIED_BY = 4.0f
        private const val NOTHING = 0.0f
        private const val ROOT_TWO = 1.4142135f
        private const val QUARTER_TURN = Mth.HALF_PI
        private const val EIGHTH_TURN = Mth.HALF_PI / 2.0f
        private const val FULL_TURN = Mth.TWO_PI

        /** The seed of one slab or level of [part], which sets its bands' pace, surges and phase in `tornado.fsh`. */
        private fun seedOf(state: TornadoRenderState, index: Int, part: Float): Float {
            val stirred = (index * SEED_STEP + (part * PARTS).toInt() * PART_STEP) ushr SEED_SHIFT
            return Mth.frac(state.phase + (stirred and HASH_MASK).toFloat() / HASH_WHOLE)
        }

        private const val SEED_STEP = -0x61C88647
        private const val PART_STEP = 0x27D4EB2F
        private const val SEED_SHIFT = 8
        private const val PARTS = 4.0f

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
