package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks

/**
 * Weathered rock masses hanging over an ocean — a grid of identical ellipsoids, every one of which
 * comes out a different shape.
 *
 * **This preset exists to demonstrate one structural decision, and its whole point is where the noise
 * sits.** The obvious way to write it is to make each mass a template that is *itself* eroded:
 * `Instanced(Intersect(Ellipsoid, Noise3D))`. That produces a field of identical clones, because
 * [Instanced] queries its templates in local coordinates, so every copy samples the same corner of the
 * noise. Hoisting the noise above the instancer instead — `Intersect(Instanced(Ellipsoid), Noise3D)` —
 * leaves it reading world coordinates, so each mass is cut from a different region of one continuous
 * field. All different, and agreeing with one another where two happen to meet.
 *
 * Three things fall out of that arrangement for nothing. The noise costs one evaluation per column no
 * matter how many masses overlap there, where a templated one would cost one per instance. The
 * `Intersect` is ordered so the analytic grid is asked first (see
 * [co.voik.agesandtheart.worldgen.field.TerrainField.samplesPerColumn]), and since most columns land
 * between the masses, most columns never sample noise at all. And the erosion needs no per-instance
 * state whatsoever, which is what the instancing design ruled out from the start.
 *
 * The noise wavelength is chosen against the mass radius on purpose: too broad and each ellipsoid sits
 * inside a single lobe and survives or vanishes whole, too fine and it dissolves into gravel. Roughly a
 * third of the radius is what carves ribs and hollows out of something still recognisably a mass.
 */
object ErodedField {

    fun world(): TerrainField {
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
            seed = LAYOUT_SEED,
        )
        // The erosion, global and world-anchored. Its band need only cover where masses can reach.
        val weathering = Noise3D(
            seed = EROSION_SEED,
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
        val seabed = Slab(lowY = WORLD_FLOOR, highY = SEABED_TOP)
        return Union(listOf(seabed, Intersect(listOf(masses, weathering))))
    }

    fun generator(biomeSource: BiomeSource): FieldChunkGenerator =
        FieldChunkGenerator(
            biomeSource,
            world(),
            AmbientMedium.sea(Blocks.WATER.defaultBlockState(), level = SEA_LEVEL),
            Palette.BARE_ROCK,
        )

    private const val WORLD_FLOOR = -64
    private const val SEABED_TOP = -56
    private const val SEA_LEVEL = -30

    private const val MASS_CENTER_Y = 60
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
