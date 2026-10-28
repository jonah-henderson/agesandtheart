package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Box
import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import co.voik.agesandtheart.worldgen.field.Wear
import kotlin.math.roundToInt

/**
 * Rectangular pillars — at `colossal`, 64×64 across and rising the full 128 blocks from the sea floor — on a
 * lightly jittered grid, over an ocean at the bottom of the world. One of the archetypes the toolkit
 * was drawn up to reach, and it needs no new machinery: a [Box] template, a [Grid], and an ocean.
 *
 * The pillars are deliberately alike — regularity *is* the aesthetic, and the jitter only breaks the
 * horizon into something less mechanical than perfect ranks; what differs is how worn each is, by place. A square
 * footprint is symmetric under quarter turns, so yaw [Variation] would do nothing here.
 */
object PillarField {

    /**
     * [scale] is [SizeScale]'s factor, and the pillars as tuned are `colossal`. Width, spacing and height
     * over the sea move together; the height is measured from the water rather than the seabed, so the
     * smallest are sea stacks rather than drowned stubs, and every size still stands on the floor.
     *
     * With a [wear], each pillar is as worn as its place: sheer where little is worn, its top fallen back to shoulders
     * at a full wear, and down to a stump where most is.
     */
    fun world(salt: Long = 0L, scale: Double = SizeScale.ORDINARY, wear: Wear? = null): TerrainField {
        val share = scale / SizeScale.COLOSSAL
        val halfWidth = (PILLAR_HALF_WIDTH * share).roundToInt().coerceAtLeast(1)
        val rise = (PILLAR_TOP - SEA_LEVEL) * share
        val pillar = PillarShape(halfWidth, SEA_LEVEL + rise.roundToInt().coerceAtLeast(1))
        val templates = if (wear == null) {
            listOf(pillar.sheer())
        } else {
            listOf(
                pillar.sheer(),
                pillar.shouldered(shouldersY = SEA_LEVEL + rise * WORN_SHOULDERS, topY = SEA_LEVEL + rise * WORN_TOP, crown = WORN_CROWN),
                pillar.shouldered(shouldersY = SEA_LEVEL.toDouble(), topY = SEA_LEVEL + rise * STUMP_TOP, crown = STUMP_CROWN),
            )
        }
        val pillars = Instanced(
            templates = templates,
            placement = Grid(spacing = SPACING * share, jitter = JITTER * share, density = Density.uniform()),
            variation = Variation.NONE,
            seed = PILLAR_SEED xor salt,
            wornBy = wear,
        )
        // A shallow floor so the ocean rests on ground rather than on the bottom of the world.
        val seabed = Slab(lowY = VerticalWindow.MIN_Y, highY = SEABED_TOP)
        return Union(listOf(seabed, pillars))
    }

    /** One pillar's footprint and height, authored around the local origin; the instancer translates each copy into place. */
    private data class PillarShape(val halfWidth: Int, val topY: Int) {
        fun sheer(): TerrainField = upTo(topY)

        /**
         * Sheer to [shouldersY], then narrowing to a flat crown [crown] of the half width across at [topY]: the corners
         * go first, then the faces.
         */
        fun shouldered(shouldersY: Double, topY: Double, crown: Double): TerrainField {
            val crownRadius = halfWidth * crown
            val shoulderRadius = halfWidth * CORNER_TO_FACE
            val narrowingPerBlock = (shoulderRadius - crownRadius) / (topY - shouldersY)
            val tipY = shouldersY + shoulderRadius / narrowingPerBlock
            val shoulders = Cone(baseX = 0, baseZ = 0, baseRadius = shoulderRadius, baseY = shouldersY.roundToInt(), tipY = tipY.roundToInt())
            return Union(listOf(upTo(shouldersY.roundToInt() - 1), Intersect(listOf(upTo(topY.roundToInt()), shoulders))))
        }

        private fun upTo(y: Int) = Box(
            minX = -halfWidth, minY = VerticalWindow.MIN_Y, minZ = -halfWidth,
            maxX = halfWidth - 1, maxY = y, maxZ = halfWidth - 1,
        )
    }

    // 64 wide: -32..31 inclusive. Centre-to-centre spacing of 96 leaves ~32-block channels between them.
    private const val PILLAR_HALF_WIDTH = 32
    private const val PILLAR_TOP = 166
    private const val SPACING = 96.0
    private const val JITTER = 8.0

    /**
     * A worn pillar's shoulders and flat top, and a stump's, as shares of the sheer one's rise over the sea; and how
     * wide each crown is against the half width.
     */
    private const val WORN_SHOULDERS = 0.55
    private const val WORN_TOP = 0.85
    private const val WORN_CROWN = 0.6
    private const val STUMP_TOP = 0.3
    private const val STUMP_CROWN = 0.5

    /** How far a square's corner stands from its middle against its faces, so shoulders begin at the corners. */
    private const val CORNER_TO_FACE = 1.45

    private const val SEA_LEVEL = 63
    private const val SEABED_TOP = 43
    private const val PILLAR_SEED = 0xC01_1055L
}
