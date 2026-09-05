package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.location
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.ARGB

/**
 * A shader run over the panel's rectangle, which is the shape every panel effect takes.
 *
 * The panel is one quad in a book, so an effect on it is one quad drawn through a pipeline of ours: no
 * texture to author, no geometry, and the whole of the effect in a fragment shader. Everything an effect
 * needs to know beyond that is *how far on it is* — a fade, an instability, a strength — which arrives as
 * the quad's alpha because `fill` gives a colour and nothing else.
 *
 * The uniforms are named rather than taken from vanilla's snippets, which are private. `Globals` is the
 * one worth asking for: it carries `GameTime`, which is what an effect animates against.
 */
class PanelOverlay(name: String) {

    private val pipeline: RenderPipeline = RenderPipeline.builder()
        .withLocation("pipeline/$name".location())
        .withVertexShader(name.location())
        .withFragmentShader(name.location())
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("Globals", UniformType.UNIFORM_BUFFER)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        // What `fill` submits: a rectangle of position and colour, and no texture coordinates at all.
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
        .build()

    /**
     * Draws the effect over the given rectangle, [strength] deciding how much of it there is.
     *
     * Nothing at all at zero, rather than a transparent quad: an effect that is not showing should not be
     * a draw call, and `gui.fsh`'s own discard is the precedent.
     */
    fun drawOver(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, strength: Float) {
        if (strength <= 0.0f) return
        val alpha = (strength.coerceAtMost(1.0f) * 255.0f).toInt()
        graphics.fill(pipeline, x, y, x + width, y + height, ARGB.color(alpha, 255, 255, 255))
    }

    companion object {
        /**
         * What a panel shows before it shows an Age, and what the Age fades in through (design §7.8.1).
         *
         * The fade *is* the load, so this is not decoration over a wait: it is drawn at the strength the
         * ring is still missing, and it is gone at the moment the ring is whole.
         */
        val MIST = PanelOverlay("panel_mist")
    }
}
