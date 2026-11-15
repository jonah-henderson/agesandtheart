package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.cataclysm.Doom
import co.voik.agesandtheart.age.cataclysm.ImpactPhase
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.Glow
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyBlend
import co.voik.ephemeris.client.SkyCorner
import co.voik.ephemeris.client.SkyMoment
import co.voik.ephemeris.client.SkyQuad
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An asteroid's end, drawn in the sky of the Age it ends (design §5.3 "The asteroid").
 *
 * **The asteroid is the clock.** It hangs on a real straight entry line toward a point far past the horizon, so for
 * most of its approach it barely moves and only grows, and at the end it sweeps down and out of sight. Then the
 * flash, the fireball rising beyond the horizon, the glow of the debris coming back down climbing over the sky from
 * that bearing until it is overhead, and the dust drawn over all of it.
 *
 * **Everything is arithmetic over the schedule and the clock**, as the meteor storm's lights are: where the
 * asteroid is at any instant is [sightingOf], and the server never says.
 *
 * The world is flat and the impact far off, so it falls **relative to the eye**: wherever you stand, it comes down
 * the same distance away, across the planet.
 */
object ImpactSky {

    fun register() {
        LevelRendering.skyOverlay(::draw)
    }

    private fun draw(moment: SkyMoment) {
        val doom = Impacts.comingTo(moment.level) ?: return
        val time = moment.level.gameTime + moment.partOfATickOn.toDouble()
        val phase = doom.phaseAt(time.toLong()) ?: return
        val glows = mutableListOf<Glow>()
        val light = mutableListOf<SkyQuad>()
        val shade = mutableListOf<SkyQuad>()
        if (phase == ImpactPhase.APPROACH || phase == ImpactPhase.ENTRY) gatherTheAsteroid(doom, time, light)
        if (time >= doom.impactAt) {
            gatherTheFireball(doom, time, light)
            gatherTheDebrisGlow(doom, time, light)
            gatherTheStreaks(doom, time, glows)
            gatherTheFlash(doom, time, light)
            gatherTheDust(doom, time, shade)
        }
        Blaze3dSkyCanvas.drawShapes(light, SkyBlend.LIGHT)
        val horizon = horizonGlowAt(doom, time)
        if (horizon > NOTHING) Blaze3dSkyCanvas.drawHorizonGlow(doom.bearingDegrees, FIRE_ON_THE_HORIZON.copy(alpha = horizon))
        Blaze3dSkyCanvas.drawGlows(glows)
        Blaze3dSkyCanvas.drawShapes(shade, SkyBlend.SHADE)
    }

    // ---- The asteroid --------------------------------------------------------------------------------------

    /** Where the asteroid is seen from the eye: bearing and height in radians, and how far off in blocks. */
    private data class Sighting(val azimuth: Double, val elevation: Double, val distance: Double) {
        val isAboveTheHorizon: Boolean get() = elevation > LOST_BELOW_THE_HORIZON
        val direction: Vector3f get() = directionOf(azimuth, elevation)
    }

    /**
     * Where the asteroid is [ticksToImpact] before it strikes.
     *
     * Its path ends at the impact, [IMPACT_DISTANCE] out on the Age's bearing, and runs back up and away along
     * [incomingOf]. How far back along it the asteroid is shrinks steadily through the entry and geometrically
     * through the approach, so it grows from a speck rather than appearing half-grown. The horizon is dropped as a
     * planet's would be, so the impact itself is out of sight.
     */
    private fun sightingOf(doom: Doom, ticksToImpact: Double): Sighting {
        val bearing = Math.toRadians(doom.bearingDegrees.toDouble())
        val alongX = sin(bearing)
        val alongZ = -cos(bearing)
        val acrossX = cos(bearing)
        val acrossZ = sin(bearing)
        val climb = Math.toRadians(ENTRY_CLIMB_DEGREES)
        val slant = Math.toRadians(ENTRY_SLANT_DEGREES)
        val back = remainingPathAt(doom, ticksToImpact)
        val outward = cos(climb) * cos(slant) * back
        val sideways = cos(climb) * sin(slant) * back
        val x = alongX * (IMPACT_DISTANCE + outward) + acrossX * sideways
        val z = alongZ * (IMPACT_DISTANCE + outward) + acrossZ * sideways
        val y = sin(climb) * back
        val horizontal = hypot(x, z)
        val dropped = minOf(horizontal / (2.0 * PLANET_RADIUS), MOST_DROPPED)
        return Sighting(atan2(x, -z), atan2(y, horizontal) - dropped, sqrt(horizontal * horizontal + y * y))
    }

