package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union

/**
 * Ordinary ground: continents, seas, hills and headlands, with the overhangs and stacks that only a
 * volumetric field can say. **An approximation of Minecraft's own overworld, never a reproduction of it** —
 * vanilla's shape is a stack of splines over continentalness, erosion and ridges, and what is here is one
 * graded [Noise3D] that happens to land in the same country.
 *
 * The world is solid to [SOLID_TOP] and noise above it. That is not a saving so much as the point: the
 * deep rock is what an underground is cut out of, so leaving it whole is what makes room for one, and the
 * noise only has to describe the part of the world anybody walks on. It also keeps the cost to the band —
 * about a hundred samples a column, the same order as `caverns`.
 *
 * **It sits high.** The waterline is at [WATERLINE] rather than vanilla's 63, because the whole reason this
 * preset exists is to have something under it: at 63 there is one storey of rock beneath the sea floor and
 * at this height there are two. See `GreatHalls`.
 */
object OverworldField {

    fun world(salt: Long = 0L): TerrainField = Union(
        listOf(
            // Whole, not noisy: this is the rock the halls are taken out of.
            Slab(lowY = VerticalWindow.MIN_Y, highY = SOLID_TOP),
            surface(salt),
        ),
    )

    /**
     * The part of the world that has a shape. Public because the preview draws it alone — over the slab it
     * is only the top hundred blocks that show, and it is the only half worth reading.
     */
    fun surface(salt: Long = 0L): Noise3D = Noise3D(
        seed = LAND_SEED xor salt,
        // Detail at roughly 256 down to 32 blocks: continents, then coasts, then something on a hillside.
        firstOctave = -8,
        amplitudes = listOf(1.0, 0.6, 0.35, 0.2),
        // Broader across than up, so the land comes out layered and benched rather than knobbly.
        scaleX = HORIZONTAL_SCALE,
        scaleY = VERTICAL_SCALE,
        scaleZ = HORIZONTAL_SCALE,
        character = NoiseCharacter.PLAIN,
        // Almost everything solid where the band meets the slab, so the two join without a seam.
        threshold = SOLID_AT_THE_BOTTOM,
        lowY = SOLID_TOP,
        highY = SKY_TOP,
        // And past what the noise can reach at the top, so nothing stands at the ceiling.
        thresholdAtTop = EMPTY_AT_THE_TOP,
    )

    /** Solid below this, shaped above it. The underground lives beneath, and needs the room. */
    const val SOLID_TOP = 104

    /**
     * The top of the shaped band, and **the dial that decides how much of the world is sea.** The ground
     * crosses the waterline about a third of the way up the band, so lifting this lifts the whole
     * coastline out of the water — at 208 nearly nothing was ocean.
     */
    private const val SKY_TOP = 176

    /**
     * Where the sea stands. High for the reason in the class note, and set a little under the height the
     * ground crosses on average (138) so that rather more of the world is land than sea.
     */
    const val WATERLINE = 132

    // Continents a few hundred blocks across rather than a thousand: at 2.0 a whole render was one
    // hillside, which reads as a slope rather than as a country.
    private const val HORIZONTAL_SCALE = 1.2
    private const val VERTICAL_SCALE = 1.0

    // The band's own ends. Read them together: how far apart decides the relief, and where they sit in the
    // noise's range decides how much of the band is ground rather than sky.
    private const val SOLID_AT_THE_BOTTOM = -0.95
    private const val EMPTY_AT_THE_TOP = 1.05

    private const val LAND_SEED = 0x0_A25_1A2DL
}
