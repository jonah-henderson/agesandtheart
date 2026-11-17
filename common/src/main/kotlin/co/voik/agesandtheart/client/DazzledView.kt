package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.Dazzled
import co.voik.agesandtheart.location
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Camera
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment
import net.minecraft.client.renderer.fog.environment.FogEnvironment
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.material.FogType
import org.joml.Vector3f
import org.joml.Vector3fc

/**
 * Being dazzled by a flash, as this client sees it: a glow brightens quickly over the middle of the view, never more
 * than three quarters white and fainter toward the edges, holds, and clears over a few seconds, the instant of the
 * flash left burnt over it and fading slower still; and a pale fog pulls back over the whole ten seconds, so what is
 * further off stays lost until the end.
 *
 * The white and the afterimage are a HUD layer of their own; the fog joins vanilla's list of fogs at its head, so it
 * wins over the open air's while it lasts and gives way to water's and lava's.
 */
object DazzledView {

    val HUD_LAYER: Identifier = "dazzled".location()
    private val AFTERIMAGE: Identifier = "dazzled_afterimage".location()
    private val GLOW: Identifier = "dazzled_glow".location()

    private const val GLOWS_UP_MILLIS = 200L
    private const val GLOW_HOLDS_MILLIS = 1_000L
    private const val GLOW_CLEARS_MILLIS = 4_000L

    /** The glow at its brightest, in the middle of the view. */
    private const val GLOW_STRENGTH = 0.75f

    /** What is left of the glow at the view's corners, as a share of its middle. */
    private const val GLOW_AT_THE_CORNERS = 0.3f
    private const val GLOW_WIDTH = 480
    private const val GLOW_HEIGHT = 270
    private const val AFTERIMAGE_HOLDS_MILLIS = 2_000L
    private const val AFTERIMAGE_FADES_MILLIS = 6_000L
    private const val AFTERIMAGE_STRENGTH = 0.7f

    /** How far the afterimage is washed toward white, as what was seen at a flash is all highlights. */
    private const val AFTERIMAGE_WASH = 0.35f

    /** The afterimage is kept at half the screen's size, softer and a quarter of the memory. */
    private const val AFTERIMAGE_SHRINK = 2

    private const val OPAQUE = 255

    private var dazzledAt: Long? = null
    private var ticksLeftLastSeen = 0
    private var afterimageSize: Pair<Int, Int>? = null
    private var isGlowMade = false

    /** Adds the fog to vanilla's. Called once by each loader's client entrypoint. */
    fun install() {
        FogRenderer.FOG_ENVIRONMENTS.add(0, DazzledFog)
    }

    /** A fresh dazzle starts the white and keeps the frame it came on; called each client tick. */
    fun tick(minecraft: Minecraft) {
        val ticksLeft = minecraft.player?.getEffect(Dazzled.EFFECT)?.duration
        if (ticksLeft == null) {
            ticksLeftLastSeen = 0
            dazzledAt = null
            return
        }
        val isFresh = ticksLeft > ticksLeftLastSeen
        ticksLeftLastSeen = ticksLeft
        if (!isFresh) return
        dazzledAt = System.currentTimeMillis()
        keepTheFrame(minecraft)
    }

    fun draw(graphics: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val since = System.currentTimeMillis() - (dazzledAt ?: return)
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val glowingUp = easedOver(since, GLOWS_UP_MILLIS)
        val afterimage = AFTERIMAGE_STRENGTH * glowingUp * (1.0f - easedOver(since - AFTERIMAGE_HOLDS_MILLIS, AFTERIMAGE_FADES_MILLIS))
        val size = afterimageSize
        if (afterimage > 0.0f && size != null) {
            val (textureWidth, textureHeight) = size
            graphics.blit(
                RenderPipelines.GUI_TEXTURED, AFTERIMAGE, 0, 0, 0.0f, 0.0f, width, height,
                textureWidth, textureHeight, textureWidth, textureHeight, ARGB.white(afterimage),
            )
        }
        val glow = GLOW_STRENGTH * glowingUp * (1.0f - easedOver(since - GLOW_HOLDS_MILLIS, GLOW_CLEARS_MILLIS))
        if (glow > 0.0f) {
            makeTheGlow()
            graphics.blit(
                RenderPipelines.GUI_TEXTURED, GLOW, 0, 0, 0.0f, 0.0f, width, height,
                GLOW_WIDTH, GLOW_HEIGHT, GLOW_WIDTH, GLOW_HEIGHT, ARGB.white(glow),
            )
        }
    }

