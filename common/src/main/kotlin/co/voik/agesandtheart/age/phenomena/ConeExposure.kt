package co.voik.agesandtheart.age.phenomena

import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * How much of what comes from one direction can reach a place, from none of it to all of it: rays cast
 * from the eye in a cone around where it comes from, weighted towards the cone's centre so one gap cannot
 * flip the answer. [upwindAt] is a wind's, [snowFrom] a blizzard's snow, [OVERHEAD] an inferno's.
 *
 * A ray gets out only if it runs its whole length without meeting a collision shape **and** ends under open
 * sky. Clear alone would call a long tunnel exposed; open sky alone would call the lee of a wall exposed.
 */
object ConeExposure {

    /** How long a reading is kept before it is taken again, in ticks. */
    const val BETWEEN_READINGS = 20L

    class Ray(val direction: Vec3, val weight: Double)

    class Cone(val rays: List<Ray>) {
        val wholeWeight = rays.sumOf { it.weight }
    }

    fun openness(level: Level, eye: Vec3, cone: Cone): Float {
        var escaped = 0.0
        for (ray in cone.rays) {
            if (getsOut(level, eye, eye.add(ray.direction.scale(RAY_LENGTH)))) escaped += ray.weight
        }
        return (escaped / cone.wholeWeight).toFloat()
    }

    private fun getsOut(level: Level, from: Vec3, to: Vec3): Boolean {
        val looking = ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())
        val runsClear = level.clip(looking).type == HitResult.Type.MISS
        return runsClear && Sampling.openToTheSky(level, BlockPos.containing(to))
    }

    /**
     * One entity's reading, kept for [BETWEEN_READINGS] ticks. Taken again at once if the cone changes (the
     * wind turned) or the clock runs backwards, which is a client that has changed Age.
     */
    class Remembered {
        private var reading = ALL_OF_IT
        private var takenAt = Long.MIN_VALUE
        private var takenWith: Cone? = null

        fun read(level: Level, eye: Vec3, cone: Cone): Float {
            val now = level.gameTime
            val isStale = cone !== takenWith || now < takenAt || now - takenAt >= BETWEEN_READINGS
            if (isStale) {
                reading = openness(level, eye, cone)
                takenAt = now
                takenWith = cone
            }
            return reading
        }
    }

    /**
     * Where snow driven towards [drivingDegrees] comes from: back up into the wind, at [Blizzard.SNOW_FALLS_PER_BLOCK],
     * to the nearest [BEARING_STEP] degrees.
     */
    fun snowFrom(drivingDegrees: Double): Cone = SNOW_SLANTED[nearestStep(drivingDegrees)]

    /**
     * Where a wind driving towards [drivingDegrees] comes from — round from +x towards +z, as Ephemeris' `Wind` has it —
     * back up into it, low over the ground, to the nearest [BEARING_STEP] degrees.
     */
    fun upwindAt(drivingDegrees: Double): Cone = ALONG_THE_GROUND[nearestStep(drivingDegrees)]

    private fun nearestStep(drivingDegrees: Double): Int =
        Math.floorMod(Math.round(drivingDegrees / BEARING_STEP).toInt(), BEARINGS)

    // Lazy because the ray tables they are built from are declared below them.
    private val ALONG_THE_GROUND: List<Cone> by lazy { conesAt(LOW_OVER_THE_GROUND) }
    private val SNOW_SLANTED: List<Cone> by lazy { conesAt(Math.toDegrees(atan(Blizzard.SNOW_FALLS_PER_BLOCK))) }

    /** One cone back up into the wind for each of the [BEARINGS], [slant] degrees above the horizontal. */
    private fun conesAt(slant: Double): List<Cone> = List(BEARINGS) { step -> coneFrom(step * BEARING_STEP + HALF_TURN, slant) }

    /** A cone of rays around [bearing], [slant] degrees above the horizontal. */
    private fun coneFrom(bearing: Double, slant: Double): Cone {
        val rays = WIND_TURNS.flatMap { turn ->
            WIND_LIFTS.map { lift ->
                val weight = exp(-squared(turn / WIND_TURN_SPREAD) - squared(lift / WIND_LIFT_SPREAD))
                Ray(towards(bearing + turn, slant + lift), weight)
            }
        }
        return Cone(rays)
    }

    /** Straight up and round it — the sun, wherever it stands. */
    val OVERHEAD: Cone by lazy {
        val straightUp = Ray(UP, ALL_OF_IT.toDouble())
        val rings = OVERHEAD_TILTS.flatMap { tilt ->
            val weight = exp(-squared(tilt / OVERHEAD_TILT_SPREAD))
            (0..<OVERHEAD_SPOKES).map { spoke ->
                Ray(towards(spoke * FULL_TURN / OVERHEAD_SPOKES, RIGHT_ANGLE - tilt), weight)
            }
        }
        Cone(listOf(straightUp) + rings)
    }

    /** A unit vector [bearingDegrees] round from +x towards +z, lifted [elevationDegrees] above the horizontal. */
    private fun towards(bearingDegrees: Double, elevationDegrees: Double): Vec3 {
        val bearing = Math.toRadians(bearingDegrees)
        val elevation = Math.toRadians(elevationDegrees)
        return Vec3(cos(bearing) * cos(elevation), sin(elevation), sin(bearing) * cos(elevation))
    }

    private fun squared(value: Double) = value * value

    /** How far a ray runs before it counts as having got out, in blocks. */
    private const val RAY_LENGTH = 16.0

    /** How finely a wind's bearing is told apart, and how many cones that makes. */
    private const val BEARING_STEP = 15.0
    private const val BEARINGS = 24
    private const val HALF_TURN = 180.0

    /** How steeply a wind with nothing falling in it comes down: barely at all. */
    private const val LOW_OVER_THE_GROUND = 10.0

    /** Either side of the wind, and below and above the snow's slant, in degrees. */
    private val WIND_TURNS = listOf(-30.0, -15.0, 0.0, 15.0, 30.0)
    private val WIND_LIFTS = listOf(-10.0, 0.0, 15.0, 30.0)
    private const val WIND_TURN_SPREAD = 25.0
    private const val WIND_LIFT_SPREAD = 20.0

    /** The rings round straight up, in degrees from it, and how many rays each. */
    private val OVERHEAD_TILTS = listOf(20.0, 40.0)
    private const val OVERHEAD_SPOKES = 8
    private const val OVERHEAD_TILT_SPREAD = 30.0

    private val UP = Vec3(0.0, 1.0, 0.0)
    private const val FULL_TURN = 360.0
    private const val RIGHT_ANGLE = 90.0
    private const val ALL_OF_IT = 1.0f
}
