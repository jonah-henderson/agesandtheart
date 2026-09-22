package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.client.AgeRenderTypes.MATRICES_AND_PROJECTION
import co.voik.agesandtheart.compat.ONLY_VERTEX_BINDING
import co.voik.agesandtheart.location
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.vertex.VertexFormat
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
                .withVertexBinding(ONLY_VERTEX_BINDING, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build(),
        ).createRenderSetup(),
    )

    /**
     * A piece of a field: vanilla's `position_tex_color`, which the End sky and the GUI's own blits draw
     * with.
     *
     * **Opaque, for the same reason [ON_A_PAGE] is** — a field *is* the finished picture, and a finished
     * picture has nothing behind it worth seeing. It is not built on [LAID], which blends.
     *
     * **Blending it was what washed out the Age's foliage.** A level render clears its background to the
     * fog colour at alpha zero and composites over that, so the picture that comes back is *premultiplied*:
     * a leaf covering a fraction `a` of its pixel arrives already multiplied by `a`. Laying that over the
     * haze fill with ordinary translucent blending multiplies by `a` a second time, so the leaf is weighed
     * at `a²` against a haze weighed at `1 - a` — and the thinner the coverage the more haze wins. Solid
     * ground writes `a = 1` and never notices; distant foliage, whose mipped alpha is thin, goes milky;
     * near foliage is nearly opaque and looks right. Turning Minecraft's own "Improved Transparency" on
     * hid it by routing translucent terrain through the post chain, which hands back `a = 1`.
     *
     * Straight replacement is right rather than merely better: the RGB in the target is *already* the
     * Age composited over its own fog colour, including where nothing was drawn, which is the same colour
     * the haze fill underneath is painted with.
     *
     * **And it writes colour only, leaving the composite's own alpha alone.** Replacing the alpha as well
     * moved the fault rather than fixing it: the picture's partial alpha landed in the composite, which
     * looked right on a lectern — [ON_A_PAGE] draws opaquely and never reads it — and turned the same
     * foliage the colour of the *page* in the book screen, which blends. The fills beneath have already
     * written alpha one across the whole panel, so masking alpha out here leaves the composite opaque
     * everywhere, which is what a finished picture should be for either reader.
     */
    private val FIELD_PIPELINE: RenderPipeline = RenderPipeline.builder(MATRICES_AND_PROJECTION)
        .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
        .withLocation("pipeline/linking_panel_field".location())
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withBindGroupLayout(
            BindGroupLayout.builder()
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                .build(),
        )
        .withVertexBinding(ONLY_VERTEX_BINDING, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withDepthStencilState(Optional.empty())
        .withCull(false)
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
                .withBindGroupLayout(
                    BindGroupLayout.builder()
                        .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                        .build(),
                )
                .withVertexShader("panel_mist".location())
                .withFragmentShader("panel_mist".location())
                .withVertexBinding(ONLY_VERTEX_BINDING, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build(),
        ).createRenderSetup(),
    )

    /**
     * A finished picture on a book in the world: opaque, since the picture always is, and writing depth, since
     * it lies on the page as solidly as the page does.
     *
     * **[ColorTargetState.DEFAULT] is what says "opaque", and saying nothing is not the same thing.** A
     * builder starts with *no* colour target at all, and 26.3 checks the count against the pass it is drawn
     * in — one attachment for the level's, so a pipeline declaring none is "Render pass color attachment
     * count must match pipeline color target state count" the first time a lectern of ours is in view.
     * `DEFAULT` carries no blend function, which is both what opaque means and what keeps this out of the
     * translucent phase.
     */
    private val ON_A_PAGE: RenderPipeline = RenderPipeline.builder(MATRICES_AND_PROJECTION)
        .withLocation("pipeline/linking_panel_on_a_page".location())
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withBindGroupLayout(
            BindGroupLayout.builder()
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                .build(),
        )
        .withVertexBinding(ONLY_VERTEX_BINDING, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
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
