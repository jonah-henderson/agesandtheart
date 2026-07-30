package co.voik.agesandtheart.client

import co.voik.agesandtheart.math.Rgba
import co.voik.agesandtheart.math.Sphere
import co.voik.agesandtheart.sky.Appearance
import co.voik.agesandtheart.sky.CelestialBody
import co.voik.agesandtheart.sky.KnownSkies
import co.voik.agesandtheart.sky.SkySpec
import co.voik.agesandtheart.sky.StarField
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * The sky **any** generated Age can have: whatever suns and moons it was written with, its own stars, and a dome
 * borrowing vanilla's own sky colour.
 *
 * **In `common` so both loaders draw the same sky.** Each owns only its hook — Fabric a
 * `DimensionRenderingRegistry.SkyRenderer`, NeoForge an `IDimensionSpecialEffectsExtension.renderSky` —
 * and both call [draw], whose parameters are the intersection of what the two hooks are handed.
 *
 * `common` compiles against the merged jar, so `RenderSystem` resolves here. The rule keeping that honest:
 * **nothing server-side may reach this class**, and nothing does.
 *
 * **Assumes nothing beyond the Age's [SkySpec]** — the Spire's cloud decks and overcast stayed with the
 * Spire's own renderer. So the dome is `ClientLevel.getSkyColor` and star visibility is
 * `getStarBrightness`, both vanilla's; and there is no sunrise glow or void plane, both of which live in
 * the method the hooks suppress.
 */
object AgeSky {

    private const val DOME_RADIUS = 100.0
    private const val DOME_SEGMENTS = 4

    // Below this much brightness the stars are too faint to be worth the draw call.
    private const val STARS_WORTH_DRAWING = 0.01f

    // Stars vary along a warm→cool axis; each twinkles at its own phase and rate.
    private val WARM_STAR = Rgba(0.95f, 0.87f, 0.76f)
    private val COOL_STAR = Rgba(0.78f, 0.85f, 1.0f)
    private const val STAR_MIN_BRIGHTNESS = 0.35f
    private const val MIN_TWINKLE_SPEED = 0.04f
    private const val MAX_TWINKLE_SPEED = 0.12f

    private const val STAR_DISTANCE = 100.0
    private const val MIN_STAR_SIZE = 0.20
    private const val STAR_SIZE_VARIATION = 0.15
    private const val FULL_CIRCLE_RADIANS = 2.0 * PI

    private data class Star(val corners: List<Vec3>, val baseColor: Rgba, val twinklePhase: Float, val twinkleSpeed: Float)

    /** Camera-surrounding box for the dome. Coarse, since this one is a flat colour rather than a gradient. */
    private val domeQuads: List<List<Vec3>> = SkyShapes.subdividedCube(DOME_RADIUS, DOME_SEGMENTS)

    /**
     * Built star fields, keyed by the field that asked for them, so two Ages with the same count and seed
     * share one. Never cleared: a field is a few thousand small records and a player visits a bounded
     * number of Ages, so eviction bookkeeping would cost more than the memory it saves.
     */
    private val starFields = mutableMapOf<StarField, List<Star>>()

    /**
     * Draws the sky. Returns false when it drew nothing, so a caller may let vanilla proceed. [view] must
     * be camera **rotation only** — vanilla's `frustumMatrix`, which both hooks supply; adding the camera
     * position would slide the sky around the world instead of surrounding the viewer.
     */
    fun draw(view: Matrix4f, level: ClientLevel, camera: Camera, partialTick: Float, isFoggy: Boolean): Boolean {
        if (SkyShapes.isSkyHidden(camera, isFoggy)) return false

        val time = level.gameTime.toFloat() + partialTick
        // Null for an Age nobody told us about, which the first frames of a first connection genuinely are. The
        // dome still goes up, because the caller has already suppressed vanilla's sky and returning here would
        // leave a black void.
        val spec = KnownSkies.of(level.dimension())
        val unrained = 1.0f - level.getRainLevel(partialTick)

        RenderSystem.depthMask(false)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()

        RenderSystem.disableBlend()
        drawDome(Matrix4f(view), level.getSkyColor(camera.position, partialTick))

        if (spec != null) {
            // Vanilla's own additive blend: bodies add light to the sky rather than being pasted over it, which is
            // why its sun reads as a light source. It also means overlapping bodies brighten instead of occluding —
            // Tier 1 accepts that, and the far-to-near ordering in `drawBodies` is where an eclipse would hook in.
            RenderSystem.enableBlend()
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO,
            )
            // `partialTick` reaches the *brightness* but never the orbit. A rain level is a smoothed value and
            // interpolates correctly; a tick counter is not, and adding a fraction to one ratchets — see
            // `Orbit.progressAt`, where that bug is written up.
            drawBodies(Matrix4f(view), spec, level.dayTime(), unrained)
            RenderSystem.disableBlend()
            RenderSystem.defaultBlendFunc()
        }

