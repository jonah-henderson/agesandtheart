package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.VerticalWindow
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wind-carved ridges on a rolling floor, all lying along the wind: each blunt and highest at its upwind nose, tapering
 * away downwind, steep-sided and stepped in ledges, with corridors between (the Lut desert's yardangs).
 *
 * The country is laid on a lattice turned to the wind, [cellLength] along it and [cellWidth] across, bent by a slow
 * warp so the rows wander, and each row slid along by its own amount so the ridges stagger. A cell holds at most one
 * ridge, which keeps inside its cell, so a column asks one cell. Where the ridges crowd is a broad noise, swung by
 * [variety]: packed wide into narrow channels in one place, thinned to open ground in another.
 */
data class Yardangs(
    val seed: Long,
    /** The way the wind blows towards, in degrees from east turning to south; the noses face the other way. */
    val bearingDegrees: Double,
    /** The corridor floor's mean top block. */
    val floorY: Int,
    val cellLength: Double,
    val cellWidth: Double,
    /** How high the tallest ridge stands over the floor. */
    val tallest: Double,
    /** How far the crowding swings from place to place: 0 none, 1 unsaid, 2 the most. */
    val variety: Double,
) : TerrainField {
    override val kind = FieldKind.YARDANGS
    override val horizontalReach = Double.POSITIVE_INFINITY
    override val samplesPerColumn = SAMPLES

    private val alongX = cos(Math.toRadians(bearingDegrees))
    private val alongZ = sin(Math.toRadians(bearingDegrees))
    private val salt = (seed xor (seed ushr Int.SIZE_BITS)).toInt()

    // One noise read on separate planes for each use, in cells rather than blocks so a size carries it.
    private val noise = fieldNoise(seed, 0, NOISE_AMPLITUDES)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val x = worldX + HALF_BLOCK
        val z = worldZ + HALF_BLOCK
        val along = x * alongX + z * alongZ
        val across = -x * alongZ + z * alongX
        val inCellsAlong = along / cellLength
        val inCellsAcross = across / cellWidth
        val floor = (read(inCellsAlong * FLOOR_FREQUENCY_ALONG, inCellsAcross * FLOOR_FREQUENCY_ACROSS, FLOOR_PLANE) *
            tallest * FLOOR_RELIEF).roundToInt()
        return Spans.of(VerticalWindow.MIN_Y, floorY + floor + riseAt(along, across, inCellsAlong, inCellsAcross, x, z))
    }

    /** How far the ridge here stands over the floor, in whole blocks; nought in a corridor. */
    private fun riseAt(along: Double, across: Double, inCellsAlong: Double, inCellsAcross: Double, x: Double, z: Double): Int {
        val warp = read(inCellsAlong * WARP_FREQUENCY_ALONG, inCellsAcross * WARP_FREQUENCY_ACROSS, WARP_PLANE)
        val bent = across + warp * cellWidth * WARP_REACH
        val row = floor(bent / cellWidth).toInt()
        val rowSlide = unit(row, 0, ROW_SLIDE) * cellLength
        val column = floor((along - rowSlide) / cellLength).toInt()

        // Read at the cell's middle, so a ridge is crowded or not as a whole rather than cut off part way along.
        val cellAlong = (rowSlide / cellLength) + column + 0.5
        val cellAcross = row + 0.5
        val crowdingRead = read(cellAlong * CROWDING_FREQUENCY, cellAcross * CROWDING_FREQUENCY, CROWDING_PLANE)
        val crowding = (crowdingRead * CROWDING_GAIN * variety).coerceIn(-1.0, 1.0)
        if (unit(column, row, PRESENT) > SHARE_WITH_A_RIDGE + CROWDED_SHARE * crowding) return 0

        val length = cellLength * lerp(SHORTEST, LONGEST, unit(column, row, LENGTH))
        val widthShare = lerp(NARROWEST, WIDEST, unit(column, row, WIDTH)) * (1.0 + CROWDED_WIDENING * crowding)
        val halfWidth = cellWidth * widthShare.coerceIn(NARROWEST_EVER, WIDEST_EVER)
        val height = tallest * lerp(LOWEST, 1.0, unit(column, row, HEIGHT))
        val noseAt = rowSlide + column * cellLength + (cellLength - length) * unit(column, row, NOSE)
        val roomAcross = cellWidth / 2 - halfWidth
        val centre = (row + 0.5) * cellWidth + (unit(column, row, CENTRE) * 2 - 1) * roomAcross * CENTRE_REACH

        val t = (along - noseAt) / length
        if (t < 0.0 || t >= 1.0) return 0
        // Each ridge curls a little towards its tail, kept to the room its cell leaves it.
        val curl = (unit(column, row, CURL) * 2 - 1) * roomAcross * (1.0 - CENTRE_REACH) * t * t
        val widthHere = halfWidth * widthProfile(t)
        if (widthHere <= 0.0) return 0
        // Only ever eaten into, so a ridge never reaches past its cell to be cut off square by the next.
        val bite = (read(x / cellWidth * RAGGED_FREQUENCY, z / cellWidth * RAGGED_FREQUENCY, RAGGED_PLANE) + 1.0) / 2.0
        val ragged = 1.0 + bite.coerceAtLeast(0.0) * RAGGEDNESS
        val outward = abs(bent - centre - curl) / widthHere * ragged
        if (outward >= 1.0) return 0
        val uneven = 1.0 + read(inCellsAlong * CREST_FREQUENCY, row.toDouble(), CREST_PLANE) * UNEVENNESS
        val crest = height * crestProfile(t) * uneven
        val rise = crest * sqrt(1.0 - outward.pow(FLANK_STEEPNESS))
        // The flanks fall in ledges, as layered rock wears; the top stays smooth.
        val onAFlank = outward > FLANK_FROM
        return if (onAFlank) (floor(rise / LEDGE) * LEDGE).toInt() else rise.toInt()
    }

    private fun read(first: Double, second: Double, plane: Double): Double = noise.get(first, plane, second).toDouble()

    /** A number in 0..1 for a cell, separated by [use]. */
    private fun unit(column: Int, row: Int, use: Int): Double = cellHash(column, row, salt xor use) + 0.5

    override fun resized(factor: Double, pivotY: Int) = copy(
        floorY = scaledAbout(floorY, factor, pivotY),
        cellLength = cellLength * factor,
        cellWidth = cellWidth * factor,
        tallest = tallest * factor,
    )

    companion object {
        val CODEC: MapCodec<Yardangs> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.LONG.fieldOf("seed").forGetter(Yardangs::seed),
                Codec.DOUBLE.fieldOf("bearing").forGetter(Yardangs::bearingDegrees),
                Codec.INT.fieldOf("floor_y").forGetter(Yardangs::floorY),
                Codec.DOUBLE.fieldOf("cell_length").forGetter(Yardangs::cellLength),
                Codec.DOUBLE.fieldOf("cell_width").forGetter(Yardangs::cellWidth),
                Codec.DOUBLE.fieldOf("tallest").forGetter(Yardangs::tallest),
                Codec.DOUBLE.fieldOf("variety").forGetter(Yardangs::variety),
            ).apply(instance, ::Yardangs)
        }

        /** Rounded and blunt over the first [NOSE_SHARE] of the length, then narrowing to a point downwind. */
        private fun widthProfile(t: Double): Double =
            if (t < NOSE_SHARE) sqrt(t / NOSE_SHARE) else (1.0 - (t - NOSE_SHARE) / (1.0 - NOSE_SHARE)).pow(TAPER)

        /** A steep face upwind, then a crest falling away towards the tail. */
        private fun crestProfile(t: Double): Double {
            val face = minOf(1.0, sqrt(t / FACE_SHARE))
            return face * (1.0 - CREST_FALL * t)
        }

        private fun lerp(from: Double, to: Double, share: Double) = from + (to - from) * share

        private const val HALF_BLOCK = 0.5
        private const val SAMPLES = 5
        private val NOISE_AMPLITUDES = listOf(1.0, 0.5)

        private const val SHARE_WITH_A_RIDGE = 0.8
        private const val SHORTEST = 0.55
        private const val LONGEST = 0.95
        private const val NARROWEST = 0.2
        private const val WIDEST = 0.36
        private const val WIDEST_EVER = 0.47
        private const val NARROWEST_EVER = 0.12
        private const val LOWEST = 0.4
        /** How far a ridge's middle may stray across its cell; the rest of the room is left for its curl. */
        private const val CENTRE_REACH = 0.5

        /** At the most crowding, every cell holds a ridge and each is half again as wide. */
        private const val CROWDED_SHARE = 0.3
        private const val CROWDED_WIDENING = 0.5

        private const val NOSE_SHARE = 0.18
        private const val TAPER = 0.8
        private const val FACE_SHARE = 0.06
        private const val CREST_FALL = 0.7
        private const val FLANK_STEEPNESS = 6.0
        private const val FLANK_FROM = 0.55
        private const val LEDGE = 3.0

        /** Noise frequencies, in cycles per cell. */
        /**
         * The rows meander along the wind and barely change across it: a warp changing faster across than the rows
         * are wide folds them over each other, and the fold slices ridges into wedges.
         */
        private const val WARP_FREQUENCY_ALONG = 0.35
        private const val WARP_FREQUENCY_ACROSS = 0.04
        /** How far the rows bend across the wind, in cells. */
        private const val WARP_REACH = 1.0
        private const val CROWDING_FREQUENCY = 0.3
        /** Vanilla's noise seldom reads past a third either way, so the crowding is pushed out to fill its range. */
        private const val CROWDING_GAIN = 2.5
        private const val RAGGED_FREQUENCY = 4.0
        private const val RAGGEDNESS = 0.3
        private const val CREST_FREQUENCY = 3.0
        private const val UNEVENNESS = 0.25
        private const val FLOOR_FREQUENCY_ALONG = 1.5
        private const val FLOOR_FREQUENCY_ACROSS = 3.0
        /** The floor rolls about a tenth of the tallest ridge either way: three or four blocks unsaid. */
        private const val FLOOR_RELIEF = 0.1

        private const val WARP_PLANE = 0.0
        private const val CROWDING_PLANE = 31.0
        private const val RAGGED_PLANE = 67.0
        private const val CREST_PLANE = 101.0
        private const val FLOOR_PLANE = 149.0

        private const val ROW_SLIDE = 0x524F57
        private const val PRESENT = 0x50524553
        private const val LENGTH = 0x4C454E47
        private const val WIDTH = 0x57494454
        private const val HEIGHT = 0x48454947
        private const val NOSE = 0x4E4F5345
        private const val CENTRE = 0x43454E54
        private const val CURL = 0x4355524C
    }
}
