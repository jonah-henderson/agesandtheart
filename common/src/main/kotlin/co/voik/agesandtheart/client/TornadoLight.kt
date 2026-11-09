package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.TornadoWinds
import co.voik.ephemeris.Rgba
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc

/**
 * The light inside a tornado's funnel, as those who have been there tell it: an eerie blue dimness, lit up as the
 * lightning flickers ([TornadoLightning]). Laid as [MeteorGlow]'s cast is, over all four of the lightmap's colours,
 * and read at the camera, so it is what you see from inside the funnel rather than a patch of lit ground.
 */
object TornadoLight {

    /** Lay the funnel's light over whatever the Age had already made of it. */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.AMBIENT_LIGHT_COLOR) { was, at, _ -> tinted(level, was, at) }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_COLOR) { was, at, _ -> tinted(level, was, at) }
        layers.addPositionalLayer(EnvironmentAttributes.BLOCK_LIGHT_TINT) { was, at, _ -> tinted(level, was, at) }
        layers.addPositionalLayer(EnvironmentAttributes.NIGHT_VISION_COLOR) { was, at, _ -> tinted(level, was, at) }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_COLOR) { was, at, _ -> tinted(level, was, at) }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR) { was, at, _ ->
            val (inside, flash) = insideAt(level, at)
            if (inside <= NONE) was else Mth.lerp(flash, was + (DIMMED_TO - was) * inside, LIT_UP_TO)
        }
        return layers
    }

    /** [was] turned to the funnel's blue as far inside one as [at] is, and toward white as the lightning flashes. */
    private fun tinted(level: ClientLevel, was: Vector3fc, at: Vec3): Vector3fc {
        val (inside, flash) = insideAt(level, at)
        if (inside <= NONE) return was
        return Rgba.of(was).lerp(EERIE, inside * EERIE_SHARE).lerp(FLASH, flash * inside).rgb()
    }

    /**
     * How far inside a tornado's funnel [at] is, from nought outside its wall to one well within, and how bright its
     * lightning is flashing there.
     *
     * Read many times a frame by the layers above and once by the renderer, nearly always at the camera, so the last
     * answer is kept for as long as the tick and the block asked about stay the same.
     */
    fun insideAt(level: ClientLevel, at: Vec3): Pair<Float, Float> {
        val block = BlockPos.containing(at)
        val now = level.gameTime
        if (now == askedAt && block == askedAbout && level === askedIn) return answered
        askedAt = now
        askedAbout = block
        askedIn = level
        answered = measuredAt(level, at)
        return answered
    }

    private var askedAt = Long.MIN_VALUE
    private var askedAbout: BlockPos? = null
    private var askedIn: ClientLevel? = null
    private var answered = NONE to NONE

    private fun measuredAt(level: ClientLevel, at: Vec3): Pair<Float, Float> {
        if (Tornadoes.nearNow().isEmpty()) return NONE to NONE
        var deepest = NONE
        var flash = NONE
        for (tornado in Tornadoes.nearNow()) {
            if (tornado.level() !== level) continue
            val vortex = TornadoWinds.shapeOf(tornado, 1.0)
            if (!vortex.isBeside(at.y)) continue
            val core = vortex.coreAt(at.y)
            val out = vortex.bearingsOf(at.x, at.y, at.z).out
            val towardTheWall = ((out - core * WALL_FROM) / (core * WALL_THICKNESS)).coerceIn(0.0, 1.0).toFloat()
            val inside = 1.0f - Mth.smoothstep(towardTheWall)
            val formed = inside * tornado.descended * (1.0f - tornado.roped)
            if (formed > deepest) {
                deepest = formed
                flash = TornadoLightning.flashOf(tornado, level.gameTime)
            }
        }
        return deepest to flash
    }

    /** The funnel's light: a cold, dim blue, and white as lightning flashes. */
    private val EERIE = Rgba(0.42f, 0.55f, 0.85f)
    private val FLASH = Rgba(0.9f, 0.93f, 1.0f)
    private const val EERIE_SHARE = 0.7f

    /** How far the sky's light is let in, deep inside, and how far a flash lifts it. */
    private const val DIMMED_TO = 0.45f
    private const val LIT_UP_TO = 1.0f

    /** Inside from four fifths of the way out to the wall, and out by a fifth beyond it. */
    private const val WALL_FROM = 0.8
    private const val WALL_THICKNESS = 0.4

    private const val NONE = 0.0f
}
