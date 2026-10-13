package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.TearStyle
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.client.SkyBlend
import co.voik.ephemeris.client.SkyCorner
import co.voik.ephemeris.client.SkyQuad
import co.voik.ephemeris.client.SkyShapes
import org.joml.Quaternionfc
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** One tear in an Age's sky: where it hangs, its shape, and the clocks it shimmers and seals on. */
data class Tear(
    val centre: Vector3f,
    /** Which way it runs across the sky at its centre, as a unit vector at right angles to [centre]. */
    val along: Vector3f,
    val lengthDegrees: Double,
    /** How far each bend stands off the straight line, in degrees, from one end to the other. */
    val bends: List<Double>,
    val width: Double,
    val shimmerPhase: Double,
    val shimmerRate: Double,
    val cycleTicks: Long,
    val shutTicks: Long,
    val cycleOffset: Long,
)

/**
 * The tears an Age's sky is torn with, as shapes for Ephemeris to draw: laid out once from the Age's seed, and
 * shaped each frame from the time — each shimmers a little all the while, and now and then seals from both ends,
 * stays shut a minute or two, and opens again.
 */
object TearShapes {

    /** [count] tears, the same for the same [seed]. */
    fun layout(seed: Long, count: Int): List<Tear> {
        val random = Random(seed xor LAYOUT_SALT)
        return List(count) { oneTear(random) }
    }

    /**
     * One tear, centred anywhere on the whole sphere with every part of it as likely as any other — the sine of
     * its height drawn evenly rather than the height itself, which crowded them overhead. Half are below the
     * horizon at any moment, rising and setting as the stars turn them round.
     */
    private fun oneTear(random: Random): Tear {
        val length = SHORTEST + (LONGEST - SHORTEST) * random.nextDouble().pow(LENGTH_SKEW)
        val height = random.nextDouble(-1.0, 1.0)
        val level = sqrt(1 - height * height)
        val bearing = random.nextDouble(0.0, 2 * PI)
        val centre = Vector3f((level * sin(bearing)).toFloat(), height.toFloat(), (-level * cos(bearing)).toFloat())
        val notAlongCentre = if (abs(height) > NEARLY_A_POLE) Vector3f(1f, 0f, 0f) else Vector3f(0f, 1f, 0f)
        val east = notAlongCentre.cross(centre, Vector3f()).normalize()
        val north = Vector3f(centre).cross(east).normalize()
        val heading = random.nextDouble(0.0, 2 * PI)
        val along = Vector3f(east).mul(cos(heading).toFloat()).add(Vector3f(north).mul(sin(heading).toFloat())).normalize()
        val windiness = random.nextDouble(LEAST_WINDING, MOST_WINDING)
        val bends = List(random.nextInt(FEWEST_BENDS, MOST_BENDS + 1) + 1) { random.nextDouble(-1.0, 1.0) * windiness * length }
        val cycle = random.nextLong(SHORTEST_CYCLE, LONGEST_CYCLE)
        return Tear(
            centre = centre,
            along = along,
            lengthDegrees = length,
            bends = bends,
            width = random.nextDouble(NARROWEST, WIDEST),
            shimmerPhase = random.nextDouble(0.0, 2 * PI),
            shimmerRate = random.nextDouble(SLOWEST_SHIMMER, FASTEST_SHIMMER),
            cycleTicks = cycle,
            shutTicks = random.nextLong(SHORTEST_SHUT, LONGEST_SHUT),
            cycleOffset = random.nextLong(0, cycle),
        )
    }

    /** How open [tear] stands at [ticks]: nought shut, one wide open, easing between over a few seconds. */
    fun openness(tear: Tear, ticks: Double): Double {
        val into = Math.floorMod((ticks + tear.cycleOffset).toLong(), tear.cycleTicks).toDouble()
        val opening = ((into - tear.shutTicks) / EASING_TICKS).coerceIn(0.0, 1.0)
        val closing = ((tear.cycleTicks - into) / EASING_TICKS).coerceIn(0.0, 1.0)
        val eased = minOf(opening, closing)
        return eased * eased * (3 - 2 * eased)
    }

