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
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

/**
 * Two dense overcast cloud layers for Ages (Spire). Replaces vanilla cloud rendering.
 *
 * The lower deck sits above the sea and hides the lower reaches of the hanging spires; the upper
 * deck hides the peaks — leaving the walkable island bodies in the clear gap between them. Each
 * deck is a flat, near-opaque slab about vanilla-cloud thick (top + bottom surfaces + walled edges
 * so it isn't hollow), shaded a little lighter on top than underneath.
 *
 * Colour comes from slowly-animating value noise (sampled in world space, then contrast-curved) so
 * each deck roils between two tones: the upper deck between cool blue-grey dark spots and light
 * grey; the lower deck between near-black and lighter grey foam. Each deck drifts at its own speed
 * and samples a different region of the noise field, so the two layers never mirror each other. The
 * surfaces stay flat — no lumps.
 *
 * The decks write depth (depthMask on) so they properly occlude the terrain and each other — without
 * that, looking down through the upper deck from above shows everything below it.
 *
 * Unlike the sky (renderSky, raw matrices), renderClouds provides a MatrixStack, and that is the
 * transform which keeps the layers world-horizontal as the camera turns.
 */
object AgeCloudRenderer : DimensionRenderingRegistry.CloudRenderer {
    private const val RADIUS = 512.0f
    private const val GRID_STEP = 32.0f
    private const val HALF_THICKNESS = 2.0f // ~4 blocks, like vanilla clouds
    private const val TOP_BRIGHTNESS = 1.0f
    private const val BOTTOM_BRIGHTNESS = 0.72f
    private const val SIDE_BRIGHTNESS = 0.84f
    private const val CONTRAST = 1.6f // spreads the noise toward its extremes for defined spots

    /** One cloud layer: where it sits, its two roiling tones, how fast it drifts, and its noise region. */
    private data class Deck(
        val height: Double,
        val low: Rgba,
        val high: Rgba,
        val driftSpeed: Float,
        val noiseOffsetX: Double,
        val noiseOffsetZ: Double,
    )

    /**
     * World Y of the upper deck. Also this dimension's registered cloud level (see [AgeDimensionEffects])
     * and the height [AgeSkyRenderer]'s star-reveal band is built around — one fact, named once, because
     * three copies of it would drift apart the first time the deck is retuned.
     */
    const val UPPER_DECK_HEIGHT = 265.0

    /**
     * World Y of the lower deck. The 120-block gap up to [UPPER_DECK_HEIGHT] is close to vanilla's own
     * ground-to-cloud distance, which is the point: the band should feel like a sky you are inside
     * rather than a lid. Both decks sit high above the sea at y=63, so the archipelago reads as being
     * genuinely far up rather than hovering over the water.
     */
    const val LOWER_DECK_HEIGHT = 145.0

    // Upper deck: mostly light grey, with cool blue-grey darker spots. Drifts faster.
    private val UPPER_DECK = Deck(
        height = UPPER_DECK_HEIGHT,
        low = Rgba(0.16f, 0.17f, 0.22f),
        high = Rgba(0.47f, 0.50f, 0.51f),
        driftSpeed = 0.045f,
        noiseOffsetX = 0.0,
        noiseOffsetZ = 0.0,
    )

    // Lower deck: mostly near-black, with lighter grey foam. Drifts slower, and offset far into the
    // noise field so its pattern is uncorrelated with the upper deck's.
    private val LOWER_DECK = Deck(
        height = LOWER_DECK_HEIGHT,
        low = Rgba(0.12f, 0.14f, 0.15f),
        high = Rgba(0.42f, 0.44f, 0.45f),
        driftSpeed = 0.015f,
        noiseOffsetX = 9000.0,
        noiseOffsetZ = 4000.0,
    )

    override fun render(context: WorldRenderContext) {
        val matrix = context.matrixStack()?.last()?.pose() ?: context.positionMatrix()
        val camera = context.camera().position
        val time = context.world().gameTime.toFloat() + context.tickCounter().getGameTimeDeltaPartialTick(false)

        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.depthMask(true) // write depth so the decks occlude terrain and each other
        RenderSystem.disableCull()

        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        drawDeck(buffer, matrix, UPPER_DECK, camera, time)
        drawDeck(buffer, matrix, LOWER_DECK, camera, time)
        BufferUploader.drawWithShader(buffer.buildOrThrow())

        RenderSystem.disableBlend()
        RenderSystem.enableCull()
    }

