package co.voik.agesandtheart.portal

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/** What a block is to a linking portal being looked for. */
enum class PortalFooting {
    FRAME,
    OPEN,
    LIT,
    BLOCKED,
    ;

    /** Where the opening may run: nothing in the way, or the portal already standing there. */
    val isOpening: Boolean get() = this == OPEN || this == LIT
}

/**
 * A linking portal's opening, found by the nether portal's own rules with a block of phasmium for obsidian.
 *
 * A port of vanilla's `PortalShape`, whose frame is a private constant naming obsidian. The rules are
 * vanilla's to the letter: an opening 2 to 21 wide and 3 to 21 tall, a frame down both sides, across the
 * top and along the bottom, and no corners needed.
 *
 * Found over a [PortalFooting] per position rather than over a level, so the search is a pure function.
 */
class LinkingPortalShape private constructor(
    val axis: Direction.Axis,
    private val rightward: Direction,
    val bottomLeft: BlockPos,
    val width: Int,
    val height: Int,
    private val litCount: Int,
) {
    val isUnlit: Boolean get() = litCount == 0
    val isLit: Boolean get() = litCount == width * height

    /** Every position inside the frame. */
    val opening: List<BlockPos>
        get() = (0..<height).flatMap { up -> (0..<width).map { across -> at(across, up) } }

    /** The frame blocks the rules ask for, which leaves the four corners out. */
    val frame: List<BlockPos>
        get() {
            val sides = (0..<height).flatMap { up -> listOf(at(LEFT_SIDE, up), at(width, up)) }
            val topAndBottom = (0..<width).flatMap { across -> listOf(at(across, BOTTOM), at(across, height)) }
            return sides + topAndBottom
        }

    fun isFramedBy(position: BlockPos): Boolean = position in frame

    private fun at(across: Int, up: Int): BlockPos = bottomLeft.above(up).relative(rightward, across)

    companion object {
        const val MIN_WIDTH = 2
        const val MAX_WIDTH = 21
        const val MIN_HEIGHT = 3
        const val MAX_HEIGHT = 21

        private const val LEFT_SIDE = -1
        private const val BOTTOM = -1

        /**
         * The portal whose opening holds [position], framed in the plane of [axis], or null where there is none.
         *
         * [lowestY] is the level's floor, which the search will not look beneath.
         */
        fun find(
            footing: (BlockPos) -> PortalFooting,
            lowestY: Int,
            position: BlockPos,
            axis: Direction.Axis,
        ): LinkingPortalShape? {
            val rightward = if (axis == Direction.Axis.X) Direction.WEST else Direction.SOUTH
            val bottomLeft = bottomLeftOf(footing, lowestY, position, rightward) ?: return null
            val width = distanceToTheFrame(footing, bottomLeft, rightward)
            if (width !in MIN_WIDTH..MAX_WIDTH) return null
            val rise = riseOf(footing, bottomLeft, rightward, width)
            val isFramedAbove = (0..<width).all { across ->
                footing(bottomLeft.above(rise.height).relative(rightward, across)) == PortalFooting.FRAME
            }
            if (rise.height !in MIN_HEIGHT..MAX_HEIGHT || !isFramedAbove) return null
            return LinkingPortalShape(axis, rightward, bottomLeft, width, rise.height, rise.litCount)
        }

        /** [find] in whichever plane has a portal, trying [preferred] first. */
        fun findInEitherPlane(
            footing: (BlockPos) -> PortalFooting,
            lowestY: Int,
            position: BlockPos,
            preferred: Direction.Axis,
        ): LinkingPortalShape? {
            val other = if (preferred == Direction.Axis.X) Direction.Axis.Z else Direction.Axis.X
            return find(footing, lowestY, position, preferred) ?: find(footing, lowestY, position, other)
        }

        private fun bottomLeftOf(
            footing: (BlockPos) -> PortalFooting,
            lowestY: Int,
            position: BlockPos,
            rightward: Direction,
        ): BlockPos? {
            val floor = maxOf(lowestY, position.y - MAX_HEIGHT)
            var bottom = position
            while (bottom.y > floor && footing(bottom.below()).isOpening) bottom = bottom.below()
            val leftward = rightward.opposite
            val stepsToTheLeftEdge = distanceToTheFrame(footing, bottom, leftward) - 1
            return if (stepsToTheLeftEdge < 0) null else bottom.relative(leftward, stepsToTheLeftEdge)
        }

        /**
         * How far along [direction] the frame stands, walking an opening with frame under every step; 0 where
         * the walk meets anything else first.
         */
        private fun distanceToTheFrame(
            footing: (BlockPos) -> PortalFooting,
            start: BlockPos,
            direction: Direction,
        ): Int {
            for (steps in 0..MAX_WIDTH) {
                val here = start.relative(direction, steps)
                val atHere = footing(here)
                if (!atHere.isOpening) return if (atHere == PortalFooting.FRAME) steps else 0
                if (footing(here.below()) != PortalFooting.FRAME) return 0
            }
            return 0
        }

        private data class Rise(val height: Int, val litCount: Int)

        /** How many rows up the opening runs with frame at both ends, counting the portal already in it. */
        private fun riseOf(
            footing: (BlockPos) -> PortalFooting,
            bottomLeft: BlockPos,
            rightward: Direction,
            width: Int,
        ): Rise {
            var litCount = 0
            for (up in 0..<MAX_HEIGHT) {
                val row = bottomLeft.above(up)
                val isFramedAtBothEnds = footing(row.relative(rightward, LEFT_SIDE)) == PortalFooting.FRAME &&
                    footing(row.relative(rightward, width)) == PortalFooting.FRAME
                if (!isFramedAtBothEnds) return Rise(up, litCount)
                for (across in 0..<width) {
                    val atHere = footing(row.relative(rightward, across))
                    if (!atHere.isOpening) return Rise(up, litCount)
                    if (atHere == PortalFooting.LIT) litCount++
                }
            }
            return Rise(MAX_HEIGHT, litCount)
        }
    }
}
