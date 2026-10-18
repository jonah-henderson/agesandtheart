package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Branches run out from a tree's trunk into a crown too wide for it, so no leaf is left decaying. A leaf holds while
 * it is within [FARTHEST_HELD] steps of a log through other leaves, and a colossal crown reaches past that.
 *
 * A branch goes from the trunk towards the farthest leaf left unheld, stopping a little short of it so the leaves
 * round its end are held too, and branches are added until the crown holds or nothing more can be grown. Then every
 * leaf is given its true distance, since a leaf keeps the one it was placed with until a neighbour changes.
 */
object Branches {

    /**
     * Grows branches into [tree] — every block of it by where it stands — only where [mayGrowAt] allows. [isLog]
     * says what holds a leaf up, the log tag unless a caller names its logs outright, as one offline must.
     */
    fun grownInto(
        tree: MutableMap<BlockPos, BlockState>,
        mayGrowAt: (BlockPos) -> Boolean,
        isLog: (BlockState) -> Boolean = ::isLog,
    ) {
        val trunk = tree.filterValues(isLog).keys
        val log = trunk.minByOrNull { it.y }?.let(tree::getValue) ?: return
        val middleX = trunk.map { it.x }.average()
        val middleZ = trunk.map { it.z }.average()
        val top = trunk.maxOf { it.y }
        val bottom = trunk.minOf { it.y }
        /** How far [at] is from the nearest point of the trunk's middle, squared: out from it, or up past its top. */
        fun outFromTheMiddle(at: BlockPos): Double {
            val acrossX = at.x - middleX
            val acrossZ = at.z - middleZ
            val pastTheEnds = (at.y - at.y.coerceIn(bottom, top)).toDouble()
            return acrossX * acrossX + acrossZ * acrossZ + pastTheEnds * pastTheEnds
        }
        // A leaf no branch could be grown towards is not asked for again.
        val unreachable = HashSet<BlockPos>()
        var grown = 0
        repeat(MOST_TRIES) {
            if (grown >= MOST_BRANCHES) return settleDistances(tree, isLog)
            val held = distancesFromLogs(tree, isLog)
            fun needsABranch(at: BlockPos) = isLeaf(tree.getValue(at)) && at !in held && at !in unreachable
            val farthest = tree.keys.filter(::needsABranch).maxByOrNull(::outFromTheMiddle) ?: return settleDistances(tree, isLog)
            val from = BlockPos(middleX.roundToInt(), minOf(farthest.y, top), middleZ.roundToInt())
            if (branch(tree, from, farthest, log, mayGrowAt, isLog)) grown++ else unreachable += farthest
        }
        settleDistances(tree, isLog)
    }

    /** Lays logs from [from] towards [towards], stopping short of it or where nothing may grow. Answers whether it laid any. */
    private fun branch(
        tree: MutableMap<BlockPos, BlockState>,
        from: BlockPos,
        towards: BlockPos,
        log: BlockState,
        mayGrowAt: (BlockPos) -> Boolean,
        isLog: (BlockState) -> Boolean,
    ): Boolean {
        val across = towards.subtract(from)
        val steps = max(abs(across.x), max(abs(across.y), abs(across.z)))
        val laid = logAlong(log, across)
        var grew = false
        for (step in 1..steps) {
            val at = from.offset(
                (across.x * step.toDouble() / steps).roundToInt(),
                (across.y * step.toDouble() / steps).roundToInt(),
                (across.z * step.toDouble() / steps).roundToInt(),
            )
            if (stepsBetween(at, towards) <= STOPS_SHORT_BY) break
            val standing = tree[at]
            if (standing != null && isLog(standing)) continue
            if (!mayGrowAt(at)) break
            tree[at] = laid
            grew = true
        }
        return grew
    }

    /** How far every leaf of [tree] is from a log, stepping through leaves, for the leaves within [FARTHEST_HELD]. */
    fun distancesFromLogs(tree: Map<BlockPos, BlockState>, isLog: (BlockState) -> Boolean = ::isLog): Map<BlockPos, Int> {
        val reached = HashMap<BlockPos, Int>()
        var frontier = tree.filterValues(isLog).keys.toList()
        for (distance in 1..FARTHEST_HELD) {
            val next = mutableListOf<BlockPos>()
            for (at in frontier) for (direction in Direction.entries) {
                val beside = at.relative(direction)
                val isAnUnreachedLeaf = tree[beside]?.let(::isLeaf) == true && beside !in reached
                if (!isAnUnreachedLeaf) continue
                reached[beside] = distance
                next += beside
            }
            frontier = next
        }
        return reached
    }

    private fun settleDistances(tree: MutableMap<BlockPos, BlockState>, isLog: (BlockState) -> Boolean) {
        val held = distancesFromLogs(tree, isLog)
        for ((at, state) in tree.entries.toList()) {
            if (!isLeaf(state)) continue
            tree[at] = state.setValue(LeavesBlock.DISTANCE, held[at] ?: LeavesBlock.DECAY_DISTANCE)
        }
    }

    private fun logAlong(log: BlockState, across: BlockPos): BlockState {
        if (!log.hasProperty(RotatedPillarBlock.AXIS)) return log
        val axis = when {
            abs(across.y) >= abs(across.x) && abs(across.y) >= abs(across.z) -> Direction.Axis.Y
            abs(across.x) >= abs(across.z) -> Direction.Axis.X
            else -> Direction.Axis.Z
        }
        return log.setValue(RotatedPillarBlock.AXIS, axis)
    }

    /** How many steps a leaf's distance counts between [first] and [second]: one face at a time. */
    private fun stepsBetween(first: BlockPos, second: BlockPos): Int = first.distManhattan(second)

    fun isLog(state: BlockState): Boolean = state.`is`(BlockTags.LOGS)

    fun isLeaf(state: BlockState): Boolean = state.hasProperty(LeavesBlock.DISTANCE)

    /** The farthest a leaf may be from a log and hold: one short of where vanilla lets it decay. */
    const val FARTHEST_HELD = LeavesBlock.DECAY_DISTANCE - 1

    /** How many steps short of the leaf it reaches for a branch stops, so the leaves round its end are held. */
    private const val STOPS_SHORT_BY = 3

    /** Enough for the widest crown a colossal tree grows, with room to spare. */
    private const val MOST_BRANCHES = 64

    /** And how many leaves are tried before giving up, those no branch could reach among them. */
    private const val MOST_TRIES = 256
}