    /** White, its alpha whole in the middle and falling away smoothly to [GLOW_AT_THE_CORNERS] at the corners. */
    private fun makeTheGlow() {
        if (isGlowMade) return
        val glow = NativeImage(GLOW_WIDTH, GLOW_HEIGHT, false)
        val halfWidth = GLOW_WIDTH / 2.0f
        val halfHeight = GLOW_HEIGHT / 2.0f
        for (y in 0..<GLOW_HEIGHT) {
            for (x in 0..<GLOW_WIDTH) {
                val across = (x + 0.5f - halfWidth) / halfWidth
                val down = (y + 0.5f - halfHeight) / halfHeight
                // One at the corners, measured on the view as it is shaped.
                val out = Mth.sqrt((across * across + down * down) / 2.0f).coerceAtMost(1.0f)
                val fallen = out * out * (3.0f - 2.0f * out)
                val alpha = 1.0f - (1.0f - GLOW_AT_THE_CORNERS) * fallen
                glow.setPixel(x, y, ARGB.white(alpha))
            }
        }
        Minecraft.getInstance().textureManager.register(GLOW, DynamicTexture({ "Dazzled glow" }, glow))
        isGlowMade = true
    }

    fun forget() {
        dazzledAt = null
        ticksLeftLastSeen = 0
        afterimageSize = null
        Minecraft.getInstance().textureManager.release(AFTERIMAGE)
        Minecraft.getInstance().textureManager.release(GLOW)
        isGlowMade = false
    }

    /** Nought to one over [length] from [elapsed] nought, eased at both ends. */
    private fun easedOver(elapsed: Long, length: Long): Float {
        val progress = (elapsed.toFloat() / length).coerceIn(0.0f, 1.0f)
        return progress * progress * (3.0f - 2.0f * progress)
    }

    /** The last frame drawn, which is the instant before the flash, shrunk, washed pale, and kept as a texture. */
    private fun keepTheFrame(minecraft: Minecraft) {
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget()) { screen ->
            val width = screen.width / AFTERIMAGE_SHRINK
            val height = screen.height / AFTERIMAGE_SHRINK
            val afterimage = NativeImage(width, height, false)
            screen.resizeSubRectTo(0, 0, screen.width, screen.height, afterimage)
            screen.close()
            for (y in 0..<height) {
                for (x in 0..<width) {
                    val washed = ARGB.srgbLerp(AFTERIMAGE_WASH, afterimage.getPixel(x, y), -1)
                    afterimage.setPixel(x, y, ARGB.color(OPAQUE, washed))
                }
            }
            minecraft.textureManager.register(AFTERIMAGE, DynamicTexture({ "Dazzled afterimage" }, afterimage))
            afterimageSize = width to height
        }
    }

    /**
     * The pale fog, some blocks off at first and back at the render distance when the dazzle ends, leaning only partly
     * toward white so it does not white the view out by itself.
     */
    private object DazzledFog : FogEnvironment() {
        private const val NEAREST = 8.0f
        private const val FOG_START_SHARE = 0.25f
        private const val PALENESS = 0.4f
        private val PALE: Vector3fc = Vector3f(1.0f, 1.0f, 1.0f)
        private val openAir = AtmosphericFogEnvironment()

        override fun isApplicable(fogType: FogType?, entity: Entity): Boolean {
            val isInTheOpenAir = fogType == FogType.ATMOSPHERIC || fogType == FogType.NONE
            return isInTheOpenAir && entity is LivingEntity && entity.hasEffect(Dazzled.EFFECT)
        }

        override fun getBaseColor(level: ClientLevel, camera: Camera, renderDistance: Int, partialTicks: Float): Vector3fc {
            val air = openAir.getBaseColor(level, camera, renderDistance, partialTicks)
            val paleness = PALENESS * (1.0f - recovery(camera.entity()))
            return Vector3f(
                Mth.lerp(paleness, air.x(), PALE.x()),
                Mth.lerp(paleness, air.y(), PALE.y()),
                Mth.lerp(paleness, air.z(), PALE.z()),
            )
        }

        override fun setupFog(fog: FogData, camera: Camera, level: ClientLevel, renderDistance: Float, deltaTracker: DeltaTracker) {
            val recovered = recovery(camera.entity())
            val distance = Mth.lerp(recovered * recovered, NEAREST, renderDistance)
            fog.environmentalStart = distance * FOG_START_SHARE
            fog.environmentalEnd = distance
            fog.skyEnd = distance
            fog.cloudEnd = distance
        }

        /** Nought when the dazzle is fresh, one as it ends. */
        private fun recovery(entity: Entity?): Float {
            val effect = (entity as? LivingEntity)?.getEffect(Dazzled.EFFECT) ?: return 1.0f
            return 1.0f - (effect.duration.toFloat() / Dazzled.LASTS_FOR_TICKS).coerceIn(0.0f, 1.0f)
        }
    }
}
