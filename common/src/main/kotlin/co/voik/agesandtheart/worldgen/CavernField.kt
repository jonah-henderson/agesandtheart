package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField

/**
 * Hills hollowed out from beneath by a network of tunnels and chambers — the preset that exists to show
 * what [NoiseCharacter.RIDGED] is for, and the first Age whose interesting half is underground.
 *
 * **The caves are shape, not carving.** A heightmap column is one solid run and cannot express a roof,
 * so everything hollow used to need a carver; ridged noise gives a column as many runs as it likes, and
 * the caves live in the field tree — composable, resizable, and visible in the offline preview.
 *
 * Ridged noise peaks along its zero crossings, which are continuous surfaces rather than isolated lumps,
 * so thresholding near the top of the range leaves a connected network. [CAVE_SCALE_Y] is squashed
 * against the horizontal scales, so chambers come out wider than tall and read as caves, not shafts.
 */
object CavernField {

    fun world(): TerrainField = Subtract(
        base = NoiseHeightmap(
            seed = LAND_SEED,
            firstOctave = -7,
            amplitudes = listOf(1.0, 0.5, 0.25),
            scaleX = 1.0,
            scaleZ = 1.0,
            baseY = SURFACE_Y,
            relief = SURFACE_RELIEF,
            flatY = WORLD_FLOOR,
        ),
        cut = caves(),
    )

    /**
     * The tunnels alone. Public because the preview draws it on its own: a cave system is much easier to
     * read as a solid lattice hanging in space than as absence inside a hill.
     */
    fun caves(): Noise3D = Noise3D(
        seed = CAVE_SEED,
        // Detail at roughly 64 and 32 blocks. Coarse on purpose: with RIDGED the passage *width* is set
        // by how steeply the noise crosses zero, so broader features cross more gently and leave
        // something walkable, where finer ones cut the same volume as cracks too thin to squeeze through.
        firstOctave = -6,
        amplitudes = listOf(1.0, 0.5),
        scaleX = CAVE_SCALE_XZ,
        scaleY = CAVE_SCALE_Y,
        scaleZ = CAVE_SCALE_XZ,
        character = NoiseCharacter.RIDGED,
        // High, because with RIDGED a higher threshold means *thinner* tubes: this is what keeps the
        // network from eating the whole hill. It is the dial to turn if the caves feel too open or too rare.
        threshold = CAVE_THRESHOLD,
        // The band bounds the walk, and so the cost. It stops below the surface so hilltops stay whole,
        // and short of bedrock so there is always floor underneath.
        lowY = CAVE_LOWEST_Y,
        highY = CAVE_HIGHEST_Y,
    )

    private const val WORLD_FLOOR = -64
    private const val SURFACE_Y = 78
    private const val SURFACE_RELIEF = 26.0

    private const val CAVE_SCALE_XZ = 1.4
    private const val CAVE_SCALE_Y = 0.7
    // Around a tenth of the band, per `./gradlew :common:noiseprofile`. The number is high because
    // RIDGED peaks where the raw noise is *near zero*, which is most of it: at 0.6 this keeps half the
    // volume and reads as sponge rather than as caves. Do not guess this dial; read the profile.
    private const val CAVE_THRESHOLD = 0.90
    private const val CAVE_LOWEST_Y = -56
    private const val CAVE_HIGHEST_Y = 64

    private const val LAND_SEED = 0xCAFE_1A2DL
    private const val CAVE_SEED = 0xDEED_DA1EL
    private const val TABLE_SEED = 0xD4A1_9EL
}