    /** A flat, closed cloud slab centred on the camera, roiling between the deck's two tones. */
    private fun drawDeck(buffer: VertexConsumer, matrix: Matrix4f, deck: Deck, camera: Vec3, time: Float) {
        val baseY = (deck.height - camera.y).toFloat()
        drawSurface(buffer, matrix, baseY + HALF_THICKNESS, deck, camera, time, TOP_BRIGHTNESS)
        drawSurface(buffer, matrix, baseY - HALF_THICKNESS, deck, camera, time, BOTTOM_BRIGHTNESS)
        drawWalls(buffer, matrix, baseY, deck)
    }

    private fun drawSurface(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        surfaceY: Float,
        deck: Deck,
        camera: Vec3,
        time: Float,
        brightness: Float,
    ) {
        val cells = (RADIUS * 2 / GRID_STEP).toInt()
        for (ix in 0..<cells) {
            val x0 = -RADIUS + ix * GRID_STEP
            val x1 = x0 + GRID_STEP
            for (iz in 0..<cells) {
                val z0 = -RADIUS + iz * GRID_STEP
                val z1 = z0 + GRID_STEP
                surfaceVertex(buffer, matrix, x0, surfaceY, z0, deck, camera, time, brightness)
                surfaceVertex(buffer, matrix, x0, surfaceY, z1, deck, camera, time, brightness)
                surfaceVertex(buffer, matrix, x1, surfaceY, z1, deck, camera, time, brightness)
                surfaceVertex(buffer, matrix, x1, surfaceY, z0, deck, camera, time, brightness)
            }
        }
    }

    private fun surfaceVertex(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        localX: Float,
        surfaceY: Float,
        localZ: Float,
        deck: Deck,
        camera: Vec3,
        time: Float,
        brightness: Float,
    ) {
        val sampleX = camera.x + localX + deck.noiseOffsetX
        val sampleZ = camera.z + localZ + deck.noiseOffsetZ
        val density = cloudDensity(sampleX, sampleZ, time, deck.driftSpeed)
        val toned = ((density - 0.5f) * CONTRAST + 0.5f).coerceIn(0.0f, 1.0f)
        val base = deck.low.lerp(deck.high, toned)
        val alpha = 0.92f + 0.08f * density // near-opaque
        buffer.addVertex(matrix, localX, surfaceY, localZ)
            .setColor(base.red * brightness, base.green * brightness, base.blue * brightness, alpha)
    }

    /** The four perimeter walls that close the slab so it isn't hollow at the edges. */
    private fun drawWalls(buffer: VertexConsumer, matrix: Matrix4f, baseY: Float, deck: Deck) {
        val top = baseY + HALF_THICKNESS
        val bottom = baseY - HALF_THICKNESS
        val mid = deck.low.lerp(deck.high, 0.5f)
        val red = mid.red * SIDE_BRIGHTNESS
        val green = mid.green * SIDE_BRIGHTNESS
        val blue = mid.blue * SIDE_BRIGHTNESS
        fun corner(x: Float, y: Float, z: Float) = buffer.addVertex(matrix, x, y, z).setColor(red, green, blue, 1.0f)
        // +x
        corner(RADIUS, top, -RADIUS); corner(RADIUS, top, RADIUS); corner(RADIUS, bottom, RADIUS); corner(RADIUS, bottom, -RADIUS)
        // -x
        corner(-RADIUS, top, RADIUS); corner(-RADIUS, top, -RADIUS); corner(-RADIUS, bottom, -RADIUS); corner(-RADIUS, bottom, RADIUS)
        // +z
        corner(RADIUS, top, RADIUS); corner(-RADIUS, top, RADIUS); corner(-RADIUS, bottom, RADIUS); corner(RADIUS, bottom, RADIUS)
        // -z
        corner(-RADIUS, top, -RADIUS); corner(RADIUS, top, -RADIUS); corner(RADIUS, bottom, -RADIUS); corner(-RADIUS, bottom, -RADIUS)
    }

    // The four sine amplitudes below, summed — what the total has to be divided by to land back in
    // -1..1. Derived, so it must be updated with them; the frequencies themselves are free.
    private const val SINE_AMPLITUDE_SUM = 1.0 + 0.7 + 0.5 + 0.4

    /** Slowly-animating value noise in `0.0..1.0` — a cheap sum of drifting sines at [driftSpeed]. */
    private fun cloudDensity(x: Double, z: Double, time: Float, driftSpeed: Float): Float {
        val t = time * driftSpeed
        var value = sin(x * 0.018 + t)
        value += 0.7 * sin(z * 0.021 - t * 0.9)
        value += 0.5 * sin((x + z) * 0.012 + t * 1.4)
        value += 0.4 * cos((x - z) * 0.015 - t * 0.7)
        return ((value / SINE_AMPLITUDE_SUM) * 0.5 + 0.5).toFloat().coerceIn(0.0f, 1.0f)
    }
}
