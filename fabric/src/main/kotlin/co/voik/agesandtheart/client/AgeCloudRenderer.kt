package co.voik.agesandtheart.client

import co.voik.agesandtheart.math.Rgba
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
 * Two dense overcast cloud layers for the Spire, replacing vanilla cloud rendering. The lower deck hides
 * the hanging spires and the upper one the peaks, leaving the walkable island bodies in the gap between.
 *
 * Each deck is a flat near-opaque slab with walled edges, coloured by slowly-animating value noise sampled
 * in world space so it roils between two tones. The decks drift at different speeds and sample different
 * regions, so the two never mirror each other.
 *
 * **The decks write depth**, or looking down through the upper one shows everything below it. And unlike
 * `renderSky`, `renderClouds` provides a `MatrixStack` — that is the transform keeping the layers
 * world-horizontal as the camera turns.
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

    /** Nudged off the block grid — see the note in [drawDeck]. */
    private const val DECK_LIFT = 0.5

    /**
     * World Y of the upper deck — also this dimension's registered cloud level and the height the
     * star-reveal band is built around. **One fact, named once**, since three copies would drift apart the
     * first time the deck is retuned.
     */
    const val UPPER_DECK_HEIGHT = 265.0

    /**
     * World Y of the lower deck: level with the islands' waist, so it hides the hanging spires and leaves
     * the walkable bodies in the gap up to [UPPER_DECK_HEIGHT]. **Rides with the terrain** — if the
     * archipelago is lifted, this must move by the same amount or it becomes a floor under everything.
     */
    const val LOWER_DECK_HEIGHT = 217.0

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
        // **Half a block up, to stop the decks z-fighting with terrain at the same level** (Jonah,
        // 2026-07-29). A deck drawn at a whole Y sits exactly on the face of the block at that height, so the
        // two planes are coplanar and which one wins is down to depth-buffer precision — it flickers as you
        // move. Offsetting by half a block puts the sheet inside the block's own space, where nothing else is.
        val baseY = (deck.height + DECK_LIFT - camera.y).toFloat()
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
