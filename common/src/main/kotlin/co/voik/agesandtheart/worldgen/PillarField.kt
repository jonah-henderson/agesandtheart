package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Box
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation

/**
 * Colossal rectangular pillars — 64×64 across, rising the full 128 blocks from the sea floor — on a
 * lightly jittered grid, over an ocean at the bottom of the world. One of the archetypes the toolkit
 * was drawn up to reach, and it needs no new machinery: a [Box] template, a [Grid], and an ocean.
 *
 * The pillars are deliberately identical — regularity *is* the aesthetic, and the jitter only breaks the
 * horizon into something less mechanical than perfect ranks. A square footprint is symmetric under
 * quarter turns, so yaw [Variation] would do nothing here.
 */
object PillarField {

    fun world(salt: Long = 0L): TerrainField {
        // Authored around the local origin; the instancer translates each copy into place.
        val pillar = Box(
            minX = -PILLAR_HALF_WIDTH, minY = VerticalWindow.MIN_Y, minZ = -PILLAR_HALF_WIDTH,
            maxX = PILLAR_HALF_WIDTH - 1, maxY = PILLAR_TOP, maxZ = PILLAR_HALF_WIDTH - 1,
        )
        val pillars = Instanced(
            templates = listOf(pillar),
            placement = Grid(spacing = SPACING, jitter = JITTER, density = Density.uniform()),
            variation = Variation.NONE,
            seed = PILLAR_SEED xor salt,
        )
        // A shallow floor so the ocean rests on ground rather than on the bottom of the world.
        val seabed = Slab(lowY = VerticalWindow.MIN_Y, highY = SEABED_TOP)
        return Union(listOf(seabed, pillars))
    }

    // 64 wide: -32..31 inclusive. Centre-to-centre spacing of 96 leaves ~32-block channels between them.
    private const val PILLAR_HALF_WIDTH = 32
    private const val PILLAR_TOP = 166
    private const val SPACING = 96.0
    private const val JITTER = 8.0

    private const val SEABED_TOP = 43
    private const val PILLAR_SEED = 0xC01_1055L
}