        // Vanilla's own night curve, and ours to use freely: `getStarBrightness` is read only by the method our
        // callers suppress. Rain dims the stars the same way it dims the bodies.
        val starlight = level.getStarBrightness(partialTick) * unrained
        val stars = spec?.stars
        if (stars != null && stars.count > 0 && starlight > STARS_WORTH_DRAWING) {
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            drawStars(Matrix4f(view), starsOf(stars), time, starlight)
            RenderSystem.disableBlend()
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        return true
    }

    /** Vanilla's sky colour over the whole box — flat, as vanilla's own sky disc is. */
    private fun drawDome(matrix: Matrix4f, skyColor: Vec3) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val color = Rgba(skyColor.x.toFloat(), skyColor.y.toFloat(), skyColor.z.toFloat())
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (quad in domeQuads) {
            for (corner in quad) buffer.addColoredVertex(matrix, corner, color)
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    /**
     * Every sun and moon the Age has, in as few draws as there are distinct textures. **Sorted far to near
     * before grouping**, which buys nothing under additive blending and is where an eclipse would hook in —
     * ordering being the only tool available, the sky pass writing no depth.
     */
    private fun drawBodies(view: Matrix4f, spec: SkySpec, dayTime: Long, brightness: Float) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
        val farthestFirst = spec.bodies.sortedByDescending { it.orbit.distance }
        for ((texture, bodies) in farthestFirst.groupBy { (it.appearance as Appearance.Sprite).texture }) {
            RenderSystem.setShaderTexture(0, texture)
            val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
            for (body in bodies) emitBody(buffer, view, body, dayTime, brightness)
            BufferUploader.drawWithShader(buffer.buildOrThrow())
        }
    }

    /**
     * One body as a quad facing the camera, its orbit **baked into the vertices** because
     * `RenderSystem.getModelViewMatrix()` is the identity during the sky pass and the transform has
     * nowhere else to live. The quad lies flat at `y = distance` and matches vanilla's corner and UV
     * order, so a one-day body is indistinguishable from vanilla's own sun.
     */
    private fun emitBody(
        buffer: BufferBuilder,
        view: Matrix4f,
        body: CelestialBody,
        dayTime: Long,
        brightness: Float,
    ) {
        val sprite = body.appearance as Appearance.Sprite
        val placed = Matrix4f(view).rotate(body.orbit.rotationAt(dayTime))
        val distance = body.orbit.distance
        val half = sprite.angularSize

        val cell = body.phase?.stepAt(dayTime) ?: 0
        val column = cell % sprite.columns
        val row = (cell / sprite.columns) % sprite.rows
        val u0 = column.toFloat() / sprite.columns
        val u1 = (column + 1).toFloat() / sprite.columns
        val v0 = row.toFloat() / sprite.rows
        val v1 = (row + 1).toFloat() / sprite.rows

        val tint = sprite.tint
        fun corner(x: Float, z: Float, u: Float, v: Float) {
            buffer.addVertex(placed, x, distance, z)
                .setUv(u, v)
                .setColor(tint.red, tint.green, tint.blue, tint.alpha * brightness)
        }
        corner(-half, -half, u0, v0)
        corner(half, -half, u1, v0)
        corner(half, half, u1, v1)
        corner(-half, half, u0, v1)
    }

    private fun drawStars(matrix: Matrix4f, stars: List<Star>, time: Float, visibility: Float) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (star in stars) {
            val twinkle = STAR_MIN_BRIGHTNESS +
                (1.0f - STAR_MIN_BRIGHTNESS) * (0.5f + 0.5f * sin(time * star.twinkleSpeed + star.twinklePhase))
            val color = star.baseColor.copy(alpha = twinkle * visibility)
            for (corner in star.corners) buffer.addColoredVertex(matrix, corner, color)
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    /** This Age's stars, built once and kept. */
    private fun starsOf(field: StarField): List<Star> = starFields.getOrPut(field) { buildStars(field) }

    /** Places [StarField.count] stars on the sky sphere, each a coloured, twinkling billboarded quad. */
    private fun buildStars(field: StarField): List<Star> {
        val random = Random(field.seed)
        val starSphere = Sphere(STAR_DISTANCE)
        return (0..<field.count).map {
            val center = starSphere.randomSurfacePoint(random)
            val halfSize = MIN_STAR_SIZE + random.nextDouble() * STAR_SIZE_VARIATION
            val spin = random.nextDouble() * FULL_CIRCLE_RADIANS
            val corners = starSphere.tangentQuad(center, halfSize, spin)
            val baseColor = WARM_STAR.lerp(COOL_STAR, random.nextFloat())
            val twinklePhase = (random.nextFloat() * FULL_CIRCLE_RADIANS).toFloat()
            val twinkleSpeed = MIN_TWINKLE_SPEED + random.nextFloat() * (MAX_TWINKLE_SPEED - MIN_TWINKLE_SPEED)
            Star(corners, baseColor, twinklePhase, twinkleSpeed)
        }
    }
}
