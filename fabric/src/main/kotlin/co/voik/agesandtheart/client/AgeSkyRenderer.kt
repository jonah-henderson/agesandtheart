package co.voik.agesandtheart.client

import co.voik.agesandtheart.math.Sphere
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.math.Axis
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.util.Random

/**
 * Custom sky for Ages. Replaces vanilla sky rendering entirely, so we draw everything ourselves:
 * a dark storm-grey overcast dome (no sun or moon) and a sparse, faintly drifting starfield.
 * The two cloud layers (see [AgeCloudRenderer]) will eventually occlude the stars from below.
 */
object AgeSkyRenderer : DimensionRenderingRegistry.SkyRenderer {
    private val OVERCAST_COLOR = Rgba(0.16f, 0.19f, 0.19f) // dark storm-grey, faint cold teal
    private val STAR_COLOR = Rgba(0.85f, 0.88f, 0.95f, 0.75f) // pale, cool, faint

    private const val DOME_RADIUS = 100.0f
    private const val STAR_DRIFT_DEGREES_PER_TICK = 0.0015f

    // Star field generation. Fixed seed keeps the sky identical every visit.
    private const val STAR_SEED = 0xA6E57A25L
    private const val STAR_COUNT = 180
    private const val STAR_DISTANCE = 100.0
    private const val MIN_STAR_SIZE = 0.20
    private const val STAR_SIZE_VARIATION = 0.15
    private const val VERTICES_PER_STAR = 4
    private val FULL_CIRCLE_RADIANS = 2.0 * Math.PI

    /** The six faces of a box surrounding the camera; every inward face is [OVERCAST_COLOR]. */
    private val OVERCAST_FACES: List<List<Vec3>> = surroundingCubeFaces(DOME_RADIUS)

    /** Flattened star quad corners (x, y, z per vertex, four vertices per star). Built once. */
    private val starVertices: FloatArray by lazy { buildStarVertices() }

    override fun render(context: WorldRenderContext) {
        // matrixStack is null during 1.21.1's renderSky; the camera/frustum transform is positionMatrix.
        val viewMatrix = context.positionMatrix()

        RenderSystem.depthMask(false)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()

        RenderSystem.disableBlend()
        drawOvercast(viewMatrix)

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        val driftDegrees = context.world().gameTime.toFloat() * STAR_DRIFT_DEGREES_PER_TICK
        drawStars(Matrix4f(viewMatrix).rotate(Axis.YP.rotationDegrees(driftDegrees)))

        RenderSystem.disableBlend()
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
    }

    private fun drawOvercast(matrix: Matrix4f) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (face in OVERCAST_FACES) {
            for (corner in face) {
                buffer.addColoredVertex(matrix, corner, OVERCAST_COLOR)
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    private fun drawStars(matrix: Matrix4f) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (vertexStart in starVertices.indices step 3) {
            buffer.addColoredVertex(
                matrix,
                starVertices[vertexStart],
                starVertices[vertexStart + 1],
                starVertices[vertexStart + 2],
                STAR_COLOR,
            )
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    /** The corners of a cube of the given radius, as six faces of four corners each. */
    private fun surroundingCubeFaces(radius: Float): List<List<Vec3>> {
        val low = -radius.toDouble()
        val high = radius.toDouble()
        // Winding is irrelevant here because culling is disabled while the dome is drawn.
        return listOf(
            listOf(Vec3(low, high, low), Vec3(low, high, high), Vec3(high, high, high), Vec3(high, high, low)), // top
            listOf(Vec3(low, low, high), Vec3(low, low, low), Vec3(high, low, low), Vec3(high, low, high)), // bottom
            listOf(Vec3(high, low, low), Vec3(high, high, low), Vec3(high, high, high), Vec3(high, low, high)), // east
            listOf(Vec3(low, low, high), Vec3(low, high, high), Vec3(low, high, low), Vec3(low, low, low)), // west
            listOf(Vec3(high, low, high), Vec3(high, high, high), Vec3(low, high, high), Vec3(low, low, high)), // south
            listOf(Vec3(low, low, low), Vec3(low, high, low), Vec3(high, high, low), Vec3(high, low, low)), // north
        )
    }

    /**
     * Generates the star quads: [STAR_COUNT] random points on the sky sphere, each turned into a
     * small square facing the centre and spun by a random angle, then flattened into the vertex
     * buffer. All the spherical geometry lives in [Sphere], so this reads as just "place stars".
     */
    private fun buildStarVertices(): FloatArray {
        val random = Random(STAR_SEED)
        val starSphere = Sphere(STAR_DISTANCE)
        val vertices = ArrayList<Float>(STAR_COUNT * VERTICES_PER_STAR * 3)
        repeat(STAR_COUNT) {
            val center = starSphere.randomSurfacePoint(random)
            val halfSize = MIN_STAR_SIZE + random.nextDouble() * STAR_SIZE_VARIATION
            val spin = random.nextDouble() * FULL_CIRCLE_RADIANS
            for (corner in starSphere.tangentQuad(center, halfSize, spin)) {
                vertices.add(corner.x.toFloat())
                vertices.add(corner.y.toFloat())
                vertices.add(corner.z.toFloat())
            }
        }
        return vertices.toFloatArray()
    }
}
