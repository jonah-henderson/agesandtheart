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
