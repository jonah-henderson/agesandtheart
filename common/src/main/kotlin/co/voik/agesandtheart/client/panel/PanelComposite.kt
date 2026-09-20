package co.voik.agesandtheart.client.panel

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import org.joml.Vector4f
import net.minecraft.client.DeltaTracker
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.util.ARGB
import org.joml.Matrix4f

/**
 * A panel's finished picture — frame, black, the Age with whatever its instability does to it, and the mist —
 * laid down once a frame into a texture of its own, so that a panel in a book and a panel on a lectern are one
 * picture rather than two drawings of it (design §7.8.2).
 *
 * Two of them: the live one, for whichever panel is showing an Age, and a misted one that every other panel
 * wears. Both are laid down during extraction, never inside the world's render, and sampled afterwards — by the
 * book screen as one blit, by a lectern as one quad.
 *
 * Drawn through vanilla's own immediate path, `RenderType.draw`, pointed at the composite by the two output
 * overrides `RenderSystem` keeps public, so every stroke lands in the order it was given.
 */
object PanelComposite {

    /** Texels to a page-pixel: finer than a book screen shows at any GUI scale, so a blit is as sharp as drawing. */
    private const val TEXELS_PER_PAGE_PIXEL = 4

    private const val FRAMED_WIDTH = PanelPicture.WIDTH + 2 * PanelPicture.FRAME_WIDTH
    private const val FRAMED_HEIGHT = PanelPicture.HEIGHT + 2 * PanelPicture.FRAME_WIDTH

    /** Nothing at all behind the composite — 26.2's clear takes a vector rather than a packed int. */
    private val TRANSPARENT = Vector4f(0.0f, 0.0f, 0.0f, 0.0f)
    private const val FURTHEST_DEPTH = 1.0
    private const val WHOLLY_MISTED = 1.0f
    private const val UNTINTED = -1
    private const val FULLY = 255

    /** Room for the busiest picture, a torn Age's cut, without the builder having to grow. */
    private const val VERTEX_BYTES = 64 * 1024

    private const val NEAREST = -1.0f
    private const val FARTHEST = 1.0f

    /** Page-pixels across the framed picture, downwards as a page's run. */
    private val PAGE_PIXELS: Matrix4f = Matrix4f().setOrtho(
        -PanelPicture.FRAME_WIDTH.toFloat(),
        (PanelPicture.WIDTH + PanelPicture.FRAME_WIDTH).toFloat(),
        (PanelPicture.HEIGHT + PanelPicture.FRAME_WIDTH).toFloat(),
        -PanelPicture.FRAME_WIDTH.toFloat(),
        NEAREST,
        FARTHEST,
    )

    /** Made on first use and kept for the life of the client, as [PanelTarget]'s fields are. */
    private var live: RenderTarget? = null
    private var misted: RenderTarget? = null
    /**
     * 26.2's immediate-mode path: a draw is appended, written into, uploaded and then executed, where a
     * `BufferBuilder` used to be built and handed straight to the render type.
     */
    private val staged by lazy { StagedVertexBuffer({ "Ages linking panel composite" }, VERTEX_BYTES) }
    private val projections by lazy { ProjectionMatrixBuffer("ages linking panel composite") }

    /**
     * Renders [preview]'s Age for this frame and lays the live picture down from it — mist alone until there is
     * something to show, thinning as the ring arrives.
     */
    fun composeLive(preview: PreviewLevel?, delta: DeltaTracker): GpuTextureView {
        val age = preview?.takeIf { PanelRenderer.draw(it, delta) }?.let(AgeInView::of)
        val stillMissing = 1.0f - (preview?.load?.wholeness ?: 0.0f)
        val target = live ?: newTarget("live").also { live = it }
        return compose(target, PanelPicture.strokes(age, stillMissing))
    }

    /** Lays the misted picture down: the one every panel wears that is not showing an Age. */
    fun composeMisted(): GpuTextureView {
        val target = misted ?: newTarget("misted").also { misted = it }
        return compose(target, PanelPicture.strokes(age = null, mist = WHOLLY_MISTED))
    }

    /** The live picture as last laid down, or null before it ever has been. */
    fun liveView(): GpuTextureView? = live?.colorTextureView

    /** The misted picture as last laid down, or null before it ever has been. */
    fun mistedView(): GpuTextureView? = misted?.colorTextureView

    private fun newTarget(which: String): RenderTarget = TextureTarget(
        "Ages linking panel, $which",
        FRAMED_WIDTH * TEXELS_PER_PAGE_PIXEL,
        FRAMED_HEIGHT * TEXELS_PER_PAGE_PIXEL,
        true,
        GpuFormat.RGBA8_UNORM,
    )

