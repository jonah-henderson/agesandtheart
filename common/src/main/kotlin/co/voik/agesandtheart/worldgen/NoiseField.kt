package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.TerrainField
import kotlin.math.sqrt

/**
 * The smooth-field preset: rolling hills over a sea, the counterpart to the toolkit's hard-edged CSG
 * worlds. Its whole job is to exercise [NoiseHeightmap] — one primitive, no combinators — so what the
 * noise actually looks like is readable without anything else in the way.
 *
 * The sea earns its place here: it reads the relief back to you as coastline, so how far the surface
 * swings is visible at a glance rather than having to be walked.
 */
object NoiseField {

    /**
     * [scale] is [SizeScale]'s factor. A bigger hill is mostly a broader one: the wavelength takes the whole
     * factor and the relief only its square root, so colossal hills are rolling country rather than peaks.
     */
    fun hills(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField = NoiseHeightmap(
        seed = TERRAIN_SEED xor salt,
        // Detail at roughly 128, 64 and 32 blocks — broad hills with a little shape on their flanks.
        firstOctave = -7,
        amplitudes = listOf(1.0, 0.5, 0.25),
        scaleX = scale,
        scaleZ = scale,
        baseY = 68,
        relief = RELIEF * sqrt(scale),
        flatY = VerticalWindow.MIN_Y,
    )

    private const val RELIEF = 30.0

    private const val TERRAIN_SEED = 0x1DEA_5EEDL
}
