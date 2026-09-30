package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where every part of one paper tree goes: a trunk at a curving, irregular slant, broad flat terraces of
 * leaves at intervals up it, and roots arching out and down from its foot into the water.
 *
 * **A pure function of the heart root's position and a seed**, so the root can always work out its own tree
 * again — to wither it from the outside in, to grow back what was felled, to know which roots are its own —
 * without anything but the seed being stored. The form is the tropical almond's pagoda of tiers, leant.
 */
data class PaperTreeShape(
    /** Every log, in the order it grows: the trunk from the root up, each terrace's branches after it. */
    val logs: List<Log>,
    val terraces: List<Terrace>,
    /** The roots that spread from the heart, which are what feel the water. */
    val roots: List<BlockPos>,
) {
    /** One log, and the index of the log it grows from, or [FROM_THE_ROOT]. */
    data class Log(val at: BlockPos, val axis: Direction.Axis, val grownFrom: Int)

    /** One tier: the logs that carry it and its leaves, each with its distance from the nearest log. */
    data class Terrace(val logs: List<Int>, val leaves: Map<BlockPos, Int>)

    val leaves: Map<BlockPos, Int> = terraces.fold(emptyMap()) { all, terrace -> all + terrace.leaves }

    companion object {
        /** Where the trunk's first log stands on nothing of the tree's but the heart itself. */
        const val FROM_THE_ROOT = -1

        /** A leaf's distance before [withDistances] has counted it. */
        private const val UNKNOWN_DISTANCE = 0

        fun grownFrom(heart: BlockPos, seed: Long): PaperTreeShape {
            val random = RandomSource.create(seed)
            val logs = mutableListOf<Log>()
            val trunk = trunk(heart, random, logs)
            val terraceCount = if (trunk.size >= THREE_TERRACES_FROM) THREE_TERRACES else TWO_TERRACES
            val hubs = hubsFor(trunk, terraceCount, random)
            val placedLogs = logs.withIndex().associate { (index, log) -> log.at to index }.toMutableMap()
            val radii = hubs.indices.map { tier -> radiusOf(tier, terraceCount, random) }
            val terraceLogs = hubs.mapIndexed { tier, hub -> branches(logs, hub, radii[tier], random, placedLogs) }
            val terraces = hubs.mapIndexed { tier, hub ->
                val canopy = canopy(logs[hub].at, radii[tier], random, placedLogs.keys)
                Terrace(listOf(hub) + terraceLogs[tier], canopy.associateWith { UNKNOWN_DISTANCE })
            }
            return PaperTreeShape(logs, withDistances(terraces, placedLogs.keys), roots(heart, random, placedLogs.keys))
        }

        /**
         * The trunk, one log a level, leaning further the higher it goes and wandering a little sideways as
         * it does. A step sideways is a log laid on its side at the level below, so every log touches the one
         * before it by a face — which is what leaves count their distance through, and what reads as a slant
         * rather than a stack of offset posts.
         */
        private fun trunk(heart: BlockPos, random: RandomSource, logs: MutableList<Log>): List<Int> {
            val height = random.nextIntBetweenInclusive(SHORTEST, TALLEST)
            val heading = (random.nextFloat() * Mth.TWO_PI).toDouble()
            val lean = LEAST_LEAN + random.nextFloat() * (MOST_LEAN - LEAST_LEAN)
            val wander = LEAST_WANDER + random.nextFloat() * (MOST_WANDER - LEAST_WANDER)
            val wanderPhase = random.nextFloat() * Mth.TWO_PI
            val wanderTurns = 1 + random.nextInt(2)
            val trunk = mutableListOf<Int>()
            var at = heart
            for (level in 1..height) {
                val rise = level.toDouble() / height
                val along = lean * rise.pow(CURVE)
                val across = wander * (sin(PI * wanderTurns * rise + wanderPhase) - sin(wanderPhase.toDouble()))
                val targetX = heart.x + (cos(heading) * along - sin(heading) * across).roundToInt()
                val targetZ = heart.z + (sin(heading) * along + cos(heading) * across).roundToInt()
                // The first log stands straight on the heart, so nothing is laid sideways into the ground.
                val canStep = level > 1
                val stepX = if (canStep) (targetX - at.x).sign else 0
                val stepZ = if (canStep) (targetZ - at.z).sign else 0
                if (stepX != 0) {
                    at = at.offset(stepX, 0, 0)
                    logs += Log(at, Direction.Axis.X, logs.lastIndex)
                }
                if (stepZ != 0) {
                    at = at.offset(0, 0, stepZ)
                    logs += Log(at, Direction.Axis.Z, logs.lastIndex)
                }
                at = at.above()
                logs += Log(at, Direction.Axis.Y, logs.lastIndex)
                trunk += logs.lastIndex
            }
            return trunk
        }

        /** The trunk logs each terrace grows from: spaced up the trunk, the last at its very top. */
        private fun hubsFor(trunk: List<Int>, count: Int, random: RandomSource): List<Int> {
            val heights = if (count == THREE_TERRACES) THREE_TIER_HEIGHTS else TWO_TIER_HEIGHTS
            return heights.mapIndexed { tier, share ->
                val isTheTop = tier == heights.lastIndex
                val jitter = if (isTheTop) 0 else random.nextIntBetweenInclusive(-1, 1)
                val index = ((trunk.size - 1) * share).roundToInt() + jitter
                trunk[index.coerceIn(0, trunk.lastIndex)]
            }
        }

        /** Broadest at the bottom and narrowing upward, as a pagoda does. */
        private fun radiusOf(tier: Int, count: Int, random: RandomSource): Int {
            val fromTheTop = count - 1 - tier
            return TOP_RADIUS + fromTheTop + random.nextInt(2)
        }

        /**
         * Branches radiating level from a terrace's hub, far enough out that the leaves at its rim are held.
         * Each steps one axis at a time, so a diagonal branch is a zigzag of logs that touch face to face.
         */
        private fun branches(
            logs: MutableList<Log>,
            hub: Int,
            radius: Int,
            random: RandomSource,
            placed: MutableMap<BlockPos, Int>,
        ): List<Int> {
            val count = if (radius >= WIDE_TERRACE) MANY_BRANCHES else FEW_BRANCHES
            val start = random.nextFloat() * Mth.TWO_PI
            val reach = (radius - BRANCH_SHORT_OF_THE_RIM).coerceAtLeast(1)
            val grown = mutableListOf<Int>()
            for (branch in 0..<count) {
                val angle = start + branch * Mth.TWO_PI / count + (random.nextFloat() - 0.5f) * BRANCH_SPREAD
                var at = logs[hub].at
                var from = hub
                for (step in 1..reach) {
                    val targetX = logs[hub].at.x + (cos(angle.toDouble()) * step).roundToInt()
                    val targetZ = logs[hub].at.z + (sin(angle.toDouble()) * step).roundToInt()
                    val moves = listOf(
                        Direction.Axis.X to (targetX - at.x).sign,
                        Direction.Axis.Z to (targetZ - at.z).sign,
                    )
                    for ((axis, sign) in moves) {
                        if (sign == 0) continue
                        at = if (axis == Direction.Axis.X) at.offset(sign, 0, 0) else at.offset(0, 0, sign)
                        // Onto a log already there — the trunk, or another branch — it grows on from that one.
                        val already = placed[at]
                        if (already != null) {
                            from = already
                            continue
                        }
                        logs += Log(at, axis, from)
                        from = logs.lastIndex
                        placed[at] = from
                        grown += from
                    }
                }
            }
            return grown
        }

        /**
         * One terrace's leaves: a broad plate a level above its branches with a ragged rim, a sparse fringe
         * among the branches beneath it, and a low hump over the middle — broad and flat, a tier rather than
         * a crown.
         */
        private fun canopy(hub: BlockPos, radius: Int, random: RandomSource, logs: Set<BlockPos>): Set<BlockPos> {
            val rim = FloatArray(RIM_SECTORS) { radius + (random.nextFloat() - RIM_BIAS) * RIM_RAGGEDNESS }
            fun rimAt(dx: Int, dz: Int): Float {
                val angle = Mth.atan2(dz.toDouble(), dx.toDouble()).toFloat() + PI.toFloat()
                return rim[(angle / Mth.TWO_PI * RIM_SECTORS).toInt().coerceIn(0, RIM_SECTORS - 1)]
            }
            val leaves = mutableSetOf<BlockPos>()
            val reach = radius + 1
            for (dx in -reach..reach) {
                for (dz in -reach..reach) {
                    val out = sqrt((dx * dx + dz * dz).toFloat())
                    if (out <= rimAt(dx, dz)) leaves += hub.offset(dx, 1, dz)
                    val isUnderneath = out <= radius - FRINGE_INSET && random.nextFloat() < FRINGE_DENSITY
                    if (isUnderneath) leaves += hub.offset(dx, 0, dz)
                    if (out <= HUMP_RADIUS) leaves += hub.offset(dx, 2, dz)
                }
            }
            return leaves - logs
        }

        /**
         * Each leaf's distance from the nearest log, counted through other leaves as vanilla counts it, and
         * **any leaf that would be too far to hold is left off** — so a terrace is never wider than its
         * branches can carry, and nothing the tree grows decays on its own.
         */
        private fun withDistances(terraces: List<Terrace>, logs: Set<BlockPos>): List<Terrace> {
            val allLeaves = terraces.flatMap { it.leaves.keys }.toSet()
            val distance = HashMap<BlockPos, Int>()
            var frontier = logs.toList()
            for (step in 1..FARTHEST_HELD) {
                val next = mutableListOf<BlockPos>()
                for (from in frontier) {
                    for (direction in Direction.entries) {
                        val leaf = from.relative(direction)
                        if (leaf !in allLeaves || leaf in distance) continue
                        distance[leaf] = step
                        next += leaf
                    }
                }
                frontier = next
            }
            return terraces.map { terrace ->
                val held = terrace.leaves.keys.mapNotNull { leaf -> distance[leaf]?.let { leaf to it } }
                terrace.copy(leaves = held.toMap())
            }
        }

        /**
         * Roots arching out from the foot of the trunk and down into the ground, as a mangrove's do: the first
         * block of each beside the trunk above ground, the last a level below it.
         */
        private fun roots(heart: BlockPos, random: RandomSource, taken: Set<BlockPos>): List<BlockPos> {
            val count = random.nextIntBetweenInclusive(FEWEST_ROOTS, MOST_ROOTS)
            val start = random.nextFloat() * Mth.TWO_PI
            val roots = linkedSetOf<BlockPos>()
            for (root in 0..<count) {
                val angle = start + root * Mth.TWO_PI / count + (random.nextFloat() - 0.5f) * ROOT_SPREAD
                val length = random.nextIntBetweenInclusive(SHORTEST_ROOT, LONGEST_ROOT)
                for (step in 1..length) {
                    val drop = (ROOT_DESCENT * (step - 1)) / (length - 1)
                    val at = BlockPos(
                        heart.x + (cos(angle.toDouble()) * step).roundToInt(),
                        heart.y + 1 - drop,
                        heart.z + (sin(angle.toDouble()) * step).roundToInt(),
                    )
                    if (at != heart && at !in taken) roots += at
                }
            }
            return roots.toList()
        }

        private const val SHORTEST = 8
        private const val TALLEST = 13
        private const val THREE_TERRACES_FROM = 11
        private const val TWO_TERRACES = 2
        private const val THREE_TERRACES = 3

        /** How far up the trunk each tier sits, as a share of its height; the last is the top. */
        private val TWO_TIER_HEIGHTS = listOf(0.6, 1.0)
        private val THREE_TIER_HEIGHTS = listOf(0.45, 0.72, 1.0)

        /** How far the top leans off the root, in blocks, and how sharply the lean gathers toward the top. */
        private const val LEAST_LEAN = 2.0f
        private const val MOST_LEAN = 4.0f
        private const val CURVE = 1.6

        /** How far the trunk wanders sideways off its lean, in blocks. */
        private const val LEAST_WANDER = 0.4f
        private const val MOST_WANDER = 1.1f

        private const val TOP_RADIUS = 3
        private const val WIDE_TERRACE = 4
        private const val MANY_BRANCHES = 6
        private const val FEW_BRANCHES = 4
        private const val BRANCH_SHORT_OF_THE_RIM = 2
        private const val BRANCH_SPREAD = 0.5f

        private const val RIM_SECTORS = 12
        private const val RIM_RAGGEDNESS = 1.4f
        private const val RIM_BIAS = 0.6f
        private const val FRINGE_INSET = 1.5f
        private const val FRINGE_DENSITY = 0.5f
        private const val HUMP_RADIUS = 1.5f

        /** Vanilla's leaves decay at seven, so six is the farthest a leaf of ours may be from a log. */
        private const val FARTHEST_HELD = 6

        private const val FEWEST_ROOTS = 3
        private const val MOST_ROOTS = 5
        private const val SHORTEST_ROOT = 3
        private const val LONGEST_ROOT = 4
        private const val ROOT_DESCENT = 2
        private const val ROOT_SPREAD = 0.6f
    }
}
