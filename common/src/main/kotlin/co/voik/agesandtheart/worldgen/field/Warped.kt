package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.roundToInt

/**
 * [base], sampled at a column that noise has moved sideways — so its *plan* wanders where [Undulated]
 * moves its *height*.
 *
 * The two are complementary and neither substitutes for the other. Undulating shifts a column up or
 * down, which moves a contour line by the shift divided by the gradient: a lot on level ground, almost
 * nothing on anything steep. A cone's flank is steep everywhere, so however hard it is undulated its
 * outline stays very nearly the circle it started as — the silhouette gives the geometry away from a
 * distance even when the surface is visibly rough. Warping acts on the outline directly and at whatever
 * wavelength it is given, so a round footprint becomes a lobed one and a ruled edge becomes a wandering
 * one, at two noise samples and no change to the shape underneath.
 *
 * Two things follow from it being a *lookup* displacement rather than a deformation:
 *
 * - **A flat region stays flat.** Every column inside it maps to another column inside it, so a crater
 *   floor keeps its floor while the crater's rim goes ragged. That is usually what is wanted, and it is
 *   the reason to reach for this over undulating something that has to stay level.
 * - **Wrap the outermost coherent shape.** Warping a shape that meets another moves only that one, and
 *   what met flush now interpenetrates or gapes. Wrapping the finished tree moves everything together.
 */
data class Warped(
    val base: TerrainField,
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: List<Double>,
    /** Blocks per unit of noise — long wavelengths swing whole flanks, short ones fray an edge. */
    val scale: Double,
    /** How far a column may be moved, on each horizontal axis. */
    val amount: Double,
) : TerrainField {
    override val kind = FieldKind.WARPED

    // A column this far out can still read a column the base is solid at, so the instancing scan has to
    // look this far. Generous rather than exact: the displacement runs on two axes and a normal noise
    // is not strictly bounded to ±1, and over-reaching only costs a cell that turns out to be empty.
    override val horizontalReach = base.horizontalReach + amount * HEADROOM

    override val samplesPerColumn = base.samplesPerColumn + 2

    // Built once in the constructor and only read after, so it is safe across the chunk workers. The
    // two axes are separate noises rather than one read twice — the same noise on both would displace
    // every column along the diagonal and shear the shape instead of wandering it.
    private val alongX = fieldNoise(seed, firstOctave, amplitudes)
    private val alongZ = fieldNoise(seed + SECOND_AXIS, firstOctave, amplitudes)

    private val stretch = scale.coerceAtLeast(SMALLEST_STRETCH)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val sampleX = worldX / stretch
        val sampleZ = worldZ / stretch
        val shiftX = (alongX.get(sampleX, 0.0, sampleZ).toDouble() * amount).roundToInt()
        val shiftZ = (alongZ.get(sampleX, 0.0, sampleZ).toDouble() * amount).roundToInt()
        return base.columnSpans(worldX + shiftX, worldZ + shiftZ)
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        base = base.resized(factor, pivotY),
        scale = scale * factor,
        // A displacement is a distance in the space the base lives in, so it scales with it.
        amount = amount * factor,
    )

    companion object {
        /** Salt, so the two axes are different noises rather than the same one read twice. */
        private const val SECOND_AXIS = 0x57_41_52_50L

        private const val HEADROOM = 2.0

        fun codec(self: Codec<TerrainField>): MapCodec<Warped> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Warped::base),
                Codec.LONG.fieldOf("seed").forGetter(Warped::seed),
                Codec.INT.fieldOf("first_octave").forGetter(Warped::firstOctave),
                Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(Warped::amplitudes),
                Codec.DOUBLE.fieldOf("scale").forGetter(Warped::scale),
                Codec.DOUBLE.fieldOf("amount").forGetter(Warped::amount),
            ).apply(instance, ::Warped)
        }
    }
}
