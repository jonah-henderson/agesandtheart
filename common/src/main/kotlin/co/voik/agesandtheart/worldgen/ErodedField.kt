package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import kotlin.math.roundToInt

/**
 * Weathered rock country rising out of the sea: graded 3D noise, solid at its foot and worn to buttes,
 * arches and overhangs towards its top — the relief a heightmap cannot say, standing on the ground.
 *
 * **Graded, not level.** A level threshold cuts the same share out of every height, which is sponge; one
 * running from nearly-all-solid at the seabed to nearly-none at the top describes a surface that still
 * answers per voxel (see [Noise3D.thresholdAtTop]). [OverworldField] is the same construction tuned to be
 * ordinary; this one is stretched up the vertical so its walls stand sheer and its tops come out flat.
 */
object ErodedField {

    /**
     * [scale] is [SizeScale]'s factor. The country as tuned is the `large` step, and it is resized whole
     * about the sea — breadth, height and the grain of the wear together — while the seabed stays put.
     */
    fun world(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField {
        val country = Noise3D(
            seed = LAND_SEED xor salt,
            // Detail at roughly 128 down to 16 blocks: the buttes, then their benches, then the fretting.
            firstOctave = -7,
            amplitudes = listOf(1.0, 0.7, 0.5, 0.3),
            scaleX = HORIZONTAL_SCALE,
            // Stretched up the vertical, so a wall runs a long way before the noise turns it: sheer sides
            // and flat tops, which is what makes the country read as mesas rather than as lumps.
            scaleY = VERTICAL_SCALE,
            scaleZ = HORIZONTAL_SCALE,
            character = NoiseCharacter.PLAIN,
            threshold = SOLID_AT_THE_FOOT,
            lowY = SEABED_TOP,
            highY = TOP_Y,
            thresholdAtTop = EMPTY_AT_THE_TOP,
        ).resized(scale / TUNED_SIZE, SEA_LEVEL)
        // Up to wherever the resized band now begins, or the country would float over a gap of air.
        val seabed = Slab(lowY = VerticalWindow.MIN_Y, highY = country.lowY)
        return CanyonlandsField.weathered(Union(listOf(seabed, country)), scale * WEATHER_SHARE)
    }

    /** Where the seabed stands at [scale]: the band's foot, which moves as the country is resized about the sea. */
    fun seabedY(scale: Double): Int = SEA_LEVEL + ((SEABED_TOP - SEA_LEVEL) * scale / TUNED_SIZE).roundToInt()

    /**
     * How much of canyonlands' weather this wears, as a factor on the size. Canyonlands' weather is tuned
     * for a table at `colossal`, and this country stands lower.
     */
    private const val WEATHER_SHARE = 1.5

    /** The size the constants below were walked at: `large`. */
    private const val TUNED_SIZE = 2.0

    private const val SEA_LEVEL = 63

    /** The seabed, and the foot the country rises from. */
    private const val SEABED_TOP = 40

    /** The top of the shaped band: nothing stands above it. */
    private const val TOP_Y = 190

    private const val HORIZONTAL_SCALE = 0.6
    private const val VERTICAL_SCALE = 1.5

    // The band's two ends, read together: how far apart decides the relief, and where they sit in the
    // noise's range decides how much of the band is ground rather than sky.
    private const val SOLID_AT_THE_FOOT = -0.45
    private const val EMPTY_AT_THE_TOP = 0.7

    private const val LAND_SEED = 0xE20_DEDL
}