    private fun compose(target: RenderTarget, strokes: List<PanelStroke>): GpuTextureView {
        val colour = requireNotNull(target.colorTextureView) { "A panel composite has no colour to lay onto" }
        val depth = requireNotNull(target.depthTextureView) { "A panel composite has no depth to lay onto" }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            requireNotNull(target.colorTexture),
            TRANSPARENT,
            requireNotNull(target.depthTexture),
            FURTHEST_DEPTH,
        )
        PanelTexture.registerAll()
        ontoTheComposite(colour, depth) { lay(runsOf(strokes)) }
        return colour
    }

    /**
     * Runs [block] with vanilla's immediate draws pointed at the composite and measured in page-pixels, and puts
     * back everything it borrowed: the frame this is laid down in goes on to draw the world and the GUI with them.
     */
    private fun ontoTheComposite(colour: GpuTextureView, depth: GpuTextureView, block: () -> Unit) {
        val outerColour = RenderSystem.outputColorTextureOverride
        val outerDepth = RenderSystem.outputDepthTextureOverride
        val outerProjection = RenderSystem.getProjectionMatrixBuffer()
        val outerProjectionType = RenderSystem.getProjectionType()
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix()
        try {
            modelView.identity()
            RenderSystem.outputColorTextureOverride = colour
            RenderSystem.outputDepthTextureOverride = depth
            RenderSystem.setProjectionMatrix(projections.getBuffer(PAGE_PIXELS), ProjectionType.ORTHOGRAPHIC)
            block()
        } finally {
            modelView.popMatrix()
            RenderSystem.outputColorTextureOverride = outerColour
            RenderSystem.outputDepthTextureOverride = outerDepth
            outerProjection?.let { RenderSystem.setProjectionMatrix(it, outerProjectionType) }
        }
    }

    /** Strokes in a row that take one render type, and so one draw. */
    private class Run(val renderType: RenderType, val format: VertexFormat) {
        val strokes = mutableListOf<PanelStroke>()
    }

    private fun runsOf(strokes: List<PanelStroke>): List<Run> {
        val runs = mutableListOf<Run>()
        for (stroke in strokes) {
            val renderType = renderTypeOf(stroke)
            val continues = runs.lastOrNull()?.takeIf { it.renderType === renderType }
            val run = continues ?: Run(renderType, formatOf(stroke)).also { runs += it }
            run.strokes += stroke
        }
        return runs
    }

    private fun renderTypeOf(stroke: PanelStroke): RenderType = when (stroke) {
        is PanelStroke.Fill -> PanelRenderTypes.fill
        is PanelStroke.Field -> PanelRenderTypes.field(stroke.field)
        is PanelStroke.Mist -> PanelRenderTypes.mist
    }

    private fun formatOf(stroke: PanelStroke): VertexFormat = when (stroke) {
        is PanelStroke.Fill, is PanelStroke.Mist -> DefaultVertexFormat.POSITION_COLOR
        is PanelStroke.Field -> DefaultVertexFormat.POSITION_TEX_COLOR
    }

    /**
     * **Written in two passes, because the upload is one call for all of them.** Every run is appended and
     * filled first, then a single upload stages the lot, then each is executed in the order it was laid —
     * which is what keeps the composite a painter's stack rather than a race between draws.
     */
    private fun lay(runs: List<Run>) {
        val appended = runs.map { run ->
            val draw = staged.appendDraw(run.format, PrimitiveTopology.QUADS)
            val builder = staged.getVertexBuilder(draw)
            run.strokes.forEach { cornersOf(builder, it) }
            staged.endDraw()
            run to draw
        }
        staged.upload()
        // Null where a draw took no vertices, which is nothing to execute rather than a fault.
        appended.forEach { (run, draw) ->
            staged.getExecuteInfo(draw)?.let { run.renderType.prepare().drawFromBuffer(it) }
        }
        staged.endFrame()
    }

    /** A stroke's four corners, top-left round to top-right. */
    private fun cornersOf(builder: VertexConsumer, stroke: PanelStroke) {
        val left = stroke.rect.left.toFloat()
        val top = stroke.rect.top.toFloat()
        val right = stroke.rect.right.toFloat()
        val bottom = stroke.rect.bottom.toFloat()
        when (stroke) {
            is PanelStroke.Fill -> {
                builder.addVertex(left, top, 0.0f).setColor(stroke.colour)
                builder.addVertex(left, bottom, 0.0f).setColor(stroke.colour)
                builder.addVertex(right, bottom, 0.0f).setColor(stroke.colour)
                builder.addVertex(right, top, 0.0f).setColor(stroke.colour)
            }
            is PanelStroke.Field -> {
                builder.addVertex(left, top, 0.0f).setUv(stroke.u0, stroke.v0).setColor(UNTINTED)
                builder.addVertex(left, bottom, 0.0f).setUv(stroke.u0, stroke.v1).setColor(UNTINTED)
                builder.addVertex(right, bottom, 0.0f).setUv(stroke.u1, stroke.v1).setColor(UNTINTED)
                builder.addVertex(right, top, 0.0f).setUv(stroke.u1, stroke.v0).setColor(UNTINTED)
            }
            is PanelStroke.Mist -> {
                val colour = ARGB.color((stroke.strength * FULLY).toInt(), FULLY, FULLY, FULLY)
                builder.addVertex(left, top, 0.0f).setColor(colour)
                builder.addVertex(left, bottom, 0.0f).setColor(colour)
                builder.addVertex(right, bottom, 0.0f).setColor(colour)
                builder.addVertex(right, top, 0.0f).setColor(colour)
            }
        }
    }
}
