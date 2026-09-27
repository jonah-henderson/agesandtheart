package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.levelgen.structure.BoundingBox

/**
 * The cage a block standing at a position is set on: a box whose twelve edges and eight corners are frame,
 * with a frame block beside that position somewhere on it.
 *
 * Its faces may be open or filled with anything, frame included. It is found by walking the frame: down
 * to its lowest corner, out along the three edges from there, and then checking the rest. Frame that runs
 * on past the box, such as a wider floor or a second cage sharing a wall, carries a walk with it and the
 * box is not found.
 *
 * Found over a frame test per position rather than over a level, so the search is a pure function.
 */
object CageShape {
    const val MIN_INSIDE = 1
    const val MAX_INSIDE = 21

    /** The farthest a corner can be from any other block of the frame, along one axis. */
    private const val REACH = MAX_INSIDE + 1

    /** The cage a block at [device] is set on, as its outline with the frame included, or null. */
    fun around(isFrame: (BlockPos) -> Boolean, device: BlockPos): BoundingBox? =
        Direction.entries.map(device::relative).filter(isFrame).firstNotNullOfOrNull { cageThrough(isFrame, it) }

    private fun cageThrough(isFrame: (BlockPos) -> Boolean, start: BlockPos): BoundingBox? {
        val corner = lowestCornerFrom(isFrame, start) ?: return null
        val width = insideAlong(isFrame, corner, Direction.EAST) ?: return null
        val height = insideAlong(isFrame, corner, Direction.UP) ?: return null
        val depth = insideAlong(isFrame, corner, Direction.SOUTH) ?: return null
        val outline = BoundingBox(
            corner.x, corner.y, corner.z,
            corner.x + width + 1, corner.y + height + 1, corner.z + depth + 1,
        )
        return outline.takeIf { edgesOf(it).all(isFrame) }
    }

    /**
     * Steps down through the frame on any axis until no step is left. Every step keeps to the frame and
     * lowers a coordinate, so on a cage it ends at the corner nearest the origin wherever it starts.
     */
    private fun lowestCornerFrom(isFrame: (BlockPos) -> Boolean, start: BlockPos): BlockPos? {
        var at = start
        while (true) {
            val next = DOWNWARD.map(at::relative).firstOrNull(isFrame) ?: return at
            val isTooFarFromTheStart = start.x - next.x > REACH || start.y - next.y > REACH || start.z - next.z > REACH
            if (isTooFarFromTheStart) return null
            at = next
        }
    }

    /** How far inside runs from [corner] along [direction], up to the frame at the far corner; null if not a cage's. */
    private fun insideAlong(isFrame: (BlockPos) -> Boolean, corner: BlockPos, direction: Direction): Int? {
        val run = (1..REACH + 1).takeWhile { steps -> isFrame(corner.relative(direction, steps)) }.size
        val inside = run - 1
        return inside.takeIf { it in MIN_INSIDE..MAX_INSIDE }
    }

    /** Every block of [outline] on two of its faces at once: the edges, and the corners where they meet. */
    private fun edgesOf(outline: BoundingBox): List<BlockPos> {
        fun facesAt(position: BlockPos): Int = listOf(
            position.x == outline.minX() || position.x == outline.maxX(),
            position.y == outline.minY() || position.y == outline.maxY(),
            position.z == outline.minZ() || position.z == outline.maxZ(),
        ).count { it }
        return BlockPos.betweenClosed(outline.minX(), outline.minY(), outline.minZ(), outline.maxX(), outline.maxY(), outline.maxZ())
            .map(BlockPos::immutable)
            .filter { facesAt(it) >= 2 }
    }

    private val DOWNWARD = listOf(Direction.WEST, Direction.DOWN, Direction.NORTH)
}
