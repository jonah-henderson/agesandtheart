package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.math.Axis
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4f
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom sky for Ages. Replaces vanilla sky rendering, so we draw everything ourselves.
 *
 * Currently: a dark storm-grey overcast dome (no sun/moon) plus a sparse, faint starfield that
 * drifts very slowly. The two cloud layers (which will eventually occlude the stars from below)
 * come next.
 */
object AgeSkyRenderer : DimensionRenderingRegistry.SkyRenderer {
    // dark desaturated storm-grey with a faint cold teal cast (Spire reference)
    private const val R = 0.16f
    private const val G = 0.19f
    private const val B = 0.19f

    // sparse, faint, pale-cool stars
    private const val STAR_COUNT = 180
    private const val STAR_R = 0.85f
    private const val STAR_G = 0.88f
    private const val STAR_B = 0.95f
    private const val STAR_A = 0.75f

    /** Star corner positions (x,y,z per vertex, 4 verts per star), built once from a fixed seed. */
    private val starVerts: FloatArray by lazy { buildStars() }
    private var logged = false

    override fun render(context: WorldRenderContext) {
        if (!logged) { Constants.LOG.info("AgeSkyRenderer drawing overcast + stars"); logged = true }
        val pos = context.positionMatrix() // camera/frustum matrix (matrixStack is null during renderSky)

        RenderSystem.depthMask(false)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()

        // 1. Opaque overcast dome.
        RenderSystem.disableBlend()
        drawDome(pos)

        // 2. Blended stars, drifting very slowly.
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        val drift = context.world().gameTime.toFloat() * 0.0015f
        drawStars(Matrix4f(pos).rotate(Axis.YP.rotationDegrees(drift)))

        // restore
        RenderSystem.disableBlend()
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
    }

    private fun drawDome(m: Matrix4f) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val d = 100.0f
        val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        quad(buf, m, -d, d, -d, -d, d, d, d, d, d, d, d, -d)      // up
        quad(buf, m, -d, -d, d, -d, -d, -d, d, -d, -d, d, -d, d)  // down
        quad(buf, m, d, -d, -d, d, d, -d, d, d, d, d, -d, d)      // +x
        quad(buf, m, -d, -d, d, -d, d, d, -d, d, -d, -d, -d, -d)  // -x
        quad(buf, m, d, -d, d, d, d, d, -d, d, d, -d, -d, d)      // +z
        quad(buf, m, -d, -d, -d, -d, d, -d, d, d, -d, d, -d, -d)  // -z
        BufferUploader.drawWithShader(buf.buildOrThrow())
    }

    private fun drawStars(m: Matrix4f) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val v = starVerts
        val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        var i = 0
        while (i < v.size) {
            buf.addVertex(m, v[i], v[i + 1], v[i + 2]).setColor(STAR_R, STAR_G, STAR_B, STAR_A)
            i += 3
        }
        BufferUploader.drawWithShader(buf.buildOrThrow())
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

    /** Vanilla-style star tessellation, thinned out: random points on a sphere as small quads. */
    private fun buildStars(): FloatArray {
        val rng = Random(0xA6E57A25L)
        val out = ArrayList<Float>(STAR_COUNT * 12)
        var made = 0
        var guard = 0
        while (made < STAR_COUNT && guard < STAR_COUNT * 64) {
            guard++
            var x = rng.nextFloat().toDouble() * 2.0 - 1.0
            var y = rng.nextFloat().toDouble() * 2.0 - 1.0
            var z = rng.nextFloat().toDouble() * 2.0 - 1.0
            val size = 0.20 + rng.nextFloat().toDouble() * 0.15 // a touch bigger than vanilla, for visibility
            val len2 = x * x + y * y + z * z
            if (len2 <= 0.010 || len2 >= 1.0) continue
            val inv = 1.0 / sqrt(len2)
            x *= inv; y *= inv; z *= inv
            val cx = x * 100.0; val cy = y * 100.0; val cz = z * 100.0
            val yaw = atan2(x, z); val sinYaw = sin(yaw); val cosYaw = cos(yaw)
            val pitch = atan2(sqrt(x * x + z * z), y); val sinPitch = sin(pitch); val cosPitch = cos(pitch)
            val spin = rng.nextDouble() * Math.PI * 2.0; val sinSpin = sin(spin); val cosSpin = cos(spin)
            for (j in 0 until 4) {
                val sx = ((j and 2) - 1).toDouble() * size
                val sy = (((j + 1) and 2) - 1).toDouble() * size
                val u = sx * cosSpin - sy * sinSpin
                val w = sy * cosSpin + sx * sinSpin
                val oy = u * sinPitch
                val t = -u * cosPitch
                val ox = t * sinYaw - w * cosYaw
                val oz = w * sinYaw + t * cosYaw
                out.add((cx + ox).toFloat())
                out.add((cy + oy).toFloat())
                out.add((cz + oz).toFloat())
            }
            made++
        }
        return out.toFloatArray()
    }
}
