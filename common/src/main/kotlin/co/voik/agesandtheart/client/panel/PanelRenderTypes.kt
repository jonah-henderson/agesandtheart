package co.voik.agesandtheart.client.panel

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
 * How a panel is drawn onto a book in the world, where the book screen draws it into the GUI.
 *
 * Neither is fogged or lit: a panel is a window onto somewhere else, and the weather and the dark of the room
 * it stands in are not what it shows.
 */
object PanelRenderTypes {

    /** The two uniform buffers both shaders read. Restated, as `AgeRenderTypes` does, since vanilla's is private. */
    private val MATRICES_AND_PROJECTION: RenderPipeline.Snippet = RenderPipeline.builder()
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .buildSnippet()

    /**
     * The Age itself, off [PanelTexture] — vanilla's `position_tex_color`, which the End sky draws with, made
     * opaque and writing depth, since it lies on the page as solidly as the page does.
     */
    val picture: RenderType = RenderType.create(
        "age_linking_panel_picture",
        RenderSetup.builder(
            RenderPipeline.builder(MATRICES_AND_PROJECTION)
                .withLocation("pipeline/linking_panel_picture".location())
                .withVertexShader("core/position_tex_color")
                .withFragmentShader("core/position_tex_color")
                .withSampler("Sampler0")
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withCull(false)
                .build(),
        ).withTexture("Sampler0", PanelTexture.LOCATION, PanelTarget::sampler).createRenderSetup(),
    )

    /**
     * The mist a loading panel wears, on a quad in the world: the book screen's own fragment shader, with a
     * vertex shader that measures the field in page-pixels off the quad's texture coordinates, so the banks
     * are the size they are in a book and do not slide about as the viewer moves.
     *
     * Blended, and writing no depth, so that it thins to show the picture under it.
     */
    val mist: RenderType = RenderType.create(
        "age_linking_panel_mist",
        RenderSetup.builder(
            RenderPipeline.builder(MATRICES_AND_PROJECTION)
                .withLocation("pipeline/linking_panel_mist_on_a_page".location())
                .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                .withVertexShader("panel_mist_on_a_page".location())
                .withFragmentShader("panel_mist".location())
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                .withDepthStencilState(DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withCull(false)
                .build(),
        ).createRenderSetup(),
    )
}
