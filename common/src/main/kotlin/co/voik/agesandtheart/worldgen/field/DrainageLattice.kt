package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable
import kotlin.math.sqrt

/**
 * The lattice both drainage networks, [Drainage] and [MountainRange], are built on: a square scan of nodes,
 * each flowing to its lowest neighbour, and the reaches between them.
 */

/** Whether a drainage network is describing its rock, or the water standing in its channels. */
enum class FieldYield : StringRepresentable {
    GROUND,
    WATER,
    ;

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<FieldYield> = StringRepresentable.fromEnum(FieldYield::values)
    }
}

/** A node lower than all eight of its neighbours. Nothing flows out of it, so its valley ends there. */
internal const val NOWHERE = -1

/**
 * The [size] × [size] square of nodes around one column: where each stands on the lattice's two axes, how
 * high it is, and which way it flows. Positions are in whatever planar frame the network lives in — world X
 * and Z, or a range's along and across.
 *
 * **Refilled rather than allocated.** A scan is a few kilobytes of arrays, and allocating one per column left
 * megabytes of garbage per chunk, so a network holds one per thread and overwrites it.
 */
internal class LatticeScan(val size: Int) {
    val first = DoubleArray(size * size)
    val second = DoubleArray(size * size)
    val height = DoubleArray(size * size)
    val flowsTo = IntArray(size * size)

    /** How many nodes out from the middle the scan reaches. */
    val ring = size / 2

    /**
     * The nodes around lattice cell ([cellFirst], [cellSecond]), row by row along the second axis. Each node's
     * cell is handed to [nodeFirst] and [nodeSecond] as (first, second), and its height is read at where they
     * put it. Inline, so the hot path stays unboxed.
     */
    inline fun fill(
        cellFirst: Int,
        cellSecond: Int,
        nodeFirst: (Int, Int) -> Double,
        nodeSecond: (Int, Int) -> Double,
        heightAt: (Double, Double) -> Double,
    ) {
        // Only the inner rings are written below, so the border would otherwise keep the last column's flow.
        flowsTo.fill(NOWHERE)
        for (row in 0..<size) {
            for (column in 0..<size) {
                val nodeCellFirst = cellFirst + column - ring
                val nodeCellSecond = cellSecond + row - ring
                val at = row * size + column
                first[at] = nodeFirst(nodeCellFirst, nodeCellSecond)
                second[at] = nodeSecond(nodeCellFirst, nodeCellSecond)
                height[at] = heightAt(first[at], second[at])
            }
        }
        // Only where a node's own eight neighbours are inside the scan can its flow be known at all.
        for (row in 1..<size - 1) {
            for (column in 1..<size - 1) {
                flowsTo[row * size + column] = lowestNeighbourOf(row, column, height, size)
            }
        }
    }

    /**
     * How many streams join at [here] — the stream order, and the whole of what tells a trunk from a
     * headwater. **Counted rather than accumulated**: a true Strahler order would need the network walked to
     * its sources, and one step of it is enough to tell a confluence from a beginning.
     */
    fun inflowsTo(here: Int): Int {
        val row = here / size
        val column = here % size
        var joining = 0
        for (upRow in -1..1) {
            for (upColumn in -1..1) {
                if (upRow == 0 && upColumn == 0) continue
                if (flowsTo[(row + upRow) * size + (column + upColumn)] == here) joining++
            }
        }
        return joining
    }
}

/**
 * Where a node stands on one axis: its [cell], nudged off the lattice point by up to [jitter] of a [spacing].
 * The hash is keyed on ([hashFirst], [hashSecond]), which each network chooses for itself.
 */
internal fun latticeNode(
    cell: Int,
    hashFirst: Int,
    hashSecond: Int,
    salt: Int,
    jitter: Double,
    spacing: Double,
): Double = (cell + HALF_A_CELL + cellHash(hashFirst, hashSecond, salt) * jitter) * spacing

/** A node's lattice point is the middle of its cell. */
private const val HALF_A_CELL = 0.5

/**
 * Which of a node's eight neighbours it flows to, or [NOWHERE] where it is lower than all of them. [height]
 * is a [scan] × [scan] square of node heights, row by row.
 */
internal fun lowestNeighbourOf(row: Int, column: Int, height: DoubleArray, scan: Int): Int {
    var lowest = NOWHERE
    var lowestHeight = height[row * scan + column]
    for (downRow in -1..1) {
        for (downColumn in -1..1) {
            if (downRow == 0 && downColumn == 0) continue
            val neighbour = (row + downRow) * scan + (column + downColumn)
            if (height[neighbour] >= lowestHeight) continue
            lowestHeight = height[neighbour]
            lowest = neighbour
        }
    }
    return lowest
}

/**
 * How far along a segment its nearest point to ([atX], [atZ]) lies, clamped to the segment. Any planar
 * frame will do — world X and Z, or a range's along and across.
 */
internal fun alongSegment(atX: Double, atZ: Double, fromX: Double, fromZ: Double, toX: Double, toZ: Double): Double {
    val runX = toX - fromX
    val runZ = toZ - fromZ
    val lengthSquared = runX * runX + runZ * runZ
    if (lengthSquared <= 0.0) return 0.0
    return (((atX - fromX) * runX + (atZ - fromZ) * runZ) / lengthSquared).coerceIn(0.0, 1.0)
}

internal fun distance(fromX: Double, fromZ: Double, toX: Double, toZ: Double): Double {
    val runX = toX - fromX
    val runZ = toZ - fromZ
    return sqrt(runX * runX + runZ * runZ)
}
