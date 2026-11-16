package co.voik.agesandtheart.client

import co.voik.ephemeris.Rgba
import co.voik.ephemeris.client.SkyCorner
import co.voik.ephemeris.client.SkyQuad
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The asteroid near enough to have a shape (design §5.3 "The asteroid"): a tumbling cube of the moon's own pixels and,
 * through its entry, a sheath of flame wrapped round it, a paraboloid opening back along its path.
 *
 * Both are built in the sky's own space about [centre] (a point some way out along the asteroid's direction) with the
 * cube [halfEdge] across, so they turn and foreshorten as a body there would. [travel] is the way it moves.
 */
internal object AsteroidBody {

    /**
     * The cube, laid over the sky as far as it is [solid]: each face it shows a grid of pixels drawn from the moon's,
     * lit from above, smouldering as it [burning]s and white-hot on the faces it leads with as its [heat] rises.
     */
    fun cubeOf(centre: Vector3f, halfEdge: Float, travel: Vector3f, solid: Float, burning: Float, heat: Float, time: Double): List<SkyQuad> {
        val turned = turnedAt(time)
        val palette = moonPalette
        return FACES.withIndex().flatMap { (faceNumber, face) ->
            val normal = turned.transform(Vector3f(face.normal))
            val isTowardTheEye = -normal.dot(centre) > halfEdge
            if (!isTowardTheEye) return@flatMap emptyList()
            val across = turned.transform(Vector3f(face.across)).mul(halfEdge)
            val up = turned.transform(Vector3f(face.up)).mul(halfEdge)
            val faceCentre = Vector3f(normal).mul(halfEdge).add(centre)
            val lit = AMBIENT + (1.0f - AMBIENT) * maxOf(0.0f, normal.dot(SUNLIGHT))
            val leading = maxOf(0.0f, normal.dot(travel)).pow(LEADING_SHARPNESS)
            fun cornerAt(column: Int, row: Int): Vector3f = Vector3f(faceCentre)
                .add(Vector3f(across).mul(2.0f * column / PIXELS - 1.0f))
                .add(Vector3f(up).mul(2.0f * row / PIXELS - 1.0f))
                .normalize()
            (0..<PIXELS).flatMap { column ->
                (0..<PIXELS).map { row ->
                    val pixel = palette[pixelAt(faceNumber, column, row, palette.size)]
                    val colour = pixel.dimmed(lit * MOON_DIMMED)
                        .lerp(SMOULDERING, burning * SMOULDERS)
                        .lerp(WHITE_HOT, heat * leading)
                        .copy(alpha = solid)
                    fun corner(atColumn: Int, atRow: Int): SkyCorner {
                        val direction = cornerAt(atColumn, atRow)
                        return SkyCorner(direction, colour.copy(alpha = colour.alpha * aboveTheHorizon(direction)))
                    }
                    SkyQuad(corner(column, row), corner(column + 1, row), corner(column + 1, row + 1), corner(column, row + 1))
                }
            }
        }
    }

    /** Where the cube's eight corners are from its middle at [time], [halfEdge] out along each of its axes. */
    fun cornersOf(halfEdge: Float, time: Double): List<Vector3f> {
        val turned = turnedAt(time)
        return CORNERS.map { turned.transform(Vector3f(it)).mul(halfEdge) }
    }

    private fun turnedAt(time: Double): Quaternionf =
        Quaternionf(RESTING).premul(Quaternionf().rotationAxis((time * TUMBLE_PER_TICK).toFloat(), TUMBLE_AXIS))

    /** Whether the sheath is laid over the sky by its alpha or added to it as light. */
    enum class Look { COVERING, SHINING }

