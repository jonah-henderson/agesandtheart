package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Tornado
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
 * The light under a tornado. Under its overcast as it gathers, the warning (Jonah): the sky and the fog darken toward a
 * stormy green and the day dims. Inside its funnel, as those who have been there tell it: an eerie blue dimness, lit
 * up as the lightning flickers ([TornadoLightning]). Laid as [MeteorGlow]'s cast is, over the lightmap's colours, and
 * read at the camera, so it is what you see from where you stand rather than a patch of lit ground.
 */
object TornadoLight {

    /** Lay the funnel's light over whatever the Age had already made of it. */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        layers.addPositionalLayer(EnvironmentAttributes.AMBIENT_LIGHT_COLOR) { was, at, _ -> tinted(level, was, at, STORM_LIGHT_SHARE) }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_COLOR) { was, at, _ -> tinted(level, was, at, STORM_LIGHT_SHARE) }
        layers.addPositionalLayer(EnvironmentAttributes.BLOCK_LIGHT_TINT) { was, at, _ -> tinted(level, was, at, NONE) }
        layers.addPositionalLayer(EnvironmentAttributes.NIGHT_VISION_COLOR) { was, at, _ -> tinted(level, was, at, NONE) }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_COLOR) { was, at, _ -> tinted(level, was, at, STORM_SKY_SHARE) }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_COLOR) { was, at, _ -> tinted(level, was, at, STORM_SKY_SHARE) }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR) { was, at, _ ->
            val (inside, flash, storm) = insideAt(level, at)
            val underIt = was + (STORM_DIMS_TO - was) * storm
            if (inside <= NONE) underIt else Mth.lerp(flash, underIt + (DIMMED_TO - underIt) * inside, LIT_UP_TO)
        }
        return layers
    }

    /**
     * [was] turned [stormShare] of the way to the storm's green as far under a gathering overcast as [at] is, then to
     * the funnel's blue as far inside one as it is, and toward white as the lightning flashes.
     */
    private fun tinted(level: ClientLevel, was: Vector3fc, at: Vec3, stormShare: Float): Vector3fc {
        val (inside, flash, storm) = insideAt(level, at)
        if (inside <= NONE && storm <= NONE) return was
        return Rgba.of(was).lerp(STORM_GREEN, storm * stormShare).lerp(EERIE, inside * EERIE_SHARE).lerp(FLASH, flash * inside).rgb()
    }

    /** How far inside a funnel a place is, how bright its lightning is flashing, and how far under a gathering storm. */
    data class Reading(val inside: Float, val flash: Float, val storm: Float)

    /**
     * How far inside a tornado's funnel [at] is, from nought outside its wall to one well within, and how bright its
     * lightning is flashing there.
     *
     * Read many times a frame by the layers above and once by the renderer, nearly always at the camera, so the last
     * answer is kept for as long as the tick and the block asked about stay the same.
     */
    fun insideAt(level: ClientLevel, at: Vec3): Reading {
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
    private var answered = Reading(NONE, NONE, NONE)

    private fun measuredAt(level: ClientLevel, at: Vec3): Reading {
        if (Tornadoes.nearNow().isEmpty()) return Reading(NONE, NONE, NONE)
        var deepest = NONE
        var flash = NONE
        var storm = NONE
        for (tornado in Tornadoes.nearNow()) {
            if (tornado.level() !== level) continue
            storm = maxOf(storm, underTheStorm(tornado, at))
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
        return Reading(deepest, flash, storm)
    }

    /**
     * How far under [tornado]'s overcast [at] stands while it gathers and stands: the whole of it under the middle of
     * the overcast, feathering out past its edge, and rising as it gathers and falling as it clears.
     */
    private fun underTheStorm(tornado: Tornado, at: Vec3): Float {
        val gathered = Mth.smoothstep(tornado.brewed) * (1.0f - Mth.smoothstep(tornado.cleared))
        if (gathered <= NONE) return NONE
        val awayX = (at.x - tornado.x).toFloat()
        val awayZ = (at.z - tornado.z).toFloat()
        val away = Mth.sqrt(awayX * awayX + awayZ * awayZ)
        val reach = TornadoRenderer.OVERCAST_REACH * gathered
        val beyond = ((away - reach * STORM_SOLID_TO) / (reach * (1.0f - STORM_SOLID_TO) + STORM_FEATHER)).coerceIn(NONE, 1.0f)
        return gathered * (1.0f - Mth.smoothstep(beyond))
    }

    /**
     * Under a gathering storm: the sky and fog go most of the way to a dark green-grey, the light a little of the way,
     * and the day dims, solid under the inner seven tenths of the overcast and feathering out past its edge.
     */
    private val STORM_GREEN = Rgba(0.30f, 0.38f, 0.32f)
    private const val STORM_SKY_SHARE = 0.75f
    private const val STORM_LIGHT_SHARE = 0.25f
    private const val STORM_DIMS_TO = 0.6f
    private const val STORM_SOLID_TO = 0.7f
    private const val STORM_FEATHER = 16.0f

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
