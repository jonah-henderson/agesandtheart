package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Branches run out from a tree's trunk into a crown too wide for it, so no leaf is left decaying. A leaf holds while
 * it is within [FARTHEST_HELD] steps of a log through other leaves, and a colossal crown reaches past that.
 *
 * **Grown as vanilla's big oaks grow theirs**: each leaves the trunk below the leaves it is for and climbs out to
 * them, aimed at the middle of the cluster still unheld rather than at one far leaf, and a block off true. Branches
 * are added until the crown holds or nothing more can be grown. Then every leaf is given its true distance, since a
 * leaf keeps the one it was placed with until a neighbour changes.
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
        val lowestLeaf = tree.filterValues(::isLeaf).keys.minOfOrNull { it.y } ?: return
        // A branch never leaves the trunk below the crown it is for, nor above the trunk's top.
        val lowestStart = maxOf(bottom, lowestLeaf - BELOW_THE_CROWN).coerceAtMost(top)
        fun outFromTheTrunk(at: BlockPos): Double {
            val acrossX = at.x - middleX
            val acrossZ = at.z - middleZ
            return sqrt(acrossX * acrossX + acrossZ * acrossZ)
        }
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
            val unheld = tree.keys.filter(::needsABranch)
            val farthest = unheld.maxByOrNull(::outFromTheMiddle) ?: return settleDistances(tree, isLog)
            val aim = nudged(middleOf(unheld.filter { it.distManhattan(farthest) <= CLUSTER_STEPS }))
            val climb = (outFromTheTrunk(aim) * RISE_PER_BLOCK_OUT).roundToInt()
            val from = BlockPos(middleX.roundToInt(), (aim.y - climb).coerceIn(lowestStart, top), middleZ.roundToInt())
            if (branch(tree, from, aim, log, mayGrowAt, isLog)) grown++ else unreachable += farthest
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

    private fun middleOf(cluster: List<BlockPos>): BlockPos = BlockPos(
        cluster.map { it.x }.average().roundToInt(),
        cluster.map { it.y }.average().roundToInt(),
        cluster.map { it.z }.average().roundToInt(),
    )

    /** [at] moved a block or none each way across, drawn from where it is, so a crown's branches are not symmetrical. */
    private fun nudged(at: BlockPos): BlockPos {
        val drawn = Mth.getSeed(at)
        val acrossX = Math.floorMod(drawn shr NUDGE_X_BITS, NUDGES) - 1
        val acrossZ = Math.floorMod(drawn shr NUDGE_Z_BITS, NUDGES) - 1
        return at.offset(acrossX.toInt(), 0, acrossZ.toInt())
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

    /** How many steps short of where it is aimed a branch stops, so the leaves round its end are held. */
    private const val STOPS_SHORT_BY = 2

    /** How far round the farthest unheld leaf the cluster a branch is aimed at reaches, in steps. */
    private const val CLUSTER_STEPS = 4

    /** How much a branch climbs for each block it reaches out from the trunk: about thirty degrees. */
    private const val RISE_PER_BLOCK_OUT = 0.6

    /** How far below its crown a branch may leave the trunk. */
    private const val BELOW_THE_CROWN = 2

    /** A nudge is one of three, each way across, drawn from separate bits of the position's seed. */
    private const val NUDGES = 3L
    private const val NUDGE_X_BITS = 8
    private const val NUDGE_Z_BITS = 24

    /** Enough for the widest crown a colossal tree grows, with room to spare. */
    private const val MOST_BRANCHES = 64

    /** And how many leaves are tried before giving up, those no branch could reach among them. */
    private const val MOST_TRIES = 256
}
