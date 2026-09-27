package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tall, narrow fissures at any heading, crossing wherever they meet: the space to take *out* of rock, as
 * [co.voik.agesandtheart.worldgen.FissuresField] uses it.
 *
 * Each fissure is a blade: a line in plan that wanders a little from side to side, and across it a lens
 * that is widest at its centre height and closes to a point above and below. Both its width and its height
 * taper to nothing at the two ends. So a column answers with one open run per fissure it falls inside, and
 * the whole field is closed form.
 *
 * Laid out [perCell] to a square cell [spacing] across, each centred anywhere in its cell and at any height
 * in the band that still fits it whole.
 */
data class Fissures(
    val lowY: Int,
    val highY: Int,
    val spacing: Int,
    val perCell: Int,
    /** The longest a fissure runs, end to end. */
    val length: Int,
    /** The tallest a fissure stands, point to point. */
    val height: Int,
    /** The widest a fissure opens, at its middle. */
    val width: Int,
    val seed: Int,
) : TerrainField {
    init {
        require(spacing > 0 && perCell > 0) { "fissures need a cell ($spacing) and something in it ($perCell)" }
        require(length > 0 && height > 0 && width > 0) { "a fissure needs a size ($length, $height, $width)" }
    }

    override val kind = FieldKind.FISSURES
    override val horizontalReach = Double.POSITIVE_INFINITY

    /** How far from its centre a fissure's opening can reach, wander included. */
    private val reach = length / 2.0 + width * (1.0 + WANDER_PER_WIDTH)
    private val ring = ceil(reach / spacing).toInt()

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val cellX = Math.floorDiv(worldX, spacing)
        val cellZ = Math.floorDiv(worldZ, spacing)
        var open = Spans.EMPTY
        for (nearX in cellX - ring..cellX + ring) {
            for (nearZ in cellZ - ring..cellZ + ring) {
                for (slot in 0..<perCell) {
                    val opening = openingOf(nearX, nearZ, slot, worldX + HALF, worldZ + HALF) ?: continue
                    open = open.union(opening)
                }
            }
        }
        return open
    }

    /** Where the fissure in [slot] of this cell is open in the column at ([x], [z]), or null where it is not. */
    private fun openingOf(cellX: Int, cellZ: Int, slot: Int, x: Double, z: Double): Spans? {
        fun drawn(property: Int) = cellHash(cellX, cellZ, seed xor (slot * SLOT_STRIDE) xor property) + HALF

        val dx = x - (cellX + drawn(X)) * spacing
        val dz = z - (cellZ + drawn(Z)) * spacing
        val halfLength = length / 2.0 * between(SHORTEST, 1.0, drawn(LENGTH))
        val thisOnesReach = halfLength + width * (1.0 + WANDER_PER_WIDTH)
        if (dx * dx + dz * dz > thisOnesReach * thisOnesReach) return null

        val heading = drawn(HEADING) * PI
        val along = dx * cos(heading) + dz * sin(heading)
        if (abs(along) >= halfLength) return null
        val taper = sqrt(1.0 - (along / halfLength) * (along / halfLength))

        val halfWidth = width / 2.0 * between(NARROWEST, 1.0, drawn(WIDTH)) * taper
        val wander = width * WANDER_PER_WIDTH * (
            sin(along / halfLength * LONG_WAVES + drawn(LONG_PHASE) * TURN) +
                SHORT_SHARE * sin(along / halfLength * SHORT_WAVES + drawn(SHORT_PHASE) * TURN)
            )
        val across = abs(-dx * sin(heading) + dz * cos(heading) - wander)
        if (across >= halfWidth) return null

        val band = (highY - lowY).toDouble()
        val fullHalfHeight = minOf(height / 2.0 * between(SHORTEST, 1.0, drawn(HEIGHT)), band / 2.0)
        val centreY = lowY + fullHalfHeight + drawn(CENTRE_Y) * (band - 2.0 * fullHalfHeight)
        val swayedCentreY = centreY + fullHalfHeight * SWAY * sin(along / halfLength * PI + drawn(SWAY_PHASE) * TURN)
        // Lower toward the ends as well as narrower, so a fissure's tips close in plan and in section alike.
        val halfHeight = fullHalfHeight * sqrt(taper)
        // Widest at its centre height and closing to a point: width(y) = halfWidth * (1 - s²), s the height
        // from the centre as a share of halfHeight, so the column is open where that width exceeds [across].
        val reachUp = halfHeight * sqrt(1.0 - across / halfWidth)
        val low = maxOf(ceil(swayedCentreY - reachUp).toInt(), lowY)
        val high = minOf(floor(swayedCentreY + reachUp).toInt(), highY)
        return if (high < low) null else Spans.of(low, high)
    }

    override fun resized(factor: Double, pivotY: Int): Fissures = copy(
        lowY = scaledAbout(lowY, factor, pivotY),
        highY = scaledAbout(highY, factor, pivotY),
        spacing = scaled(spacing, factor).coerceAtLeast(1),
        length = scaled(length, factor).coerceAtLeast(1),
        height = scaled(height, factor).coerceAtLeast(1),
        width = scaled(width, factor).coerceAtLeast(1),
    )

    private fun between(least: Double, most: Double, share: Double) = least + (most - least) * share

    companion object {
        val CODEC: MapCodec<Fissures> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("low_y").forGetter(Fissures::lowY),
                Codec.INT.fieldOf("high_y").forGetter(Fissures::highY),
                Codec.INT.fieldOf("spacing").forGetter(Fissures::spacing),
                Codec.INT.fieldOf("per_cell").forGetter(Fissures::perCell),
                Codec.INT.fieldOf("length").forGetter(Fissures::length),
                Codec.INT.fieldOf("height").forGetter(Fissures::height),
                Codec.INT.fieldOf("width").forGetter(Fissures::width),
                Codec.INT.fieldOf("seed").forGetter(Fissures::seed),
            ).apply(instance, ::Fissures)
        }

        private const val HALF = 0.5
        private const val TURN = 2.0 * PI

        // How much of the longest, tallest and widest a fissure may be drawn at, at the least.
        private const val SHORTEST = 0.6
        private const val NARROWEST = 0.5

        // The side-to-side wander, in widths: a long bend over the fissure's length and a shorter kink on it.
        private const val WANDER_PER_WIDTH = 0.8
        private const val LONG_WAVES = 2.2
        private const val SHORT_WAVES = 7.0
        private const val SHORT_SHARE = 0.35

        // How far the centre height rises and falls along the length, as a share of the half height.
        private const val SWAY = 0.12

        // Separates one fissure of a cell from the next, and each drawn property from the others.
        private const val SLOT_STRIDE = 0x3C6E_F372
        private const val X = 1
        private const val Z = 2
        private const val LENGTH = 3
        private const val HEADING = 4
        private const val WIDTH = 5
        private const val HEIGHT = 6
        private const val CENTRE_Y = 7
        private const val LONG_PHASE = 8
        private const val SHORT_PHASE = 9
        private const val SWAY_PHASE = 10
    }
}
