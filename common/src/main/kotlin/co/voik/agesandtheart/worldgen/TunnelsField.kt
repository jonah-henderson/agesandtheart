package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter

/**
 * A network of tunnels through the rock — the `tunnels` underground, and what [NoiseCharacter.RIDGED] is for.
 *
 * Ridged noise peaks along its zero crossings, which are continuous surfaces rather than isolated lumps,
 * so thresholding near the top of the range leaves a connected network of fairly even tubes: nothing like
 * vanilla's cheese chambers and spaghetti, which is the reason to have both. [CAVE_SCALE_Y] is squashed
 * against the horizontal scales, so passages come out wider than tall and read as tunnels, not shafts.
 */
object TunnelsField {

    /**
     * The tunnels between [lowY] and [highY], as the space to take out of the rock.
     *
     * [scale] is [SizeScale]'s factor, and it is the noise's: every passage grows with it while the band
     * stays the height the landform gave it.
     */
    fun tubes(lowY: Int, highY: Int, salt: Long = 0L, scale: Double = SizeScale.ORDINARY): Noise3D = Noise3D(
        seed = CAVE_SEED xor salt,
        // Detail at roughly 64 and 32 blocks. Coarse on purpose: with RIDGED the passage *width* is set
        // by how steeply the noise crosses zero, so broader features cross more gently and leave
        // something walkable, where finer ones cut the same volume as cracks too thin to squeeze through.
        firstOctave = -6,
        amplitudes = listOf(1.0, 0.5),
        scaleX = CAVE_SCALE_XZ * scale,
        scaleY = CAVE_SCALE_Y * scale,
        scaleZ = CAVE_SCALE_XZ * scale,
        character = NoiseCharacter.RIDGED,
        // High, because with RIDGED a higher threshold means *thinner* tubes: this is what keeps the
        // network from eating the whole rock. It is the dial to turn if the tunnels feel too open or too rare.
        threshold = CAVE_THRESHOLD,
        lowY = lowY,
        highY = highY,
    )

    private const val CAVE_SCALE_XZ = 1.4
    private const val CAVE_SCALE_Y = 0.7
    // Around a tenth of the band, per `./gradlew :common:noiseprofile`. The number is high because
    // RIDGED peaks where the raw noise is *near zero*, which is most of it: at 0.6 this keeps half the
    // volume and reads as sponge rather than as tunnels. Do not guess this dial; read the profile.
    private const val CAVE_THRESHOLD = 0.90

    private const val CAVE_SEED = 0xDEED_DA1EL
}