    /**
     * The sheath of flame, as strong as [strength]: a paraboloid about the way the cube travels, its nose just ahead
     * of it and opening back past it, white at the nose and orange behind, fading as it goes. Brightest where the eye
     * looks along its surface, as a glowing shell is, so it shows as a bright rim round the cube with the cube seen
     * through it. Its rim ripples, each ring on a pace of its own.
     */
    fun sheathOf(centre: Vector3f, halfEdge: Float, travel: Vector3f, strength: Float, time: Double, look: Look): List<SkyQuad> {
        val forward = Vector3f(travel).normalize()
        val aside = Vector3f(forward).cross(if (abs(forward.y) > NEARLY_UPRIGHT) EAST else UP).normalize()
        val other = Vector3f(forward).cross(aside).normalize()
        val nose = halfEdge * NOSE_AHEAD
        val focus = halfEdge * OPENING
        val back = halfEdge * REACHES_BACK
        val widest = sqrt(2.0f * focus * (nose + back))
        fun ringRadius(ring: Int): Float = widest * (ring.toFloat() / RINGS).pow(RINGS_GATHER_AT_THE_NOSE)
        fun corner(ring: Int, sector: Int): SkyCorner {
            val radius = ringRadius(ring)
            val alongTheAxis = nose - radius * radius / (2.0f * focus)
            val turn = 2.0 * Math.PI * sector / SECTORS
            val ripple = 1.0f + RIPPLE * sin(time * RIPPLE_PACE * (1.0 + ring * RIPPLE_PACE_SPREAD) + ring * RIPPLE_STEP + turn * RIPPLE_LOBES).toFloat()
            val outward = Vector3f(aside).mul(cos(turn).toFloat()).add(Vector3f(other).mul(sin(turn).toFloat()))
            val point = Vector3f(centre).add(Vector3f(forward).mul(alongTheAxis)).add(Vector3f(outward).mul(radius * ripple))
            val surfaceNormal = Vector3f(forward).add(Vector3f(outward).mul(radius / focus)).normalize()
            val towardTheEye = Vector3f(point).normalize()
            val behind = (nose - alongTheAxis) / (nose + back)
            // Bright wherever the eye looks along it, and over its nose whatever the angle, so the face it leads with
            // is wrapped in fire rather than seen bare through it.
            val alongIt = (GRAZING_LEAST / maxOf(abs(surfaceNormal.dot(towardTheEye)), GRAZING_LEAST)).pow(GRAZING_SHARPNESS)
            val overTheNose = (1.0f - behind / NOSE_BURNS_FOR).coerceIn(0.0f, 1.0f)
            val grazing = maxOf(alongIt, overTheNose)
            val fading = (1.0f - behind).pow(FADES_SHARPNESS)
            val colour = WHITE_HOT.lerp(FLAME, (behind * WHITE_FOR).coerceAtMost(1.0f))
            val direction = point.normalize()
            val share = strength * grazing * fading * aboveTheHorizon(direction)
            val shown = when (look) {
                Look.COVERING -> FLAME.copy(alpha = share * COVERS)
                Look.SHINING -> colour.dimmed(share * BRIGHTNESS).copy(alpha = 1.0f)
            }
            return SkyCorner(direction, shown)
        }
        return (0..<RINGS).flatMap { ring ->
            (0..<SECTORS).map { sector ->
                SkyQuad(corner(ring, sector), corner(ring + 1, sector), corner(ring + 1, sector + 1), corner(ring, sector + 1))
            }
        }
    }

