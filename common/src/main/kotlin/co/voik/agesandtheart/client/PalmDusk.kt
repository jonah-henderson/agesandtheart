package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.aspect.Biomes
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Vector4f

/**
 * **A palm beach's sunsets, deeper, hotter and higher than anywhere else's** (Jonah, 2026-10-01: "anything we
 * can do to make sunsets especially spectacular"; walked: "very, very nice", the fan wanted taller and the
 * clouds tinted). Laid over the Age's own air, as every look of ours is.
 *
 * The day's own timeline already decides *when* the sky glows and how strongly, in the sunrise colour's
 * alpha; this leaves that timing alone and changes what it glows. Over a palm beach:
 *
 * - **the glow** is a third stronger and runs from vanilla's yellow-orange towards a red coral;
 * - **the fan** stands [TALLER] times its height, so more of the sky takes the colour — ephemeris's
 *   `LevelRendering.horizonHeight`, which both vanilla's fan and ephemeris's own read ([standsTaller]);
 * - **the clouds** are whiter and less see-through all day, and at dawn and dusk are lit a hot peach in step
 *   with the glow, all the way, so they look lit from within (Jonah, walked: "glowing"). A flat tint,
 *   not one that brightens towards the sun: the cloud mesh is drawn in one colour, and a tint by bearing
 *   would want a cloud shader of our own.
 *
 * A palm beach at noon keeps its colours; only dawn and dusk are different, and the clouds.
 *
 * Laid unconditionally and asking about the biome at each sample, because this runs once when the level's
 * attribute system is built; outside a palm beach it hands back what it was given.
 */
object PalmDusk {

    private val PALM_BEACH = ResourceKey.create(Registries.BIOME, Biomes.PALM_BEACH_BIOME)

    /**
     * How strongly the horizon glowed when last asked, from the day's own timeline — which is what the
     * clouds' warmth follows. Read off the sunrise layer as it passes, since a layer cannot ask another
     * attribute; a frame old at worst.
     */
    @Volatile
    private var glowing = 0.0f

    /** How tall the fan stands over the camera now, eased so walking off a palm beach does not snap it. */
    private var stretch = 1.0f

    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.SUNRISE_SUNSET_COLOR) { was, at, _ ->
            glowing = was.w()
            if (!onAPalmBeach(level, at.x, at.y, at.z)) return@addPositionalLayer was
            Vector4f(
                Mth.lerp(HOTTER, was.x(), CORAL_RED),
                Mth.lerp(HOTTER, was.y(), CORAL_GREEN),
                Mth.lerp(HOTTER, was.z(), CORAL_BLUE),
                (was.w() * STRONGER).coerceAtMost(1.0f),
            )
        }
        layers.addPositionalLayer(EnvironmentAttributes.CLOUD_COLOR) { was, at, _ ->
            if (!onAPalmBeach(level, at.x, at.y, at.z)) return@addPositionalLayer was
            // The timeline has already darkened the clouds for the hour, so whitening is a share of the way
            // to the brightest it allows, and the warmth rides the glow.
            val warm = glowing * WARMEST
            Vector4f(
                Mth.lerp(warm, was.x(), PEACH_RED),
                Mth.lerp(warm, was.y(), PEACH_GREEN),
                Mth.lerp(warm, was.z(), PEACH_BLUE),
                Mth.lerp(SOLIDER, was.w(), 1.0f),
            )
        }
        return layers
    }

    /**
     * How much taller the sunset fan stands for [camera] — one off a palm beach. Registered with ephemeris
     * (`LevelRendering.horizonHeight`), which asks it each frame a fan is drawn.
     *
     * **Eased rather than switched**, since this is asked of the camera's own column and a biome's edge would
     * otherwise snap the fan from one height to the other. An attribute would have blended it for free, and
     * would have meant ephemeris registering game content of its own.
     */
    fun standsTaller(level: ClientLevel, camera: Camera): Float {
        val eye = camera.position()
        val goal = if (onAPalmBeach(level, eye.x, eye.y, eye.z)) TALLER else 1.0f
        stretch = Mth.lerp(EASES, stretch, goal)
        return stretch
    }

    private fun onAPalmBeach(level: ClientLevel, x: Double, y: Double, z: Double): Boolean =
        level.getBiome(BlockPos.containing(x, y, z)).`is`(PALM_BEACH)

    /** How far the glow's colour moves from vanilla's towards coral, and how much stronger it is. */
    private const val HOTTER = 0.5f
    private const val STRONGER = 1.35f

    /** A red coral, #FF3D2E — redder than the first, for contrast against the blue (Jonah, walked). */
    private const val CORAL_RED = 1.0f
    private const val CORAL_GREEN = 0.24f
    private const val CORAL_BLUE = 0.18f

    /** How much taller the fan stands, and how much of the way there it moves each frame. */
    private const val TALLER = 2.5f
    private const val EASES = 0.05f

    /** A hot peach for the clouds at the height of the glow, all the way there, so they glow. */
    private const val WARMEST = 1.0f
    private const val PEACH_RED = 1.0f
    private const val PEACH_GREEN = 0.6f
    private const val PEACH_BLUE = 0.45f

    /** How much of the way to fully opaque the clouds move — vanilla's are a fifth see-through. */
    private const val SOLIDER = 0.75f
}