    private fun remainingPathAt(doom: Doom, ticksToImpact: Double): Double {
        val entry = doom.timeline.entry.toDouble()
        val approach = doom.timeline.approach.toDouble()
        val left = ticksToImpact.coerceAtLeast(0.0)
        if (left <= entry) return PATH_AT_ENTRY * left / entry
        val throughTheApproach = ((left - entry) / approach).coerceAtMost(1.0)
        return PATH_AT_ENTRY * (PATH_AT_FIRST_SIGHT / PATH_AT_ENTRY).pow(throughTheApproach)
    }

    private fun gatherTheAsteroid(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val ticksToImpact = doom.impactAt - time
        val sighting = sightingOf(doom, ticksToImpact)
        if (!sighting.isAboveTheHorizon) return
        val burning = doom.progressThrough(ImpactPhase.ENTRY, time).toFloat()
        val size = sizeAt(sighting.distance)
        // The trail first, so the body is laid over its own wake.
        if (burning > NOTHING) {
            for (behind in TRAIL_LENGTH downTo 1) {
                val earlier = sightingOf(doom, ticksToImpact + behind * TRAIL_SPACING_TICKS)
                if (!earlier.isAboveTheHorizon) continue
                val fading = TRAIL_FADES.pow(behind) * burning
                into += discAt(earlier.direction, size * TRAIL_THINS.pow(behind), BURNING.dimmed(fading), haloStrength = NOTHING)
            }
        }
        val body = PALE_ROCK.lerp(BURNING, sqrt(burning))
        into += discAt(sighting.direction, size, body, haloStrength = BODY_HALO + BURNING_HALO * burning)
    }

    /** How big a body of [ASTEROID_RADIUS] looks [distance] blocks off, in the units `drawGlow` takes. */
    private fun sizeAt(distance: Double): Float =
        maxOf(SPECK, (GLOW_DISTANCE * ASTEROID_RADIUS / distance).toFloat())

    // ---- After the impact ----------------------------------------------------------------------------------

