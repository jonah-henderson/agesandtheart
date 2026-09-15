package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.client.AgeRenderTypes.MATRICES_AND_PROJECTION
import co.voik.agesandtheart.location
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import java.util.Optional

/**
 * How a panel is drawn: the strokes [PanelComposite] lays its picture down with, and the finished picture on a
 * book in the world.
 *
 * None is fogged or lit. A panel is a window onto somewhere else, and the weather and the dark of the room it
 * stands in are not what it shows.
 */
object PanelRenderTypes {

    /**
     * Laying a picture down: blended as the GUI blends, and never depth-tested, since the strokes land in the
     * order they are given and each is meant to cover what came before.
     */
    private val LAID: RenderPipeline.Snippet = RenderPipeline.builder(MATRICES_AND_PROJECTION)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(Optional.empty())
        .withCull(false)
        .buildSnippet()

    /** A flat colour: the frame, the black, the haze and the wash. */
    val fill: RenderType = RenderType.create(
        "age_linking_panel_fill",
        RenderSetup.builder(
            RenderPipeline.builder(LAID)
                .withLocation("pipeline/linking_panel_fill".location())
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .build(),
        ).createRenderSetup(),
    )

    /** A piece of a field: vanilla's `position_tex_color`, which the End sky and the GUI's own blits draw with. */
    private val FIELD_PIPELINE: RenderPipeline = RenderPipeline.builder(LAID)
        .withLocation("pipeline/linking_panel_field".location())
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withSampler("Sampler0")
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        .build()

    private val newestField: RenderType = fieldFrom(PanelTexture.NEWEST_FIELD)
    private val olderField: RenderType = fieldFrom(PanelTexture.OLDER_FIELD)

    fun field(which: PanelField): RenderType = when (which) {
        PanelField.NEWEST -> newestField
        PanelField.OLDER -> olderField
    }

    /** The mist, measured in the page-pixels the picture is laid down in. */
    val mist: RenderType = RenderType.create(
        "age_linking_panel_mist",
        RenderSetup.builder(
            RenderPipeline.builder(LAID)
                .withLocation("pipeline/linking_panel_mist".location())
                .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                .withVertexShader("panel_mist".location())
                .withFragmentShader("panel_mist".location())
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .build(),
        ).createRenderSetup(),
    )

    /**
     * A finished picture on a book in the world: opaque, since the picture always is, and writing depth, since
     * it lies on the page as solidly as the page does.
     */
    private val ON_A_PAGE: RenderPipeline = RenderPipeline.builder(MATRICES_AND_PROJECTION)
        .withLocation("pipeline/linking_panel_on_a_page".location())
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withSampler("Sampler0")
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withCull(false)
        .build()

    val livePicture: RenderType = onAPage("live", PanelTexture.LIVE)
    val mistedPicture: RenderType = onAPage("misted", PanelTexture.MISTED)

    private fun fieldFrom(texture: PanelTexture): RenderType = RenderType.create(
        "age_linking_panel_${texture.location.path.substringAfterLast('/')}",
        RenderSetup.builder(FIELD_PIPELINE).withTexture("Sampler0", texture.location, PanelTarget::sampler).createRenderSetup(),
    )

    private fun onAPage(which: String, texture: PanelTexture): RenderType = RenderType.create(
        "age_linking_panel_${which}_on_a_page",
        RenderSetup.builder(ON_A_PAGE).withTexture("Sampler0", texture.location, PanelTarget::sampler).createRenderSetup(),
    )
}