    /**
     * Every tear as shapes at [ticks], turned by [turn] with the stars: **every halo before any core**, so where
     * two tears cross their cores run together and neither's fringe is laid across the other's.
     */
    fun shapesAt(tears: List<Tear>, ticks: Double, style: TearStyle, brightness: Float, turn: Quaternionfc): List<SkyShapes> {
        val halos = mutableListOf<SkyQuad>()
        val cores = mutableListOf<SkyQuad>()
        for (tear in tears) {
            val open = openness(tear, ticks)
            if (open <= SHUT) continue
            val seconds = ticks / TICKS_PER_SECOND
            val glimmer = (1 - SHIMMER + SHIMMER * sin(seconds * tear.shimmerRate + tear.shimmerPhase)).toFloat() * brightness
            val swell = 1 - SWELL + SWELL * sin(seconds * tear.shimmerRate * SWELL_RATE + tear.shimmerPhase * 2)
            val path = pathOf(tear, open).map { Point(turn.transform(it.at, Vector3f()), it.taper) }
            val width = tear.width * swell * open.pow(WIDTH_WITH_OPENNESS)
            when (style) {
                TearStyle.GLOWING -> {
                    cores += ribbon(path, CORE_WIDTH * width, GLOW_CORE.dimmed(glimmer))
                    halos += halo(path, CORE_WIDTH * width, HALO_WIDTH * width, GLOW_HALO.dimmed(glimmer), NOTHING)
                }
                TearStyle.DARK -> {
                    cores += ribbon(path, CORE_WIDTH * width, DARK_CORE.withAlpha(DARK_CORE.alpha * glimmer))
                    halos += halo(path, CORE_WIDTH * width, HALO_WIDTH * width, DARK_HALO.withAlpha(DARK_HALO.alpha * glimmer), CLEAR)
                }
                TearStyle.STARFIELD -> {
                    cores += ribbon(path, PORTAL_WIDTH * width, NOTHING)
                    halos += halo(path, PORTAL_WIDTH * width, HALO_WIDTH * width, PORTAL_RIM.dimmed(glimmer), NOTHING)
                }
            }
        }
        val (haloBlend, coreBlend) = when (style) {
            TearStyle.GLOWING -> SkyBlend.LIGHT to SkyBlend.LIGHT
            TearStyle.DARK -> SkyBlend.SHADE to SkyBlend.SHADE
            TearStyle.STARFIELD -> SkyBlend.LIGHT to SkyBlend.STARFIELD
        }
        return listOf(SkyShapes(halos, haloBlend), SkyShapes(cores, coreBlend))
    }

    /** A point of the tear's centre line, and how wide it is there as a share of its widest, from end to end. */
    private data class Point(val at: Vector3f, val taper: Double)

    /** The centre line, only as much of it as is [open] — a sealing tear shrinks towards its middle. */
    private fun pathOf(tear: Tear, open: Double): List<Point> {
        val across = Vector3f(tear.centre).cross(tear.along).normalize()
        val segments = (tear.lengthDegrees / DEGREES_PER_SEGMENT).toInt().coerceIn(FEWEST_SEGMENTS, MOST_SEGMENTS)
        val from = HALF - open * HALF
        return (0..segments).map { step ->
            val share = from + open * step / segments
            val forward = Math.toRadians((share - HALF) * tear.lengthDegrees)
            val aside = Math.toRadians(bendAt(tear.bends, share))
            val at = Vector3f(tear.centre)
                .add(Vector3f(tear.along).mul(tan(forward).toFloat()))
                .add(Vector3f(across).mul(tan(aside).toFloat()))
                .normalize()
            Point(at, sin(share * PI).pow(TAPER_SHARPNESS))
        }
    }

    private fun bendAt(bends: List<Double>, share: Double): Double {
        val position = share * (bends.size - 1)
        val before = position.toInt().coerceAtMost(bends.size - 2)
        val between = position - before
        return bends[before] + (bends[before + 1] - bends[before]) * between
    }

    /** Which way is across the line at each point, on the sky. */
    private fun acrossAt(path: List<Point>): List<Vector3f> = path.indices.map { index ->
        val behind = path[(index - 1).coerceAtLeast(0)].at
        val ahead = path[(index + 1).coerceAtMost(path.size - 1)].at
        val running = Vector3f(ahead).sub(behind)
        Vector3f(path[index].at).cross(running).normalize()
    }

