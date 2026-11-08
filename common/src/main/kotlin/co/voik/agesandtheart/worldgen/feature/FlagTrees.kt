package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.BlockPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.TreeFeature
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer
import java.util.function.BiConsumer
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Trees bent by a constant wind, as on Patagonia's coast: the trunk leaning downwind as it rises, and every leaf and
 * branch on the upwind side swept round to the downwind one, so the crown streams from the trunk like a flag.
 *
 * Asked by `TreeFeatureMixin` for every tree, which wraps the setters vanilla hands its trunk and foliage placers, so
 * the tree is grown whole and bent block by block as it is set — its decorations and leaf distances then follow the
 * bent blocks, since vanilla records the positions its setters were given. Answered from what the Age's generator
 * sets around its own decoration ([sweeping]); elsewhere, one read.
 */
object FlagTrees {

    private val windAt = ThreadLocal<((Int, Int) -> Double?)?>()
    private val bending = ThreadLocal<Bend?>()

    /** Runs [decoration] with the trees it grows bent towards the bearing [windAt] gives a column, where it gives one. */
    fun <T> sweeping(windAt: ((Int, Int) -> Double?)?, decoration: () -> T): T {
        if (windAt == null) return decoration()
        val was = this.windAt.get()
        this.windAt.set(windAt)
        try {
            return decoration()
        } finally {
            this.windAt.set(was)
        }
    }

    /** Called as a tree begins to be placed at [origin], so its setters know whether and which way to bend it. */
    @JvmStatic
    fun beginTree(level: WorldGenLevel, origin: BlockPos) {
        val bearing = windAt.get()?.invoke(origin.x, origin.z)
        bending.set(bearing?.let { Bend(level, origin, it) })
    }

    @JvmStatic
    fun endTree() = bending.remove()

    /** [setter] setting each log where the wind has bent it, with a log beside it wherever the lean steps over. */
    @JvmStatic
    fun trunk(setter: BiConsumer<BlockPos, BlockState>): BiConsumer<BlockPos, BlockState> {
        val bend = bending.get() ?: return setter
        return BiConsumer { pos, state ->
            setter.accept(bend.moved(pos, bend.leanAt(pos.y)), state)
            val below = bend.leanAt(pos.y - 1)
            if (below != bend.leanAt(pos.y)) setter.accept(bend.moved(pos, below), state)
        }
    }

    /** [setter] setting each leaf where the wind has swept it, and none where that is inside the ground. */
    @JvmStatic
    fun foliage(setter: FoliagePlacer.FoliageSetter): FoliagePlacer.FoliageSetter {
        val bend = bending.get() ?: return setter
        return object : FoliagePlacer.FoliageSetter {
            override fun set(pos: BlockPos, state: BlockState) {
                val moved = bend.moved(pos, bend.leanAt(pos.y))
                if (bend.hasRoomAt(moved)) setter.set(moved, state)
            }

            override fun isSet(pos: BlockPos): Boolean = setter.isSet(bend.moved(pos, bend.leanAt(pos.y)))
        }
    }

    private class Bend(private val level: WorldGenLevel, private val origin: BlockPos, bearingDegrees: Double) {
        // Snapped to the eight ways a block can step, so a swept block lands on a whole block and a mirrored one on
        // its own reflection.
        private val step = STEPS[Math.floorMod((bearingDegrees / EIGHTH_TURN).roundToInt(), STEPS.size)]
        private val stepX = step.first
        private val stepZ = step.second
        private val stepLength = sqrt((stepX * stepX + stepZ * stepZ).toDouble())

        /** How far downwind a block at this height is carried, in blocks along the wind. */
        fun leanAt(y: Int): Int = floor((y - origin.y).coerceAtLeast(0) * LEAN_PER_BLOCK_OF_HEIGHT + HALF).toInt()

        /** [pos] swept to the downwind side of the trunk and carried [lean] blocks downwind. */
        fun moved(pos: BlockPos, lean: Int): BlockPos {
            val awayX = pos.x - origin.x
            val awayZ = pos.z - origin.z
            val upwind = awayX * stepX + awayZ * stepZ < 0
            // A reflection across the line through the trunk square to the wind; the step's own length squared keeps
            // it whole on a diagonal.
            val along = awayX * stepX + awayZ * stepZ
            val squared = stepX * stepX + stepZ * stepZ
            val reflectedX = if (upwind) awayX - 2 * along * stepX / squared else awayX
            val reflectedZ = if (upwind) awayZ - 2 * along * stepZ / squared else awayZ
            val carriedX = (lean * stepX / stepLength).roundToInt()
            val carriedZ = (lean * stepZ / stepLength).roundToInt()
            return BlockPos(origin.x + reflectedX + carriedX, pos.y, origin.z + reflectedZ + carriedZ)
        }

        fun hasRoomAt(pos: BlockPos): Boolean = TreeFeature.validTreePos(level, pos) || TreeFeature.isAirOrLeaves(level, pos)
    }

    /** About a block downwind for every three it rises: a twelve-block tree leans four. */
    private const val LEAN_PER_BLOCK_OF_HEIGHT = 0.33
    private const val HALF = 0.5
    private const val EIGHTH_TURN = 45.0

    /** The eight steps, from east turning to south, as a bearing turns. */
    private val STEPS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
}
