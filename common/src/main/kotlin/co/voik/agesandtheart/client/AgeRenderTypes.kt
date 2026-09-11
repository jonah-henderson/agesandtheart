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
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType

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
    private val MATRICES_AND_PROJECTION: RenderPipeline.Snippet = RenderPipeline.builder()
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
