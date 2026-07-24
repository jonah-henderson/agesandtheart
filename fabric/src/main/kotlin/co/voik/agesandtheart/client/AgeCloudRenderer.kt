package co.voik.agesandtheart.client

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
 * Two dense overcast cloud layers for Ages (Spire). Replaces vanilla cloud rendering with flat,
 * near-opaque grey slabs at two world heights — "enveloping overcast", not distinct clouds. The
 * upper layer is lighter, the lower darker (per the reference). Roiling noise and real thickness
 * come in a later pass.
 *
 * Unlike the sky (renderSky, raw matrices), renderClouds provides a MatrixStack, and that is the
 * transform which keeps the layers world-horizontal as the camera turns.
 */
object AgeCloudRenderer : DimensionRenderingRegistry.CloudRenderer {
    private const val UPPER_LAYER_HEIGHT = 224.0
    private const val LOWER_LAYER_HEIGHT = 96.0 // a 128-block gap below the upper layer
    private const val LAYER_RADIUS = 512.0f
    private val UPPER_LAYER_COLOR = Rgba(0.34f, 0.37f, 0.38f, 0.85f) // lighter
    private val LOWER_LAYER_COLOR = Rgba(0.22f, 0.25f, 0.26f, 0.92f) // darker

    override fun render(context: WorldRenderContext) {
        val matrix = context.matrixStack()?.last()?.pose() ?: context.positionMatrix()
        val cameraY = context.camera().position.y

        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()

        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        drawLayer(buffer, matrix, (UPPER_LAYER_HEIGHT - cameraY).toFloat(), UPPER_LAYER_COLOR)
        drawLayer(buffer, matrix, (LOWER_LAYER_HEIGHT - cameraY).toFloat(), LOWER_LAYER_COLOR)
        BufferUploader.drawWithShader(buffer.buildOrThrow())

        RenderSystem.disableBlend()
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
    }

    /** A single flat horizontal slab, centred on the camera, at [heightAboveCamera] render units. */
    private fun drawLayer(buffer: VertexConsumer, matrix: Matrix4f, heightAboveCamera: Float, color: Rgba) {
        val radius = LAYER_RADIUS
        buffer.addColoredVertex(matrix, -radius, heightAboveCamera, -radius, color)
        buffer.addColoredVertex(matrix, -radius, heightAboveCamera, radius, color)
        buffer.addColoredVertex(matrix, radius, heightAboveCamera, radius, color)
        buffer.addColoredVertex(matrix, radius, heightAboveCamera, -radius, color)
    }
}
