package co.voik.agesandtheart.client

import co.voik.agesandtheart.math.Rgba
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Spire's own sky, and **nothing else's** — a storm-grey overcast dome with a starfield that fades in only
 * once you climb above the upper cloud deck.
 *
 * **Deliberately bespoke, and split out of the general renderer on 2026-07-29** (Jonah, after walking it): this
 * was written for the one handcrafted Age, and its concepts — two cloud decks, stars hidden beneath them —
 * *"should not be part of the assumption for anything else"*. While the two shared a renderer, every generated
 * Age inherited a cloud model it had no say in and a star reveal keyed to a deck height it did not have.
 *
 * So this keeps its coupling to [AgeCloudRenderer] on purpose. A generated Age cannot express any of it, which is
 * accepted rather than regretted — *"part of the excitement of an easter egg"*.
 *
 * **No celestial bodies.** The Spire has never had a sun or a moon and gains none here: it is a Tier-B world
 * whose look is its own, where [AgeSkyRenderer] draws whatever an Age's [co.voik.agesandtheart.sky.SkySpec] asks
 * for. If the Spire ever wants suns, the honest way is to give it a spec like anything else.
 *
 * The dome is a subdivided box coloured by *elevation angle* (not cube-`y`), so the gradient is spherical and the
 * cube seams disappear.
 */
object SpireSkyRenderer : DimensionRenderingRegistry.SkyRenderer {
    private val ZENITH_COLOR = Rgba(0.13f, 0.16f, 0.16f) // darker overhead
    private val HORIZON_COLOR = Rgba(0.22f, 0.25f, 0.26f) // lighter toward the horizon

    /** Hazy grey matching the cloud decks; the dome blends toward this when below the upper deck. */
    private val ENVELOPED_COLOR = Rgba(0.30f, 0.33f, 0.34f)

    private const val DOME_RADIUS = 100.0
    private const val DOME_SEGMENTS = 8
    private const val STAR_DRIFT_DEGREES_PER_TICK = 0.0015f

    // Below this much reveal the stars are too faint to be worth the draw call.
    private const val STARS_WORTH_DRAWING = 0.01f

    // Stars only appear above the upper cloud deck; fade across this band. Derived from the deck rather than
    // written out, so retuning the sky's height cannot leave the stars behind at the old one. This coupling is
    // the whole reason the Spire has a renderer of its own.
    private const val STAR_REVEAL_LOW = AgeCloudRenderer.UPPER_DECK_HEIGHT - 2.0
    private const val STAR_REVEAL_HIGH = AgeCloudRenderer.UPPER_DECK_HEIGHT + 20.0

    // Stars vary along a warm→cool axis; each twinkles at its own phase and rate.
    private val WARM_STAR = Rgba(0.95f, 0.87f, 0.76f)
    private val COOL_STAR = Rgba(0.78f, 0.85f, 1.0f)
    private const val STAR_BASE_ALPHA = 0.8f
    private const val STAR_MIN_BRIGHTNESS = 0.35f
    private const val MIN_TWINKLE_SPEED = 0.04f
    private const val MAX_TWINKLE_SPEED = 0.12f

    private const val STAR_SEED = 0xA6E57A25L
    private const val STAR_COUNT = 180
    private const val STAR_DISTANCE = 100.0
    private const val MIN_STAR_SIZE = 0.20
    private const val STAR_SIZE_VARIATION = 0.15
    private const val FULL_CIRCLE_RADIANS = 2.0 * PI

    private data class Star(val corners: List<Vec3>, val baseColor: Rgba, val twinklePhase: Float, val twinkleSpeed: Float)

    private val domeQuads: List<List<Vec3>> = SkyShapes.subdividedCube(DOME_RADIUS, DOME_SEGMENTS)
    private val stars: List<Star> by lazy { buildStars() }

    override fun render(context: WorldRenderContext) {
        // `isFoggy` is false because Fabric's hook does not carry it — see `SkyShapes.isSkyHidden`.
        if (SkyShapes.isSkyHidden(context.camera(), isFoggy = false)) return

        // matrixStack is null during 1.21.1's renderSky; the camera/frustum transform is positionMatrix.
        val viewMatrix = context.positionMatrix()
        val gameTime = context.world().gameTime.toFloat()
        val time = gameTime + context.tickCounter().getGameTimeDeltaPartialTick(false)
        // 1 above the upper cloud deck, 0 below it: drives both the star reveal and the sky haze.
        val aboveTopDeck = SkyShapes.smoothstep(STAR_REVEAL_LOW, STAR_REVEAL_HIGH, context.camera().position.y)

        RenderSystem.depthMask(false)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()

        RenderSystem.disableBlend()
        drawOvercast(viewMatrix, envelopment = 1.0f - aboveTopDeck)

        if (aboveTopDeck > STARS_WORTH_DRAWING) {
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            val driftedView = Matrix4f(viewMatrix).rotate(Axis.YP.rotationDegrees(gameTime * STAR_DRIFT_DEGREES_PER_TICK))
            drawStars(driftedView, time, aboveTopDeck)
            RenderSystem.disableBlend()
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
    }

    private fun drawOvercast(matrix: Matrix4f, envelopment: Float) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        // Between/below the decks the whole dome blends toward the cloud haze; above it, the open gradient.
        val horizon = HORIZON_COLOR.lerp(ENVELOPED_COLOR, envelopment)
        val zenith = ZENITH_COLOR.lerp(ENVELOPED_COLOR, envelopment)
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (quad in domeQuads) {
            for (corner in quad) {
                // Elevation angle: 0 at the horizon, 1 straight up — lighter horizon, darker zenith.
                val elevation = (corner.y / corner.length()).coerceIn(0.0, 1.0).toFloat()
                buffer.addColoredVertex(matrix, corner, horizon.lerp(zenith, elevation))
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    private fun drawStars(matrix: Matrix4f, time: Float, visibility: Float) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (star in stars) {
            val twinkle = STAR_MIN_BRIGHTNESS +
                (1.0f - STAR_MIN_BRIGHTNESS) * (0.5f + 0.5f * sin(time * star.twinkleSpeed + star.twinklePhase))
            val color = star.baseColor.copy(alpha = star.baseColor.alpha * twinkle * visibility)
            for (corner in star.corners) {
                buffer.addColoredVertex(matrix, corner, color)
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    /** Places [STAR_COUNT] stars on the sky sphere, each a coloured, twinkling billboarded quad. */
    private fun buildStars(): List<Star> {
        val random = Random(STAR_SEED)
        val starSphere = Sphere(STAR_DISTANCE)
        return (0..<STAR_COUNT).map {
            val center = starSphere.randomSurfacePoint(random)
            val halfSize = MIN_STAR_SIZE + random.nextDouble() * STAR_SIZE_VARIATION
            val spin = random.nextDouble() * FULL_CIRCLE_RADIANS
            val corners = starSphere.tangentQuad(center, halfSize, spin)
            val baseColor = WARM_STAR.lerp(COOL_STAR, random.nextFloat()).copy(alpha = STAR_BASE_ALPHA)
            val twinklePhase = (random.nextFloat() * FULL_CIRCLE_RADIANS).toFloat()
            val twinkleSpeed = MIN_TWINKLE_SPEED + random.nextFloat() * (MAX_TWINKLE_SPEED - MIN_TWINKLE_SPEED)
            Star(corners, baseColor, twinklePhase, twinkleSpeed)
        }
    }
}
