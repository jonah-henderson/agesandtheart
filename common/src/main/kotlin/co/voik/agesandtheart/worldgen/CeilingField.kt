package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union

/**
 * **What shuts an Age overhead when its rock is a field of ours.**
 *
 * A template's roof is part of its rock: the nether's ceiling is solid because the nether's density
 * function makes the top of that world solid, and its surface rule only *paints* the result — `BEDROCK`
 * and `NETHERRACK` replace a block that is already there, and `UNDER_CEILING` means nothing where there is
 * no ceiling. So a book that names a landform takes the roof away with the rock, and `sealed=always` was
 * left saying only what the dimension type says: no skylight, a spinning compass, an exploding bed, and
 * open air all the way up.
 *
 * **Not a slab.** A flat lid reads as the underside of a build. What makes a roofed world feel roofed is
 * that the rock above you is as uneven as the rock below — so the vault's underside rolls, and masses hang
 * off it into the space beneath.
 */
object CeilingField {

    /**
     * The lid over [window], seeded from [salt].
     *
     * Solid to the very top rather than a layer with sky above it: a world shut overhead has *rock* up
     * there, and a lid you can tunnel above and stand on is the same bug as the roof that was not there.
     */
    fun over(window: VerticalWindow, salt: Long): TerrainField = Union(
        listOf(vault(window, salt), hanging(salt)),
    )

    /**
     * The vault, hung from the top of the world down to a rolling underside — the same primitive a
     * landform uses for its surface, with the noisy face *below* the flat bound instead of above it.
     */
    private fun vault(window: VerticalWindow, salt: Long): TerrainField = NoiseHeightmap(
        seed = VAULT_SEED xor salt,
        // Broad swells at about 256 blocks with detail down to 64, so the vault reads as one sky of rock
        // rather than as a landscape hung upside down.
        firstOctave = -8,
        amplitudes = listOf(1.0, 0.55, 0.3),
        scaleX = 1.0,
        scaleZ = 1.0,
        baseY = UNDERSIDE_Y,
        relief = UNDERSIDE_RELIEF.toDouble(),
        flatY = window.topY,
    )

    /**
     * What comes down off it — clumps and columns of rock, thinning as they fall.
     *
     * The band is graded from **almost solid where it meets the vault** to almost nothing at its bottom, so
     * the mass grows out of the ceiling rather than being scattered under it. What survives lowest is what
     * the noise happened to make thickest, which is where a stalactite comes from.
     *
     * Some of it comes adrift all the same, and that is left alone: a mass of rock hanging unattached over
     * your head is a thing the nether does, and a ceiling with no loose pieces in it reads as a moulding.
     */
    private fun hanging(salt: Long): TerrainField = Noise3D(
        seed = HANGING_SEED xor salt,
        // Clumps at about 32 blocks, the same octave the eroded landform bites its masses with.
        firstOctave = -5,
        amplitudes = listOf(1.0, 0.5),
        scaleX = HANGING_SCALE,
        scaleY = HANGING_SCALE * HANGING_DRAW,
        scaleZ = HANGING_SCALE,
        character = NoiseCharacter.PLAIN,
        threshold = SPARSEST,
        lowY = UNDERSIDE_Y - HANGING_REACH,
        // Up past the vault's highest swell rather than to its mean. Stopping at the mean left a pendant
        // hanging in mid-air wherever the roof above it happened to ride higher than that.
        highY = UNDERSIDE_Y + UNDERSIDE_RELIEF,
        thresholdAtTop = DENSEST,
    )

    /**
     * Where the underside of the roof sits on average.
     *
     * Chosen against the landforms rather than against the band: our shapes crown between about sixty and
     * a hundred, so this leaves the same order of headroom the nether has over its own floor. A landform
     * that reaches higher is not clipped — it meets the roof and stands as a column, which is a thing the
     * nether does too.
     */
    private const val UNDERSIDE_Y = 160

    /** How far that underside rolls either way, so the roof has relief of its own rather than a level. */
    private const val UNDERSIDE_RELIEF = 18

    /** How far the hanging masses reach down at most. */
    private const val HANGING_REACH = 30

    private const val HANGING_SCALE = 0.7

    /** Drawn out vertically, which is what makes a lump into something that hangs. */
    private const val HANGING_DRAW = 1.8

    /** The threshold at the bottom of the hanging band, where almost nothing survives. */
    private const val SPARSEST = 0.55

    /** And at the top, where it meets the vault and has to be part of it rather than float under it. */
    private const val DENSEST = -0.25

    private const val VAULT_SEED = 0x0C_E111_0BADL
    private const val HANGING_SEED = 0x5_7A1AC_71E5L
}
