package co.voik.agesandtheart.worldgen.fissure

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.pathfinder.NodeEvaluator

/**
 * The ground at a fissure's exit, read as something falling onto it would meet it: from the top down, so what
 * comes out lands on top of the heap and never in a gap inside it.
 *
 * **Solid means it has a collision shape**, not that the heightmaps count it. 26.3's `MOTION_BLOCKING` is a block
 * tag, and a block missing from it was invisible to the old reading — rubble, fluids and players were all put
 * inside heaps of it.
 */
object ExitGround {

    /**
     * One column: [restingOn], the highest block anything could come to rest on, or null where nothing could; and
     * [clearAbove], the first height over every block in it, fluids and flowers included.
     */
    data class Column(val x: Int, val z: Int, val restingOn: Int?, val clearAbove: Int)

    /** The column at [x], [z], loading its chunk — an unloaded one answers its heightmaps with the floor. */
    fun column(level: ServerLevel, x: Int, z: Int): Column {
        val chunk = level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
        val clearAbove = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1
        return read(level, x, z, clearAbove)
    }

    /** The column at [x], [z], searched down from [clearAbove] for the first block with a collision shape. */
    fun read(level: BlockGetter, x: Int, z: Int, clearAbove: Int): Column {
        val cursor = BlockPos.MutableBlockPos()
        val restingOn = (clearAbove - 1 downTo level.minY).firstOrNull { y ->
            cursor.set(x, y, z)
            !level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty
        }
        return Column(x, z, restingOn, clearAbove)
    }

    /**
     * Where a fluid's source poured into [column] goes: the first place over the heap not already holding a
     * source, so a pool there rises rather than having one source swapped for another. Null where there is no heap.
     */
    fun pouredInto(level: BlockGetter, column: Column): BlockPos? {
        val restingOn = column.restingOn ?: return null
        val cursor = BlockPos.MutableBlockPos()
        val y = (restingOn + 1..<column.clearAbove).firstOrNull { y ->
            !level.getBlockState(cursor.set(column.x, y, column.z)).fluidState.isSource
        } ?: column.clearAbove
        return BlockPos(column.x, y, column.z)
    }

    /**
     * Whether something falling into [column] comes to rest somewhere that does not burn: nothing of
     * `NodeEvaluator.isBurningBlock`'s on the way down through what is not solid, nor in what it lands on.
     */
    fun isSafeToLandIn(level: BlockGetter, column: Column): Boolean {
        val restingOn = column.restingOn ?: return false
        val cursor = BlockPos.MutableBlockPos()
        return (restingOn..<column.clearAbove).none { y ->
            NodeEvaluator.isBurningBlock(level.getBlockState(cursor.set(column.x, y, column.z)))
        }
    }

    /**
     * The column nearest [x], [z] that is safe to land in, out to [LANDING_SEARCH_RADIUS] — and the one at [x], [z]
     * itself where none is, since a fall has to come down somewhere.
     */
    fun safestNear(level: BlockGetter, x: Int, z: Int, columnAt: (Int, Int) -> Column): Column {
        val safe = NEAREST_FIRST.asSequence()
            .map { (awayX, awayZ) -> columnAt(x + awayX, z + awayZ) }
            .firstOrNull { isSafeToLandIn(level, it) }
        return safe ?: columnAt(x, z)
    }

    /** A little past the disc rubble is scattered over, so a heap soaked through with lava still has an edge. */
    const val LANDING_SEARCH_RADIUS = 8

    /** Every offset within [LANDING_SEARCH_RADIUS], nearest first, starting with the column itself. */
    private val NEAREST_FIRST: List<Pair<Int, Int>> = run {
        val reach = -LANDING_SEARCH_RADIUS..LANDING_SEARCH_RADIUS
        val withinReach = reach.flatMap { awayX -> reach.map { awayZ -> awayX to awayZ } }
            .filter { (awayX, awayZ) -> awayX * awayX + awayZ * awayZ <= LANDING_SEARCH_RADIUS * LANDING_SEARCH_RADIUS }
        withinReach.sortedBy { (awayX, awayZ) -> awayX * awayX + awayZ * awayZ }
    }
}
