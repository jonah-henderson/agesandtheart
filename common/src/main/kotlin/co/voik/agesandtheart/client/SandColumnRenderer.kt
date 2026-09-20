package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.SandColumn
import co.voik.agesandtheart.compat.ONLY_VERTEX_BINDING
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

/** What the renderer needs of a column, taken off it before drawing. */
class SandColumnRenderState : EntityRenderState() {
    var halfWidth = 0.0f
    var reach = 0.0f
    var below = 0.0f
    var heading = 0.0f
    var phase = 0.0f
    var pour = 0.0f
    var coreHalfWidth = 0.0f
}

/**
 * A column of sand, falling from higher than the sky goes to the ground it is burying.
 *
 * **Eight quads and a fragment shader**, which is the whole of it. Two nested prisms of four faces each
 * carry no detail at all; everything that makes it read as falling sand is `sand_column.fsh`, evaluated per
 * fragment. The alternative — Ephemeris's roil summed per *vertex* on the CPU — needs the column subdivided
 * finely enough for the interpolation to hold up, which is tens of thousands of vertices rebuilt every
 * frame, and it still gives the coarse wash that Ephemeris's own note says it moved off.
 *
 * **Nothing per-column reaches the shader as a uniform, deliberately.** `submitCustomGeometry` batches by
 * `RenderType`, so a pipeline carrying per-draw uniforms would fight the batching and need a render type
 * apiece. Everything a column needs is in its vertices instead: where a corner stands around the prism and
 * how far below the top it is (`UV0`), and its own phase, which prism it belongs to and how solid that one
 * is (`Color`). One pipeline serves every column in the Age.
 *
 * **Time is free.** `GameTime` rides in the `Globals` uniform block every shader already sees, so the fall
 * costs nothing per frame and nothing per tick — see `notes/water-colour-research.md`. It is a day fraction
 * and wraps at dawn, which is why the shader's drift is a whole number of turns a day.
 *
 * **The bottom is not drawn, it is buried.** The prism starts well below the ground the column rides, and
 * the terrain in front of it settles the rest through the depth test — so a column meets a hillside exactly
 * where the hillside is, and no part of this has to know anything about the shape of the land.
 *
 * **Culling is declined outright** ([affectedByCulling]), because the entity's box is its footprint and
 * what is drawn stands from under the ground to the top of the world. There is no `getBoundingBoxForCulling`
 * in 26.1 to widen the box through instead, so this is the seam.
 */
class SandColumnRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<SandColumn, SandColumnRenderState>(context) {

    override fun createRenderState(): SandColumnRenderState = SandColumnRenderState()

    override fun extractRenderState(entity: SandColumn, state: SandColumnRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.halfWidth = entity.halfWidth
        state.heading = entity.yRot
        state.below = BURIED_BY
        state.reach = (entity.level().maxY - entity.y).toFloat() + BURIED_BY
        // Its own place in the fall, so two columns standing at once do not come down in step. Taken off
        // the entity id because it is stable for the column's life and costs nothing to carry.
        state.phase = ((entity.id * PHASE_STEP) and PHASE_MASK).toFloat() / PHASE_WHOLE
        state.pour = entity.pour
        // Taken off the column rather than worked out again here: the air goes blind inside exactly this,
        // so the two must not each have their own idea of where it is.
        state.coreHalfWidth = entity.coreHalfWidth
    }

    /** Never — see the class doc. */
    override fun affectedByCulling(entity: SandColumn): Boolean = false