    private val CORNERS = listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).flatMap { y -> listOf(-1f, 1f).map { z -> Vector3f(x, y, z) } } }

    /**
     * How much of what lies [direction] from the eye is seen over the horizon: all of it above, none below, and a
     * thin soft edge between, so a body going down behind the horizon is cut off by it rather than gone all at once.
     */
    fun aboveTheHorizon(direction: Vector3f): Float {
        val along = ((direction.y + HORIZON_EDGE) / (2.0f * HORIZON_EDGE)).coerceIn(0.0f, 1.0f)
        return along * along * (3.0f - 2.0f * along)
    }

    /** How soft the horizon's edge is, as a sine of the height either side of it. */
    private const val HORIZON_EDGE = 0.0015f

    /** One face of the cube: the way it faces, and the two ways across it, each one half-edge long. */
    private class Face(val normal: Vector3f, val across: Vector3f, val up: Vector3f)

    private val FACES = listOf(
        Face(Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, 1f), Vector3f(0f, 1f, 0f)),
        Face(Vector3f(-1f, 0f, 0f), Vector3f(0f, 0f, -1f), Vector3f(0f, 1f, 0f)),
        Face(Vector3f(0f, 1f, 0f), Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, 1f)),
        Face(Vector3f(0f, -1f, 0f), Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, -1f)),
        Face(Vector3f(0f, 0f, 1f), Vector3f(-1f, 0f, 0f), Vector3f(0f, 1f, 0f)),
        Face(Vector3f(0f, 0f, -1f), Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f)),
    )

    /** Which of the moon's pixels lies at [column], [row] on face [face]: drawn once and for all, so they hold still. */
    private fun pixelAt(face: Int, column: Int, row: Int, count: Int): Int {
        var mixed = (face * PIXELS * PIXELS + row * PIXELS + column).toLong() * GOLDEN_GAMMA + PIXEL_SALT
        mixed = (mixed xor (mixed ushr 30)) * MIX_ONE
        mixed = (mixed xor (mixed ushr 27)) * MIX_TWO
        mixed = mixed xor (mixed ushr 31)
        return Math.floorMod(mixed, count.toLong()).toInt()
    }

    /**
     * The colours of the moon as the game has it, a resource pack's included: the lit pixels of its face, and neither
     * the black of space round it nor the shading drawn along its own edge, which on a cube's face is a black speck.
     * Read once; a plain grey where it cannot be read.
     */
    private val moonPalette: List<Rgba> by lazy {
        val read = runCatching {
            val resource = Minecraft.getInstance().resourceManager.getResource(MOON).orElse(null) ?: return@runCatching emptyList()
            resource.open().use(NativeImage::read).use { image ->
                (0..<image.width).flatMap { x -> (0..<image.height).map { y -> image.getPixel(x, y) } }
                    .filter { ARGB.alpha(it) > 0 && ARGB.red(it) + ARGB.green(it) + ARGB.blue(it) > SHADING_IS_DARKER_THAN }
                    .map { Rgba(ARGB.red(it) / CHANNEL, ARGB.green(it) / CHANNEL, ARGB.blue(it) / CHANNEL) }
            }
        }.getOrDefault(emptyList())
        read.ifEmpty { listOf(PLAIN_GREY) }
    }

    private val MOON: Identifier = Identifier.withDefaultNamespace("textures/environment/celestial/moon/full_moon.png")
    private const val SHADING_IS_DARKER_THAN = 3 * 0x70
    private const val CHANNEL = 255.0f
    private val PLAIN_GREY = Rgba(0.6f, 0.6f, 0.6f)

    /** How many pixels across a face, and how dark the moon's pale colours are laid on rock. */
    private const val PIXELS = 8
    private const val MOON_DIMMED = 0.8f

    /** How it lies and tumbles: a slant it starts at, the axis it turns about, and how fast, in radians a tick. */
    private val RESTING = Quaternionf().rotationXYZ(0.6f, 0.4f, 0.2f)
    private val TUMBLE_AXIS = Vector3f(0.3f, 1.0f, 0.5f).normalize()
    private const val TUMBLE_PER_TICK = 0.004

    /** Sunlight from high up, and how much of a face turned from it still shows. */
    private val SUNLIGHT = Vector3f(0.3f, 1.0f, 0.2f).normalize()
    private const val AMBIENT = 0.55f

    private val SMOULDERING = Rgba(0.35f, 0.08f, 0.03f)
    private const val SMOULDERS = 0.35f
    private val WHITE_HOT = Rgba(1.0f, 0.93f, 0.75f)
    private const val LEADING_SHARPNESS = 1.5f
    private val FLAME = Rgba(1.0f, 0.45f, 0.12f)

    /**
     * The sheath's shape, in half-edges of the cube: its nose ahead of the cube's farthest corner, how sharply it
     * opens, and how far back past the middle it runs before it has faded.
     */
    private const val NOSE_AHEAD = 2.0f
    private const val OPENING = 0.8f
    private const val REACHES_BACK = 3.5f
    private const val RINGS = 14
    private const val SECTORS = 28
    private const val RINGS_GATHER_AT_THE_NOSE = 0.8f
    private const val RIPPLE = 0.06f
    private const val RIPPLE_PACE = 0.9
    private const val RIPPLE_PACE_SPREAD = 0.15
    private const val RIPPLE_STEP = 1.3
    private const val RIPPLE_LOBES = 3.0

    /** How the sheath glows: brighter as it is looked along, whiter for the first of its length, fading behind. */
    private const val GRAZING_LEAST = 0.25f

    /** How far back from its nose, as a share of its length, the sheath burns bright whatever the angle. */
    private const val NOSE_BURNS_FOR = 0.3f
    private const val GRAZING_SHARPNESS = 2.0f
    private const val WHITE_FOR = 2.5f
    private const val FADES_SHARPNESS = 1.4f
    private const val BRIGHTNESS = 0.9f
    private const val COVERS = 0.35f

    private const val NEARLY_UPRIGHT = 0.99f
    private val UP = Vector3f(0.0f, 1.0f, 0.0f)
    private val EAST = Vector3f(1.0f, 0.0f, 0.0f)

    private const val PIXEL_SALT = 0x5C3A_91E7_04BD_628FL
    private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
    private const val MIX_ONE = -0x40a7b892e31b1a47L
    private const val MIX_TWO = -0x6b2fb644ecceee15L
}
