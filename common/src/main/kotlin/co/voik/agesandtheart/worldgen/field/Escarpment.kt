package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Ground standing at two heights either side of a wandering line, with a cliff where they meet — **one
 * step, not a wedge**, which is the whole of the difference from [Canyon].
 *
 * A canyon is symmetric about its axis and both sides come back up. This does not: everything one way of
 * the line lies at [lowY] and everything the other way at [highY], and what happens between them is a
 * face [faceWidth] blocks across. Narrow, and the face is a cliff; wide, and it is a hillside.
 *
 * **The line's plan is where the drama is, not its profile.** A step function of distance is a ruled
 * edge, so [roughness] is deliberately coarse and multi-octave: it moves the face by tens of blocks over
 * hundreds, and by ones over tens, which is what makes headlands and coves out of a straight coast rather
 * than merely fraying it.
 */
data class Escarpment(
    /** Which way the line runs, in radians, zero running it along +Z and a quarter turn along +X. */
    val bearing: Double,
    /** How far the line sits from the origin, measured across it. Zero runs it through the origin. */
    val offset: Double,
    /** The top of the ground on the low side — a seabed, where a sea is poured over it. */
    val lowY: Int,
    /** And on the high side: the plateau's surface. */
    val highY: Int,
    /** What both stand on. */
    val floorY: Int,
    val seed: Long,
    /** How many blocks the face takes to climb. A few is a cliff; a hundred is a slope. */
    val faceWidth: Double = DEFAULT_FACE_WIDTH,
    /**
     * How many steps the face climbs in. One is a single sheer drop.
     *
     * **This is what lets the face be weathered at all.** `Weathered` works downward from a column's own
     * top, so it can only reach ground that something *stands on* — and a face crossed in one step has
     * almost no column topping out on it (measured: under half a percent). Ledges give the weather
     * treads to bite, and the bitten treads are what read as buttresses and gullies.
     */
    val ledges: Int = DEFAULT_LEDGES,
    /** How far the line wanders either way, in blocks. */
    val meanderReach: Double = DEFAULT_MEANDER_REACH,
    /** Blocks *along* the line per unit of meander noise — how long one sweep of coast runs. */
    val meanderStretch: Double = DEFAULT_MEANDER_STRETCH,
    /** How far the face is thrown either way, at every scale at once. Bays and coves come from here. */
    val roughness: Double = DEFAULT_ROUGHNESS,
    /** How far the ground rolls above and below its level, on both sides. Zero leaves two flat tables. */
    val relief: Double = DEFAULT_RELIEF,
) : TerrainField {
    override val kind = FieldKind.ESCARPMENT

    // A coast runs right across a world, so there is no bounded neighbourhood to scan — as with [Rift].
    override val horizontalReach = Double.POSITIVE_INFINITY

    override val samplesPerColumn = 3

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val alongAxis = alongBearing(bearing, worldX, worldZ)
        val acrossAxis = acrossBearing(bearing, worldX, worldZ)
        val wandered = meanderNoise.getValue(alongAxis / meanderStretch, 0.0, 0.0) * meanderReach
        val thrown = faceNoise.getValue(worldX / FACE_STRETCH, 0.0, worldZ / FACE_STRETCH) * roughness
        // Zero at the foot of the face and one at its head, so the climb below is the cliff itself.
        val upTheFace = ((acrossAxis - offset - wandered + thrown) / faceWidth + HALF).coerceIn(0.0, 1.0)
        val standing = lowY + (highY - lowY) * climbedAt(upTheFace) + reliefAt(worldX, worldZ)
        return Spans.of(floorY, standing.roundToInt())
    }

    /** How far up the face the ground stands, in [ledges] steps, each eased so its riser is not a wall. */
    private fun climbedAt(upTheFace: Double): Double {
        val steps = ledges.coerceAtLeast(1)
        val reached = upTheFace * steps
        val ledge = floor(reached)
        return ((ledge + eased(reached - ledge)) / steps).coerceIn(0.0, 1.0)
    }

    private fun eased(across: Double) = across * across * (3.0 - 2.0 * across)

    /**
     * How far the ground rolls here. Applied to both sides alike and through the face as well: a seabed
     * and a plateau are the same rock, and singling the face out would draw a seam along the one line the
     * shape most wants to look unplanned.
     */
    private fun reliefAt(worldX: Int, worldZ: Int): Double {
        if (relief <= 0.0) return 0.0
        val rolling = reliefNoise.getValue(worldX / RELIEF_STRETCH, 0.0, worldZ / RELIEF_STRETCH)
        return rolling.coerceIn(-1.0, 1.0) * relief
    }

    private val meanderNoise = fieldNoise(seed, MEANDER_OCTAVE, MEANDER_AMPLITUDES)
    private val faceNoise = fieldNoise(seed xor FACE_SALT, FACE_OCTAVE, FACE_AMPLITUDES)
    private val reliefNoise = fieldNoise(seed xor RELIEF_SALT, RELIEF_OCTAVE, RELIEF_AMPLITUDES)

    override fun resized(factor: Double, pivotY: Int) = copy(
        offset = offset * factor,
        lowY = scaledAbout(lowY, factor, pivotY),
        highY = scaledAbout(highY, factor, pivotY),
        floorY = scaledAbout(floorY, factor, pivotY),
        faceWidth = faceWidth * factor,
        meanderReach = meanderReach * factor,
        meanderStretch = meanderStretch * factor,
        roughness = roughness * factor,
        relief = relief * factor,
    )

    companion object {
        private const val HALF = 0.5

        /**
         * How wide the face is. Wide enough that its [ledges] are treads rather than a one-block lip, and
         * narrow enough against a drop of a hundred and seventy that the whole still reads as a cliff.
         */
        const val DEFAULT_FACE_WIDTH = 30.0

        /** Three, so the face has two ledges in it — enough to buttress, few enough to stay a cliff. */
        const val DEFAULT_LEDGES = 3

        /**
         * How far the line wanders, and how long one sweep runs — **the coast's shape**, as against
         * [roughness]'s texture. A sweep shorter than a render distance is what makes the cliff read as
         * going somewhere rather than as a wall ruled across the world.
         */
        const val DEFAULT_MEANDER_REACH = 180.0
        const val DEFAULT_MEANDER_STRETCH = 50.0

        /** How far the face is thrown at bay-and-headland scale. */
        const val DEFAULT_ROUGHNESS = 50.0

        /** How far the ground rolls. Enough to break the table without spoiling the cliff's line. */
        const val DEFAULT_RELIEF = 14.0

        // Long sweeps: at this octave a unit of noise is sixteen of input.
        private const val MEANDER_OCTAVE = -4
        private val MEANDER_AMPLITUDES = listOf(1.0, 0.5)

        // Bays at sixty-odd blocks down to a ragged edge at eight. **Four octaves, not six**: spreading
        // the same amplitude over more of them normalises every one of them down, and a coarse first
        // octave then dominates a picture that reads smoother than four octaves did. The coast's large
        // shape is [meanderReach]'s job, and this one is texture.
        private const val FACE_OCTAVE = -6
        private val FACE_AMPLITUDES = listOf(1.0, 0.7, 0.45, 0.25)
        private const val FACE_STRETCH = 1.0
        private const val FACE_SALT = 0xC11FFL

        private const val RELIEF_OCTAVE = -4
        private val RELIEF_AMPLITUDES = listOf(1.0, 0.5)
        private const val RELIEF_STRETCH = 6.0
        private const val RELIEF_SALT = 0x201_1E9L

        /** No children, so no need for the recursive field codec — the same shape [Rift] has. */
        val CODEC: MapCodec<Escarpment> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("bearing").forGetter(Escarpment::bearing),
                Codec.DOUBLE.fieldOf("offset").forGetter(Escarpment::offset),
                Codec.INT.fieldOf("low_y").forGetter(Escarpment::lowY),
                Codec.INT.fieldOf("high_y").forGetter(Escarpment::highY),
                Codec.INT.fieldOf("floor_y").forGetter(Escarpment::floorY),
                Codec.LONG.fieldOf("seed").forGetter(Escarpment::seed),
                Codec.DOUBLE.optionalFieldOf("face_width", DEFAULT_FACE_WIDTH).forGetter(Escarpment::faceWidth),
                Codec.INT.optionalFieldOf("ledges", DEFAULT_LEDGES).forGetter(Escarpment::ledges),
                Codec.DOUBLE.optionalFieldOf("meander_reach", DEFAULT_MEANDER_REACH)
                    .forGetter(Escarpment::meanderReach),
                Codec.DOUBLE.optionalFieldOf("meander_stretch", DEFAULT_MEANDER_STRETCH)
                    .forGetter(Escarpment::meanderStretch),
                Codec.DOUBLE.optionalFieldOf("roughness", DEFAULT_ROUGHNESS).forGetter(Escarpment::roughness),
                Codec.DOUBLE.optionalFieldOf("relief", DEFAULT_RELIEF).forGetter(Escarpment::relief),
            ).apply(instance, ::Escarpment)
        }
    }
}
