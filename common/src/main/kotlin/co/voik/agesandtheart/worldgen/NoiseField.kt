package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.TerrainField

/**
 * The smooth-field preset: rolling hills over a sea, the counterpart to the toolkit's hard-edged CSG
 * worlds. Its whole job is to exercise [NoiseHeightmap] — one primitive, no combinators — so what the
 * noise actually looks like is readable without anything else in the way.
 *
 * The sea earns its place here: it reads the relief back to you as coastline, so how far the surface
 * swings is visible at a glance rather than having to be walked.
 */
object NoiseField {

    fun hills(): TerrainField = NoiseHeightmap(
        seed = TERRAIN_SEED,
        // Detail at roughly 128, 64 and 32 blocks — broad hills with a little shape on their flanks.
        firstOctave = -7,
        amplitudes = listOf(1.0, 0.5, 0.25),
        scaleX = 1.0,
        scaleZ = 1.0,
        baseY = 68,
        relief = 30.0,
        flatY = -64,
    )

    private const val TERRAIN_SEED = 0x1DEA_5EEDL
}