    /** Where the line's point [index] stands pushed [degrees] across it, scaled by its taper; [side] is ±1. */
    private fun offset(path: List<Point>, across: List<Vector3f>, index: Int, degrees: Double, side: Float): Vector3f {
        val by = tan(Math.toRadians(degrees * path[index].taper)).toFloat() * side
        return Vector3f(path[index].at).add(Vector3f(across[index]).mul(by)).normalize()
    }

    /** A strip [halfWidth] degrees each side of the line, all [colour]. */
    private fun ribbon(path: List<Point>, halfWidth: Double, colour: Rgba): List<SkyQuad> {
        val across = acrossAt(path)
        return (0..<path.size - 1).map { index ->
            SkyQuad(
                SkyCorner(offset(path, across, index, halfWidth, -1f), colour),
                SkyCorner(offset(path, across, index, halfWidth, 1f), colour),
                SkyCorner(offset(path, across, index + 1, halfWidth, 1f), colour),
                SkyCorner(offset(path, across, index + 1, halfWidth, -1f), colour),
            )
        }
    }

    /** A fringe on both sides from [innerWidth] out to [outerWidth] degrees, fading from [inner] to [outer]. */
    private fun halo(path: List<Point>, innerWidth: Double, outerWidth: Double, inner: Rgba, outer: Rgba): List<SkyQuad> {
        val across = acrossAt(path)
        return listOf(1f, -1f).flatMap { side ->
            (0..<path.size - 1).map { index ->
                SkyQuad(
                    SkyCorner(offset(path, across, index, innerWidth, side), inner),
                    SkyCorner(offset(path, across, index, outerWidth, side), outer),
                    SkyCorner(offset(path, across, index + 1, outerWidth, side), outer),
                    SkyCorner(offset(path, across, index + 1, innerWidth, side), inner),
                )
            }
        }
    }

    private fun Rgba.withAlpha(alpha: Float) = Rgba(red, green, blue, alpha)

    private const val LAYOUT_SALT = 0x3C71_9A0E_55D2_B86FL

    private const val SHORTEST = 6.0
    private const val LONGEST = 45.0
    /** Above one, so most tears are short and a few run long. */
    private const val LENGTH_SKEW = 1.6
    /** Past this the vertical is too close to a tear's centre to reckon its east from. */
    private const val NEARLY_A_POLE = 0.99
    private const val LEAST_WINDING = 0.01
    private const val MOST_WINDING = 0.16
    private const val FEWEST_BENDS = 3
    private const val MOST_BENDS = 12
    private const val NARROWEST = 0.6
    private const val WIDEST = 1.6

    private const val SLOWEST_SHIMMER = 0.3
    private const val FASTEST_SHIMMER = 0.9
    private const val SHIMMER = 0.15
    private const val SWELL = 0.08
    private const val SWELL_RATE = 1.7

    /** Each tear seals once every twenty minutes to an hour, stays shut one to two, and eases over six seconds. */
    private const val SHORTEST_CYCLE = 24_000L
    private const val LONGEST_CYCLE = 72_000L
    private const val SHORTEST_SHUT = 1_200L
    private const val LONGEST_SHUT = 2_400L
    private const val EASING_TICKS = 120.0
    private const val TICKS_PER_SECOND = 20.0
    private const val SHUT = 0.001
    private const val WIDTH_WITH_OPENNESS = 0.5

    private const val DEGREES_PER_SEGMENT = 1.5
    private const val FEWEST_SEGMENTS = 8
    private const val MOST_SEGMENTS = 30
    private const val TAPER_SHARPNESS = 0.6
    private const val HALF = 0.5

    /** Half-widths in degrees: a hairline core, a window wide enough to see stars through, and the glow round both. */
    private const val CORE_WIDTH = 0.12
    private const val PORTAL_WIDTH = 0.35
    private const val HALO_WIDTH = 0.9

    private val GLOW_CORE = Rgba(0.95f, 0.92f, 1.0f)
    private val GLOW_HALO = Rgba(0.62f * 0.45f, 0.45f * 0.45f, 0.45f)
    private val DARK_CORE = Rgba(0.02f, 0.0f, 0.04f, 0.95f)
    private val DARK_HALO = Rgba(0.02f, 0.0f, 0.04f, 0.45f)
    private val PORTAL_RIM = Rgba(0.30f, 0.22f, 0.45f)
    private val NOTHING = Rgba(0f, 0f, 0f)
    private val CLEAR = Rgba(0f, 0f, 0f, 0f)
}
