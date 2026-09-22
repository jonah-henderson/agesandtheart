package co.voik.agesandtheart.client

import co.voik.agesandtheart.compat.ONLY_VERTEX_BINDING
import co.voik.agesandtheart.location
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer
import net.minecraft.client.renderer.oit.OitPipelineSet
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import java.util.Optional

/**
 * Ways of drawing that vanilla has no arrangement for.
 *
 * Everything here is assembled out of vanilla's own shaders and blend modes — the mod ships no GLSL, and
 * nothing here waits on the asset pass.
 */
object AgeRenderTypes {

    /**
     * The two uniform buffers `core/position_color` reads, and **nothing else** — no `Fog`, which is the
     * whole point.
     *
     * Restated rather than borrowed: vanilla's own `MATRICES_PROJECTION_SNIPPET` says exactly this, and is
     * private. Two lines here beat widening vanilla's access in the two files that would have to be kept in
     * step for them.
     */
    internal val MATRICES_AND_PROJECTION: RenderPipeline.Snippet = RenderPipeline.builder()
        .withBindGroupLayout(
            BindGroupLayout.builder()
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .build(),
        )
        .buildSnippet()

    /**
     * Light that the fog does not reach.
     *
     * **Fog is a property of the shader, not of where you draw.** One fog buffer is bound for the whole
     * level pass, and each fragment shader decides what to do with it — `core/rendertype_lightning`, which
     * `RenderTypes.dragonRays` uses, multiplies by `(1 - fogValue)`, so an additive glow fades to nothing
     * exactly where the fog ends. That is right for a meteor and wrong for a lure: in an abyss the whole
     * point of a light is that it is the thing you can see when nothing else is.
     *
     * `core/position_color` imports no fog at all — vanilla's own debug pipelines are built on it, which is
     * why this needs no shader of ours. What is ours is pairing it with the lightning blend, so the result
     * is `DRAGON_RAYS` exactly but for the fog: additive, depth-**tested** so rock still hides it, and no
     * depth write, which is the fault `AddedLight` records — a glow that stamps depth makes the water drawn
     * after it vanish in the patch behind.
     *
     * **Registering it is optional and deliberately skipped.** `GlDevice` compiles a pipeline lazily on
     * first use, so registration buys only the absence of a first-use hitch and would cost a loader-divergent
     * seam (NeoForge's `RegisterRenderPipelinesEvent`, Fabric's `FabricRenderPipeline`) for a pair of shader
     * compiles. Worth revisiting only if the first hadalfish anybody meets arrives with a stutter.
     *
     * **Nothing about it limits how far it carries**, which is the trap it comes with: an unfogged light is
     * visible as far as its entity is tracked. Whatever draws on this owes the world its own falloff —
     * see `HadalfishRenderer.SEEN_UNTIL`.
     */
    /**
     * The starfield drawn over everything, whatever the depth buffer says.
     *
     * Vanilla's `RenderTypes.endPortal` cannot do this: it carries `DepthStencilState.DEFAULT`, so terrain
     * between the eye and the field wins, and under an Age's world there is always terrain in the way. The
     * shaders, the samplers and the fifteen layers are vanilla's — only the depth test differs, and it is
     * `ALWAYS_PASS` with no write so the veil settles nothing and hides everything.
     *
     * **Culled, and the geometry is wound inward for it.** The veil is a closed box round the eye, so with
     * backface culling on each direction meets exactly one face and there is no draw-order fight between
     * the lid and the walls.
     *
     * Vanilla's three uniform snippets are restated because they are private, exactly as
     * [MATRICES_AND_PROJECTION] restates one of them.
     */
    val starFissureVeil: RenderType = RenderType.create(
        "age_star_fissure_veil",
        RenderSetup.builder(
            RenderPipeline.builder(vanillasEndPortal())
                .withLocation("pipeline/star_fissure_veil".location())
                .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .build(),
        )
            .withTexture("Sampler0", AbstractEndPortalRenderer.END_SKY_LOCATION)
            .withTexture("Sampler1", AbstractEndPortalRenderer.END_PORTAL_LOCATION)
            .createRenderSetup(),
    )

    /**
     * Vanilla's end-portal pipeline taken apart, so one property of it can be put back differently.
     *
     * **Read off the built pipeline rather than restated**, which is what keeps it vanilla's: the shaders,
     * the bind group layouts, the vertex bindings and the fifteen layers all come from whatever
     * `RenderPipelines.END_PORTAL` is on the day, so none of it can quietly fall out of step with a version
     * that adds a uniform. `RenderPipeline` publishes a getter for nearly every field and `Snippet` is a
     * public record over the same ones, so this needs no widened access at all.
     *
     * The one exception is how many colour targets are live: a built pipeline does not say, so it is
     * counted off the array. Only the depth test is left [Optional.empty], for the builder to fill in.
     */
    // NeoForge's own deprecation, steering to a constructor that takes a field only it has. `common`
    // compiles against vanilla and cannot name it, and vanilla's is the shape this copies from.
    @Suppress("DEPRECATION")
    private fun vanillasEndPortal(): RenderPipeline.Snippet {
        val portal = RenderPipelines.END_PORTAL
        val colourTargets = portal.colorTargetStates.toTypedArray()
        return RenderPipeline.Snippet(
            portal.shaders,
            Optional.of(portal.shaderDefines),
            Optional.of(portal.bindGroupLayouts),
            colourTargets,
            colourTargets.count { it != null },
            Optional.empty(),
            Optional.of(portal.polygonMode),
            Optional.of(portal.isCull),
            portal.vertexFormatBindings.toTypedArray(),
            Optional.of(portal.primitiveTopology),
            portal.pushConstantSize(),
        )
    }

    private fun describedGlow(): RenderPipeline.Builder = RenderPipeline.builder(MATRICES_AND_PROJECTION)
        .withVertexShader("core/position_color")
        .withFragmentShader("core/position_color")
        .withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
        .withVertexBinding(ONLY_VERTEX_BINDING, DefaultVertexFormat.POSITION_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))

    /**
     * A blended render type handed to `submitCustomGeometry` is drawn in the translucent phase, and on a
     * client that sorts its transparency that phase *is* the OIT one — which asks a render type for a
     * pipeline per `OitStage` and throws where there is none. Vanilla derives the three from the
     * description above, and `core/position_color` already answers the defines they carry.
     */
    private val SORTED_GLOW: OitPipelineSet =
        OitPipelineSet.builder("age_light_through_fog", describedGlow()).build()

    val lightThroughFog: RenderType = RenderType.create(
        "age_light_through_fog",
        RenderSetup.builder(describedGlow().withLocation("pipeline/light_through_fog".location()).build())
            .setOitPipelines(SORTED_GLOW)
            .createRenderSetup(),
    )

}
