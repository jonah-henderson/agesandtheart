package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration

/**
 * Lava tubes, seated in the floor of a volcanic caldera (design §7.1.2).
 *
 * **The caldera is found by looking rather than by being told where it is.** A crater is a relationship
 * between a column and the ground around it — low, with higher ground on every side, and high above the
 * world — which is a thing the finished terrain knows and a site list would only approximate. It also
 * survives two cones blending into one massif, where a list of centres would put vents on a shoulder that
 * the blend had buried.
 *
 * This runs only in an Age whose recipe asked for volcanoes, so any high basin it finds is one of ours.
 */
object VolcanoVents : Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val origin = context.origin()
        val floor = calderaFloorIn(level, origin) ?: return false
        return seat(level, floor)
    }

    /**
     * The lowest column in this chunk that sits in a bowl high above the world, or null.
     *
     * The lowest, so a caldera crossing two chunks seats its tubes once, in the deeper half, rather than
     * growing a set in each.
     */
    private fun calderaFloorIn(level: WorldGenLevel, origin: BlockPos): BlockPos? {
        var best: BlockPos? = null
        for (offsetX in 0..<CHUNK step STRIDE) {
            for (offsetZ in 0..<CHUNK step STRIDE) {
                val x = origin.x + offsetX
                val z = origin.z + offsetZ
                val y = surfaceAt(level, x, z)
                if (y < HIGH_ENOUGH) continue
                if (!ringedByHigherGround(level, x, z, y)) continue
                if (best == null || y < best.y) best = BlockPos(x, y, z)
            }
        }
        return best
    }

    private fun surfaceAt(level: WorldGenLevel, x: Int, z: Int): Int =
        level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1

    /**
     * Whether every bearing out of this column climbs — which is what a crater is and a hillside is not.
     *
     * All four rather than most: a saddle between two peaks climbs on two sides and falls away on the
     * others, and seating a vent in one would put lava down a mountainside.
     */
    private fun ringedByHigherGround(level: WorldGenLevel, x: Int, z: Int, floor: Int): Boolean =
        RIM_BEARINGS.all { (stepX, stepZ) ->
            surfaceAt(level, x + stepX * RIM_REACH, z + stepZ * RIM_REACH) >= floor + RIM_CLIMB
        }

    /**
     * A short run of tubes in the floor, which is what makes a mass rather than a single vent.
     *
     * Sunk into the rock rather than laid on it: a tube standing proud of the floor would be plugged by
     * the first lava it welled, and its mass would be one block tall.
     */
    private fun seat(level: WorldGenLevel, floor: BlockPos): Boolean {
        var seated = false
        for (depth in 0..<MASS_DEPTH) {
            for ((offsetX, offsetZ) in MASS_SHAPE) {
                val at = floor.offset(offsetX, -depth, offsetZ)
                if (!level.getBlockState(at).isSolidRender) continue
                level.setBlock(at, AgeContent.LAVA_TUBE_BLOCK.defaultBlockState(), UPDATE_NONE)
                seated = true
            }
        }
        return seated
    }

    /** A plus, so the run is connected six-ways and reads as one vent rather than as scattered blocks. */
    private val MASS_SHAPE = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private val RIM_BEARINGS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private const val MASS_DEPTH = 3

    /** Out past the caldera floor but inside the rim, for a crater of the size the cones are cut to. */
    private const val RIM_REACH = 24

    /** Enough of a climb that a gentle dip in a hillside is not mistaken for a crater. */
    private const val RIM_CLIMB = 8

    /**
     * Above anything ordinary ground reaches, so only a summit qualifies.
     *
     * **It cuts off the smallest cones deliberately.** The scatter varies a cone's size, and one scaled far
     * enough down never clears the terrain it is laid over — which makes it not a small volcano but an
     * invisible one, and an invisible volcano should not be quietly wiring lava into a hillside.
     */
    private const val HIGH_ENOUGH = 100

    private const val CHUNK = 16

    /** Coarse: a caldera is tens of blocks across, so every fourth column finds it. */
    private const val STRIDE = 4

    private const val UPDATE_NONE = 2
}
