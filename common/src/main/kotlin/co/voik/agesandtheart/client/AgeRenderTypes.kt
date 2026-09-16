package co.voik.agesandtheart.client

import co.voik.agesandtheart.location
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer
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
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
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
     * the two samplers, the vertex format, the uniform buffers and the fifteen layers all come from
     * whatever `RenderPipelines.END_PORTAL` is on the day, so none of it can quietly fall out of step with
     * a version that adds a uniform. `RenderPipeline` publishes a getter for every field and `Snippet` is a
     * public record over the same ones, so this needs no widened access at all.
     *
     * Only the depth test is left [Optional.empty], for the builder to fill in.
     */
    private fun vanillasEndPortal(): RenderPipeline.Snippet {
        val portal = RenderPipelines.END_PORTAL
        return RenderPipeline.Snippet(
            Optional.of(portal.vertexShader),
            Optional.of(portal.fragmentShader),
            Optional.of(portal.shaderDefines),
            Optional.of(portal.samplers),
            Optional.of(portal.uniforms),
            Optional.of(portal.colorTargetState),
            Optional.empty(),
            Optional.of(portal.polygonMode),
            Optional.of(portal.isCull),
            Optional.of(portal.vertexFormat),
            Optional.of(portal.vertexFormatMode),
        )
    }

    val lightThroughFog: RenderType = RenderType.create(
        "age_light_through_fog",
        RenderSetup.builder(
            RenderPipeline.builder(MATRICES_AND_PROJECTION)
                .withLocation("pipeline/light_through_fog".location())
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
                .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .build(),
        ).createRenderSetup(),
    )

}
