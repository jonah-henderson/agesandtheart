package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.Pyramid
import co.voik.agesandtheart.worldgen.field.Radial
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import net.minecraft.world.level.biome.BiomeSource

/**
 * Instancing presets: a handful of differently-sized pyramids (heights 8/16/24/32) strewn on a flat
 * plain. Three worlds exercise the placement seam — a density-gradient [Grid] (packed at the origin,
 * thinning outward), concentric [Radial] rings, and a gradient grid whose copies are turned and
 * resized by a [Variation]. Deliberate, regular geography; any detailing is a later pass, not the
 * instancer's.
 */
object PyramidField {

    /** Density-gradient grid: pyramids crowd the origin and thin out with distance. */
    fun generator(biomeSource: BiomeSource): FieldChunkGenerator =
        world(gradientGrid(), Variation.NONE).let { FieldChunkGenerator(biomeSource, it, AmbientMedium.VOID) }

    /** Concentric rings of pyramids around an empty centre. */
    fun ringsGenerator(biomeSource: BiomeSource): FieldChunkGenerator =
        world(Radial(ringSpacing = 64.0, arcSpacing = 56.0, jitter = 8.0, density = Density.uniform()), Variation.NONE)
            .let { FieldChunkGenerator(biomeSource, it, AmbientMedium.VOID) }

    /**
     * The same gradient grid, but every copy takes one of [YAW_STEPS] orientations and a size between
     * [MIN_SCALE] and [MAX_SCALE] — the pose half of instancing variety, side by side with the plain
     * [generator] for comparison.
     */
    fun variedGenerator(biomeSource: BiomeSource): FieldChunkGenerator =
        world(
            gradientGrid(),
            Variation(yawSteps = YAW_STEPS, minScale = MIN_SCALE, maxScale = MAX_SCALE, pivotY = GROUND_TOP + 1),
        ).let { FieldChunkGenerator(biomeSource, it, AmbientMedium.VOID) }

    private fun gradientGrid() =
        Grid(spacing = 40.0, jitter = 10.0, density = Density.radial(atOrigin = 1.0, atEdge = 0.1, falloffRadius = 420.0))

    private fun world(placement: Placement, variation: Variation): TerrainField {
        val pyramids = PYRAMID_HEIGHTS.map { height ->
            // Authored around the local origin; the instancer translates each copy into place.
            Pyramid(centerX = 0, centerZ = 0, baseY = GROUND_TOP + 1, height = height, baseHalfWidth = height)
        }
        val scattered = Instanced(pyramids, placement, variation, seed = SCATTER_SEED)
        val ground = Slab(lowY = FLOOR_Y, highY = GROUND_TOP)
        return Union(listOf(ground, scattered))
    }

    private val PYRAMID_HEIGHTS = listOf(8, 16, 24, 32)
    private const val FLOOR_Y = -64
    private const val GROUND_TOP = 63
    private const val SCATTER_SEED = 0x5EED_C0DEL

    // Enough orientations to read as freely turned, but a fixed set so the yaw table stays tiny.
    private const val YAW_STEPS = 16
    // Kept modest: the gradient grid already overlaps near the origin, and a large upper bound there
    // merges neighbours into one mass rather than reading as distinct, differently-sized pyramids.
    private const val MIN_SCALE = 0.6
    private const val MAX_SCALE = 1.3
}
