package co.voik.agesandtheart.worldgen

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

/**
 * Instancing presets: a handful of differently-sized pyramids (heights 8/16/24/32) strewn on a flat
 * plain. Three worlds exercise the placement seam — a density-gradient [Grid] (packed at the origin,
 * thinning outward), concentric [Radial] rings, and a gradient grid whose copies are turned and
 * resized by a [Variation]. Deliberate, regular geography; any detailing is a later pass, not the
 * instancer's.
 */
object PyramidField {

    private fun gradientGrid() = Grid(
        spacing = GRID_SPACING,
        jitter = GRID_JITTER,
        density = Density.radial(
            atOrigin = DENSITY_AT_ORIGIN,
            atEdge = DENSITY_AT_EDGE,
            falloffRadius = DENSITY_FALLOFF_RADIUS,
        ),
    )

    private fun rings() = Radial(
        ringSpacing = RING_SPACING,
        arcSpacing = ARC_SPACING,
        jitter = RING_JITTER,
        density = Density.uniform(),
    )

    /**
     * The pyramids, arranged as asked. The three arrangements were three separate presets before aspects
     * existed; they are one preset and one enumerated question now, which is what §3.2 is for.
     */
    fun world(arrangement: String, salt: Long = 0L): TerrainField = when (arrangement) {
        "rings" -> world(rings(), Variation.NONE, salt)
        "varied" -> world(gradientGrid(), variedPoses(), salt)
        else -> world(gradientGrid(), Variation.NONE, salt)
    }

    private fun variedPoses() = Variation(
        yawSteps = YAW_STEPS,
        minScale = MIN_SCALE,
        maxScale = MAX_SCALE,
        scaleSteps = SCALE_STEPS,
        pivotY = GROUND_TOP + 1,
    )

    private fun world(placement: Placement, variation: Variation, salt: Long): TerrainField {
        val pyramids = PYRAMID_HEIGHTS.map { height ->
            // Authored around the local origin; the instancer translates each copy into place.
            Pyramid(centerX = 0, centerZ = 0, baseY = GROUND_TOP + 1, height = height, baseHalfWidth = height)
        }
        val scattered = Instanced(pyramids, placement, variation, seed = SCATTER_SEED xor salt)
        val ground = Slab(lowY = FLOOR_Y, highY = GROUND_TOP)
        return Union(listOf(ground, scattered))
    }

    private val PYRAMID_HEIGHTS = listOf(8, 16, 24, 32)
    private const val FLOOR_Y = -64
    private const val GROUND_TOP = 63
    private const val SCATTER_SEED = 0x5EED_C0DEL

    // Wide enough that the largest pyramid still stands clear of its neighbours away from the origin,
    // with enough jitter to break the lattice without disguising that it is one.
    private const val GRID_SPACING = 40.0
    private const val GRID_JITTER = 10.0

    // Packed at the origin and thinning to near-empty by the falloff radius — the gradient is the
    // whole point of this world, so it runs the full range rather than a subtle one.
    private const val DENSITY_AT_ORIGIN = 1.0
    private const val DENSITY_AT_EDGE = 0.1
    private const val DENSITY_FALLOFF_RADIUS = 420.0

    // Rings far enough apart to walk between, and spaced along their arc so a ring reads as a row of
    // separate pyramids rather than a wall.
    private const val RING_SPACING = 64.0
    private const val ARC_SPACING = 56.0
    private const val RING_JITTER = 8.0

    // Enough orientations to read as freely turned, but a fixed set so the yaw table stays tiny.
    private const val YAW_STEPS = 16
    // Kept modest: the gradient grid already overlaps near the origin, and a large upper bound there
    // merges neighbours into one mass rather than reading as distinct, differently-sized pyramids.
    private const val MIN_SCALE = 0.6
    private const val MAX_SCALE = 1.3
    private const val SCALE_STEPS = 4
}