    override fun submit(
        state: SandColumnRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        if (state.halfWidth <= NOTHING || state.reach <= NOTHING) return
        poseStack.pushPose()
        // The prism turns with the column, so its faces stand square to where it is going.
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.heading))
        // **Innermost first, in one submission, and that order is the whole of what makes it correct.**
        // Every shell writes depth, so each one drawn after is nearer, passes, and blends over what is
        // already there — which is back-to-front, the only order translucency can be composited in. It is
        // one call because a render type's buffer keeps the order it was written in, and two calls would
        // leave the collector to decide.
        collector.submitCustomGeometry(poseStack, SAND_COLUMN) { pose, buffer ->
            prism(pose, buffer, state, state.coreHalfWidth, CORE, SOLID)
            // Halfway between the two, so the shell reads as a graded haze rather than one flat sheet.
            prism(pose, buffer, state, (state.halfWidth + state.coreHalfWidth) / BOTH_SIDES, INNER, INNER_SOLIDITY)
            prism(pose, buffer, state, state.halfWidth, OUTER, OUTER_SOLIDITY)
        }
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    /** Four faces, wound so the coordinate around them runs on from one to the next. */
    private fun prism(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        state: SandColumnRenderState,
        half: Float,
        which: Float,
        solidity: Float,
    ) {
        val side = half * BOTH_SIDES
        face(pose, buffer, state, -half, -half, half, -half, side * 0.0f, side, which, solidity)
        face(pose, buffer, state, half, -half, half, half, side * 1.0f, side, which, solidity)
        face(pose, buffer, state, half, half, -half, half, side * 2.0f, side, which, solidity)
        face(pose, buffer, state, -half, half, -half, -half, side * 3.0f, side, which, solidity)
    }

    /**
     * One face, from below the ground to the top of the world.
     *
     * `UV0.x` is how far around the prism the corner stands and carries on across the four faces, so the
     * roil does not mirror at a corner. `UV0.y` measures **down from the top**, which is the direction the
     * sand falls and the direction the fade is written in — so neither needs to know how tall the column is.
     */
    private fun face(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        state: SandColumnRenderState,
        fromX: Float,
        fromZ: Float,
        toX: Float,
        toZ: Float,
        around: Float,
        side: Float,
        which: Float,
        solidity: Float,
    ) {
        val top = state.reach - state.below
        corner(pose, buffer, fromX, -state.below, fromZ, around, state.reach, state, which, solidity)
        corner(pose, buffer, toX, -state.below, toZ, around + side, state.reach, state, which, solidity)
        corner(pose, buffer, toX, top, toZ, around + side, NOTHING, state, which, solidity)
        corner(pose, buffer, fromX, top, fromZ, around, NOTHING, state, which, solidity)
    }

    private fun corner(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        atX: Float,
        atY: Float,
        atZ: Float,
        around: Float,
        down: Float,
        state: SandColumnRenderState,
        which: Float,
        solidity: Float,
    ) {
        buffer.addVertex(pose, atX, atY, atZ)
            .setUv(around, down)
            .setLight(state.lightCoords)
            // r: this column's own phase. g: which shell, which decides both its lanes and whether it is
            // the solid one. b: how fast it pours. a: how solid the shell is before the fall thins it.
            .setColor(state.phase, which, state.pour, solidity)
    }

    companion object {
        /**
         * The column's own pipeline.
         *
         * Needs no registration: Blaze3D compiles one on first use, reading shaders through `ShaderManager`,
         * which scans `shaders/` across every namespace.
         *
         * **It writes depth, and that is what fixes water and clouds** (Jonah, 2026-08-31, walked). Both are
         * drawn *after* entities, so geometry that does not write depth is geometry they paint straight
         * over — water stood in front of a column it was behind, and clouds added themselves to it. A
         * translucent thing that writes depth is usually wrong, and is right here because the shells are
         * submitted innermost-first: back-to-front is the order translucency wants anyway, so writing depth
         * costs nothing and buys correctness against everything drawn later.
         *
         * **Neither face is culled**, because a player walks through a column rather than around it, and
         * the core is only a wall you cannot see out of if its inside is drawn.
         */
        private val PIPELINE: RenderPipeline = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/sand_column"))
            .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "sand_column"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "sand_column"))
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                    .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                    .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                    .build(),
            )
            // The world's own light, so a column goes down with the sun instead of glowing at midnight.
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withSampler("Sampler2")
                    .build(),
            )
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
            .withCull(false)
            .withVertexBinding(ONLY_VERTEX_BINDING, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build()

        /**
         * The render type every column in the Age is drawn through — one, so they batch.
         *
         * `RenderType.create` is the one widened line this route costs; `RenderSetup.builder` is already
         * public. See `agesandtheart.accesswidener`.
         */
        private val SAND_COLUMN: RenderType = RenderType.create(
            "sand_column",
            RenderSetup.builder(PIPELINE).useLightmap().createRenderSetup(),
        )

        private const val NAMESPACE = "agesandtheart"

        /** How far below the ground the prism starts, so a slope is answered by the depth test. */
        private const val BURIED_BY = 24.0f

        /** Which shell a vertex belongs to, as the shader reads it — see `sand_column.fsh`. */
        private const val OUTER = 0.0f
        private const val INNER = 0.5f
        private const val CORE = 1.0f

        /** The core keeps all of the roil and none of the transparency. */
        private const val SOLID = 1.0f
        /**
         * How solid each layer is before the fall thins it. High, because the shader squares the fall into
         * the alpha: what is wanted is a curtain that is nearly opaque where the sand is and nearly clear
         * where it is not, rather than an even veil at half strength.
         */
        private const val OUTER_SOLIDITY = 0.95f
        private const val INNER_SOLIDITY = 0.85f

        private const val NOTHING = 0.0f
        private const val BOTH_SIDES = 2.0f

        /** Spreads consecutive entity ids around the phase rather than stepping through it in order. */
        private const val PHASE_STEP = 2654435761L
        private const val PHASE_MASK = 0xFFFFL
        private const val PHASE_WHOLE = 65536.0f
    }
}
