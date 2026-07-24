package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4f

/**
 * Custom sky for Ages. Replaces vanilla sky rendering, so we draw everything ourselves.
 *
 * Pass (a): a uniform steel-blue overcast dome (a box around the camera), no sun or moon —
 * the stormy Spire backdrop. Stars and (separately) the two cloud layers come next.
 */
object AgeSkyRenderer : DimensionRenderingRegistry.SkyRenderer {
    // dark desaturated storm-grey with a faint cold teal cast (Spire reference)
    private const val R = 0.16f
    private const val G = 0.19f
    private const val B = 0.19f
    private var logged = false

    override fun render(context: WorldRenderContext) {
        if (!logged) { Constants.LOG.info("AgeSkyRenderer drawing steel overcast"); logged = true }
        // In 1.21.1 the sky is drawn with raw matrices (no MatrixStack), so context.matrixStack()
        // is null during sky rendering. The camera/frustum transform is the position matrix.
        val m = context.positionMatrix()

        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.depthMask(false)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()

        val d = 100.0f
        val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        // Box surrounding the camera; every inward face is the same steel colour.
        quad(buf, m, -d, d, -d, -d, d, d, d, d, d, d, d, -d)      // up
        quad(buf, m, -d, -d, d, -d, -d, -d, d, -d, -d, d, -d, d)  // down
        quad(buf, m, d, -d, -d, d, d, -d, d, d, d, d, -d, d)      // +x
        quad(buf, m, -d, -d, d, -d, d, d, -d, d, -d, -d, -d, -d)  // -x
        quad(buf, m, d, -d, d, d, d, d, -d, d, d, -d, -d, d)      // +z
        quad(buf, m, -d, -d, -d, -d, d, -d, d, d, -d, d, -d, -d)  // -z
        BufferUploader.drawWithShader(buf.buildOrThrow())

        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
    }

    private fun quad(
        b: VertexConsumer, m: Matrix4f,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
    ) {
        b.addVertex(m, x1, y1, z1).setColor(R, G, B, 1.0f)
        b.addVertex(m, x2, y2, z2).setColor(R, G, B, 1.0f)
        b.addVertex(m, x3, y3, z3).setColor(R, G, B, 1.0f)
        b.addVertex(m, x4, y4, z4).setColor(R, G, B, 1.0f)
    }
}