    /** The fireball rising beyond the horizon where it struck, white-hot and then dull red. */
    private fun gatherTheFireball(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val strength = horizonGlowAt(doom, time)
        if (strength <= NOTHING) return
        val rising = ((time - doom.impactAt) / FIREBALL_RISES_TICKS).coerceIn(0.0, 1.0)
        val elevation = Math.toRadians(FIREBALL_LOWEST + (FIREBALL_HIGHEST - FIREBALL_LOWEST) * rising.pow(FIREBALL_EASING))
        val direction = directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), elevation)
        val size = (FIREBALL_SMALLEST + (FIREBALL_LARGEST - FIREBALL_SMALLEST) * rising).toFloat()
        val cooling = rising.toFloat()
        val colour = WHITE_HOT.lerp(FIREBALL_ORANGE, (cooling * FIREBALL_COOLS_FASTER).coerceAtMost(1.0f)).lerp(DULL_RED, cooling)
        into += discAt(direction, size, colour.dimmed(strength), haloStrength = FIREBALL_HALO_STRENGTH)
    }

    /** How strongly the horizon burns where it struck: from the impact, through the heat, fading into the dust. */
    private fun horizonGlowAt(doom: Doom, time: Double): Float = when (doom.phaseAt(time.toLong())) {
        ImpactPhase.TRAVEL, ImpactPhase.HEAT -> FULL
        ImpactPhase.DUST -> Impacts.heatAt(doom, time)
        else -> NOTHING
    }

    /** How far, in degrees from the horizon where it struck, the debris coming back down has reached over the sky. */
    private fun frontAt(doom: Doom, time: Double): Double =
        OVERHEAD * (time - doom.impactAt) / doom.timeline.travel

    /** How brightly the sky glows where the debris has reached. */
    private fun skyGlowAt(doom: Doom, time: Double): Float = when (doom.phaseAt(time.toLong())) {
        ImpactPhase.TRAVEL -> (GLOW_AT_IMPACT + (FULL - GLOW_AT_IMPACT) * doom.progressThrough(ImpactPhase.TRAVEL, time)).toFloat()
        ImpactPhase.HEAT -> FULL - FLICKER * (FULL + sin(time * FLICKER_PACE).toFloat()) / 2.0f
        ImpactPhase.DUST -> Impacts.heatAt(doom, time)
        else -> NOTHING
    }

    /** How much of the debris' glow lies over [corner]: all of it behind the front, none ahead, a soft edge between. */
    private fun litAt(corner: GridCorner, impactDirection: Vector3f, front: Double): Float {
        val fromTheImpact = Math.toDegrees(acos(corner.direction.dot(impactDirection).toDouble().coerceIn(-1.0, 1.0)))
        return smoothStep(front + FRONT_SOFTNESS, front - FRONT_SOFTNESS, fromTheImpact)
    }

    private fun gatherTheDebrisGlow(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val strength = skyGlowAt(doom, time)
        if (strength <= NOTHING) return
        val impactDirection = directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), 0.0)
        val front = frontAt(doom, time)
        into += quadsOverTheSky { corner ->
            val lit = litAt(corner, impactDirection, front) * strength
            val height = (corner.elevationDegrees / OVERHEAD).toFloat().coerceIn(0.0f, 1.0f)
            LOW_GLOW.lerp(HIGH_GLOW, height).dimmed(lit * GLOW_BRIGHTNESS).copy(alpha = FULL)
        }
    }

    /**
     * The debris itself, streaking down through the glowing sky: each streak a few lights in a short falling line,
     * drawn afresh on a cycle of its own at a place drawn for that cycle, and only where the glow has reached.
     */
    private fun gatherTheStreaks(doom: Doom, time: Double, into: MutableList<Glow>) {
        val strength = skyGlowAt(doom, time)
        if (strength <= NOTHING) return
        val impactDirection = directionOf(Math.toRadians(doom.bearingDegrees.toDouble()), 0.0)
        val front = frontAt(doom, time)
        for (number in 0..<STREAKS) {
            val offset = unitAt(number.toLong(), OFFSET_SALT) * STREAK_LIFE_TICKS
            val cycle = ((time + offset) / STREAK_LIFE_TICKS).toLong()
            val life = (((time + offset) % STREAK_LIFE_TICKS) / STREAK_LIFE_TICKS).toFloat()
            val azimuth = unitAt(number.toLong(), cycle) * 2.0 * PI
            val elevation = STREAK_LOWEST + unitAt(number.toLong(), cycle xor HEIGHT_SALT) * (STREAK_HIGHEST - STREAK_LOWEST)
            val corner = GridCorner(directionOf(azimuth, Math.toRadians(elevation)), elevation)
            if (litAt(corner, impactDirection, front) < HALF) continue
            val brightness = (FULL - life) * strength
            for (link in 0..<STREAK_LINKS) {
                val falling = elevation - STREAK_FALLS_DEGREES * life + link * STREAK_LINK_DEGREES
                val direction = directionOf(azimuth, Math.toRadians(falling))
                into += glowAt(direction, STREAK_SIZE * STREAK_THINS.pow(link), STREAK_COLOUR.dimmed(brightness * STREAK_FADES.pow(link)))
            }
        }
    }

    private fun gatherTheFlash(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val flash = Impacts.flashAt(doom, time)
        if (flash <= NOTHING) return
        val white = Rgba.WHITE.dimmed(flash * FLASH_BRIGHTNESS).copy(alpha = FULL)
        into += quadsOverTheSky { white }
    }

    private fun gatherTheDust(doom: Doom, time: Double, into: MutableList<SkyQuad>) {
        val dust = Impacts.dustAt(doom, time)
        if (dust <= NOTHING) return
        val pall = DUST_PALL.copy(alpha = dust * DUST_COVERS)
        into += quadsOverTheSky { pall }
    }

    // ---- The sky as a grid ---------------------------------------------------------------------------------

    /** One corner of the grid laid over the sky: which way it lies, and how high, in degrees. */
    private class GridCorner(val direction: Vector3f, val elevationDegrees: Double)

    /** The sky from a little below the horizon to overhead, as rows of corners — the same every frame. */
    private val GRID: List<List<GridCorner>> by lazy {
        (0..ROWS).map { row ->
            val elevation = LOWEST_ROW + (OVERHEAD - LOWEST_ROW) * row / ROWS
            (0..COLUMNS).map { column ->
                val azimuth = 2.0 * PI * column / COLUMNS
                GridCorner(directionOf(azimuth, Math.toRadians(elevation)), elevation)
            }
        }
    }

    private fun quadsOverTheSky(colourAt: (GridCorner) -> Rgba): List<SkyQuad> {
        val colours = GRID.map { row -> row.map(colourAt) }
        return buildList {
            for (row in 0..<ROWS) {
                for (column in 0..<COLUMNS) {
                    fun corner(atRow: Int, atColumn: Int) = SkyCorner(GRID[atRow][atColumn].direction, colours[atRow][atColumn])
                    val quad = SkyQuad(
                        corner(row, column),
                        corner(row, column + 1),
                        corner(row + 1, column + 1),
                        corner(row + 1, column),
                    )
                    if (quad.corners.any { it.colour.isVisible() }) add(quad)
                }
            }
        }
    }

    private fun Rgba.isVisible() = alpha > NOTHING && (red > NOTHING || green > NOTHING || blue > NOTHING || alpha < FULL)

    // ---- Helpers -------------------------------------------------------------------------------------------

    private fun directionOf(azimuth: Double, elevation: Double): Vector3f = Vector3f(
        (sin(azimuth) * cos(elevation)).toFloat(),
        sin(elevation).toFloat(),
        (-cos(azimuth) * cos(elevation)).toFloat(),
    )

    /**
     * A round light of [size] (in the units `drawGlow` takes) about [direction]: a solid core with a soft rim, and a
     * faint halo out to [HALO_SPREAD] times as far at [haloStrength] of its colour. A glow is a square quad, which a
     * point of light hides and a body the size of the moon does not.
     */
    private fun discAt(direction: Vector3f, size: Float, colour: Rgba, haloStrength: Float): List<SkyQuad> {
        val radius = atan(size / GLOW_DISTANCE.toFloat())
        val across = Vector3f(direction).cross(if (abs(direction.y) > NEARLY_UPRIGHT) EAST else UP).normalize()
        val along = Vector3f(direction).cross(across).normalize()
        fun rimAt(segment: Int, angularRadius: Float): Vector3f {
            val turn = 2.0 * PI * segment / DISC_SEGMENTS
            val reach = tan(angularRadius)
            return Vector3f(across).mul((cos(turn) * reach).toFloat())
                .add(Vector3f(along).mul((sin(turn) * reach).toFloat()))
                .add(direction)
                .normalize()
        }
        val solid = colour.copy(alpha = FULL)
        val none = Rgba(0.0f, 0.0f, 0.0f, FULL)
        val halo = colour.dimmed(haloStrength).copy(alpha = FULL)
        val core = radius * DISC_CORE
        val rim = radius * DISC_RIM
        val outer = radius * HALO_SPREAD
        return buildList {
            for (segment in 0..<DISC_SEGMENTS) {
                val next = segment + 1
                add(SkyQuad(SkyCorner(direction, solid), SkyCorner(direction, solid), SkyCorner(rimAt(segment, core), solid), SkyCorner(rimAt(next, core), solid)))
                add(SkyQuad(SkyCorner(rimAt(segment, core), solid), SkyCorner(rimAt(segment, rim), halo), SkyCorner(rimAt(next, rim), halo), SkyCorner(rimAt(next, core), solid)))
                if (haloStrength > NOTHING) {
                    add(SkyQuad(SkyCorner(rimAt(segment, rim), halo), SkyCorner(rimAt(segment, outer), none), SkyCorner(rimAt(next, outer), none), SkyCorner(rimAt(next, rim), halo)))
                }
            }
        }
    }

    private fun glowAt(direction: Vector3f, size: Float, tint: Rgba): Glow = Glow(
        orientation = Quaternionf().rotateTo(0.0f, 1.0f, 0.0f, direction.x, direction.y, direction.z),
        distance = GLOW_DISTANCE.toFloat(),
        angularSize = size,
        tint = tint,
    )

    /** Rises from nought at [from] to one at [to]; either way round, so it can fall as well. */
    private fun smoothStep(from: Double, to: Double, at: Double): Float {
        val along = ((at - from) / (to - from)).coerceIn(0.0, 1.0)
        return (along * along * (3.0 - 2.0 * along)).toFloat()
    }

    /** A number in `0..1` fixed by [number] and [salt]. */
    private fun unitAt(number: Long, salt: Long): Double {
        var mixed = number * GOLDEN_GAMMA + salt
        mixed = (mixed xor (mixed ushr 30)) * MIX_ONE
        mixed = (mixed xor (mixed ushr 27)) * MIX_TWO
        mixed = mixed xor (mixed ushr 31)
        return (mixed ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private const val NOTHING = 0.0f
    private const val FULL = 1.0f
    private const val HALF = 0.5f
    private const val OVERHEAD = 90.0

    // The path. A world of these proportions is not ours to see whole, so they are chosen for how it looks.
    private const val IMPACT_DISTANCE = 30_000.0
    private const val PLANET_RADIUS = 200_000.0
    private const val MOST_DROPPED = 0.1
    private const val ENTRY_CLIMB_DEGREES = 40.0
    private const val ENTRY_SLANT_DEGREES = 25.0
    private const val PATH_AT_ENTRY = 120_000.0
    private const val PATH_AT_FIRST_SIGHT = 8_000_000.0
    private const val ASTEROID_RADIUS = 4_500.0
    private const val LOST_BELOW_THE_HORIZON = -0.005

    // The body.
    private const val GLOW_DISTANCE = 100.0
    private const val SPECK = 0.35f
    private val PALE_ROCK = Rgba(0.95f, 0.93f, 0.88f, 0.95f)
    private val BURNING = Rgba(1.0f, 0.62f, 0.28f, 0.95f)
    private const val BODY_HALO = 0.15f
    private const val BURNING_HALO = 0.45f
    private const val TRAIL_LENGTH = 14
    private const val TRAIL_SPACING_TICKS = 2.0
    private const val TRAIL_FADES = 0.8f
    private const val TRAIL_THINS = 0.85f

    // The fireball and the horizon.
    private val FIRE_ON_THE_HORIZON = Rgba(1.0f, 0.45f, 0.12f)
    private val WHITE_HOT = Rgba(1.0f, 0.95f, 0.80f)
    private val FIREBALL_ORANGE = Rgba(1.0f, 0.50f, 0.15f)
    private val DULL_RED = Rgba(0.60f, 0.12f, 0.04f)
    private const val FIREBALL_RISES_TICKS = 800.0
    private const val FIREBALL_COOLS_FASTER = 6.0f
    private const val FIREBALL_LOWEST = 1.0
    private const val FIREBALL_HIGHEST = 10.0
    private const val FIREBALL_EASING = 0.6
    private const val FIREBALL_SMALLEST = 6.0
    private const val FIREBALL_LARGEST = 12.0
    private const val FIREBALL_HALO_STRENGTH = 0.35f

    // The debris coming back down.
    private val LOW_GLOW = Rgba(1.0f, 0.45f, 0.12f)
    private val HIGH_GLOW = Rgba(0.70f, 0.12f, 0.04f)
    private const val GLOW_BRIGHTNESS = 0.8f
    private const val GLOW_AT_IMPACT = 0.55
    private const val FRONT_SOFTNESS = 15.0
    private const val FLICKER = 0.15f
    private const val FLICKER_PACE = 0.7
    private const val STREAKS = 160
    private const val STREAK_LIFE_TICKS = 30.0
    private const val STREAK_LOWEST = 8.0
    private const val STREAK_HIGHEST = 75.0
    private const val STREAK_FALLS_DEGREES = 6.0
    private const val STREAK_LINKS = 4
    private const val STREAK_LINK_DEGREES = 1.5
    private const val STREAK_SIZE = 0.5f
    private const val STREAK_THINS = 0.75f
    private const val STREAK_FADES = 0.7f
    private val STREAK_COLOUR = Rgba(1.0f, 0.85f, 0.55f, 0.95f)
    private const val OFFSET_SALT = 0x51A7_E3C0_9D21_44B7L
    private const val HEIGHT_SALT = 0x2C6B_8F10_E5A3_7D49L

    // The flash and the dust.
    private const val FLASH_BRIGHTNESS = 0.9f
    private val DUST_PALL = Rgba(0.17f, 0.15f, 0.14f)
    private const val DUST_COVERS = 0.9f

    // A disc.
    private const val DISC_SEGMENTS = 24
    private const val DISC_CORE = 0.8f
    private const val DISC_RIM = 1.0f
    private const val HALO_SPREAD = 2.5f
    private const val NEARLY_UPRIGHT = 0.95f
    private val UP = Vector3f(0.0f, 1.0f, 0.0f)
    private val EAST = Vector3f(1.0f, 0.0f, 0.0f)

    // The grid.
    private const val ROWS = 12
    private const val COLUMNS = 36
    private const val LOWEST_ROW = -12.0

    private const val GOLDEN_GAMMA = -0x61c8_8646_80b5_83ebL
    private const val MIX_ONE = -0x40a7_b892_e31b_1a47L
    private const val MIX_TWO = -0x6b2f_b644_ecce_ee15L
}
