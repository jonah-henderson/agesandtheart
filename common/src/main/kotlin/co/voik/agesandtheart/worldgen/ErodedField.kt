package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation

/**
 * Weathered rock masses hanging over an ocean — a grid of identical ellipsoids, every one of which
 * comes out a different shape.
 *
 * **The point is where the noise sits.** `Instanced(Intersect(Ellipsoid, Noise3D))` gives identical
 * clones, [Instanced] querying its templates in local coordinates so every copy samples the same corner.
 * Hoisting it — `Intersect(Instanced(Ellipsoid), Noise3D)` — leaves the noise reading world coordinates,
 * so each mass is cut from a different region and neighbours agree where they meet. It also costs one
 * evaluation per column however many masses overlap, and the analytic grid is asked first so most
 * columns never sample noise at all.
 *
 * The noise wavelength is chosen against the mass radius: too broad and each ellipsoid sits inside one
 * lobe and survives or vanishes whole, too fine and it dissolves into gravel.
 */
object ErodedField {

    /**
     * [scale] is [SizeScale]'s factor. The masses as tuned are the `large` step, and they are resized whole
     * about the sea — radius, spacing, erosion and height above the water together — while the seabed
     * stays where it is.
     */
    fun world(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField {
        val mass = Ellipsoid(
            centerX = 0,
            centerY = MASS_CENTER_Y,
            centerZ = 0,
            radiusXZ = MASS_RADIUS_XZ,
            radiusY = MASS_RADIUS_Y,
        )
        val masses = Instanced(
            templates = listOf(mass),
            placement = Grid(spacing = SPACING, jitter = JITTER, density = Density.uniform()),
            variation = Variation.NONE,
            seed = LAYOUT_SEED xor salt,
        )
        // The erosion, global and world-anchored. Its band need only cover where masses can reach.
        val weathering = Noise3D(
            seed = EROSION_SEED xor salt,
            firstOctave = -5,
            amplitudes = listOf(1.0, 0.5, 0.25),
            scaleX = EROSION_SCALE,
            scaleY = EROSION_SCALE,
            scaleZ = EROSION_SCALE,
            character = NoiseCharacter.PLAIN,
            // Below zero, so a little over half the volume survives: high enough to bite visibly, low
            // enough to leave each mass whole rather than shattering it into fragments.
            threshold = EROSION_THRESHOLD,
            lowY = MASS_CENTER_Y - MASS_RADIUS_Y.toInt() - 1,
            highY = MASS_CENTER_Y + MASS_RADIUS_Y.toInt() + 1,
        )
        val seabed = Slab(lowY = VerticalWindow.MIN_Y, highY = SEABED_TOP)
        val weathered = Intersect(listOf(masses, weathering)).resized(scale / TUNED_SIZE, SEA_LEVEL)
        return Union(listOf(seabed, weathered))
    }

    /** The size the constants below were walked at: `large`. */
    private const val TUNED_SIZE = 2.0

    private const val SEA_LEVEL = 63

    private const val SEABED_TOP = 37

    private const val MASS_CENTER_Y = 153
    private const val MASS_RADIUS_XZ = 30.0
    private const val MASS_RADIUS_Y = 22.0
    private const val SPACING = 96.0
    private const val JITTER = 10.0

    // About a third of the mass radius, so erosion cuts ribs rather than swallowing or sanding it.
    private const val EROSION_SCALE = 0.55
    private const val EROSION_THRESHOLD = -0.12

    private const val LAYOUT_SEED = 0xE20_DEDL
    private const val EROSION_SEED = 0xC0A5_7A1L
}
