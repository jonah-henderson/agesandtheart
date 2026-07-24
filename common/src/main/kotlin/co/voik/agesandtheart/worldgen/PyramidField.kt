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
import net.minecraft.world.level.biome.BiomeSource

/**
 * Instancing presets: a handful of differently-sized pyramids (heights 8/16/24/32) strewn on a flat
 * plain. Two layouts exercise the [Placement] seam — a density-gradient [Grid] (packed at the origin,
 * thinning outward) and concentric [Radial] rings. Deliberate, regular geography; any detailing is a
 * later pass, not the instancer's.
 */
object PyramidField {

    /** Density-gradient grid: pyramids crowd the origin and thin out with distance. */
    fun generator(biomeSource: BiomeSource): FieldChunkGenerator =
        world(Grid(spacing = 40.0, jitter = 10.0, density = Density.radial(atOrigin = 1.0, atEdge = 0.1, falloffRadius = 420.0)))
            .let { FieldChunkGenerator(biomeSource, it, AmbientMedium.VOID) }

    /** Concentric rings of pyramids around an empty centre. */
    fun ringsGenerator(biomeSource: BiomeSource): FieldChunkGenerator =
        world(Radial(ringSpacing = 64.0, arcSpacing = 56.0, jitter = 8.0, density = Density.uniform()))
            .let { FieldChunkGenerator(biomeSource, it, AmbientMedium.VOID) }

    private fun world(placement: Placement): TerrainField {
        val pyramids = PYRAMID_HEIGHTS.map { height ->
            // Authored around the local origin; the instancer translates each copy into place.
            Pyramid(centerX = 0, centerZ = 0, baseY = GROUND_TOP + 1, height = height, baseHalfWidth = height)
        }
        val scattered = Instanced(pyramids, placement, seed = SCATTER_SEED)
        val ground = Slab(lowY = FLOOR_Y, highY = GROUND_TOP)
        return Union(listOf(ground, scattered))
    }

    private val PYRAMID_HEIGHTS = listOf(8, 16, 24, 32)
    private const val FLOOR_Y = -64
    private const val GROUND_TOP = 63
    private const val SCATTER_SEED = 0x5EED_C0DEL
}
