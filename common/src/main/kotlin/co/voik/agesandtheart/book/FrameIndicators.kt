package co.voik.agesandtheart.book

import co.voik.agesandtheart.book.BookFinderFrameBlock.Indicator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChiseledBookShelfBlock

/**
 * What each book finder frame's indicator shows. Whether a finder reaches a frame changes with any track on its wall,
 * so a finder or frame placed or broken settles every frame joined to it: [Indicator.READY] where a finder's
 * [BookFinderWall] takes its shelf in, and [Indicator.INVALID] where none does — no finder on its part of the wall, or
 * a wall already [BookFinderWall.MOST_SHELVES] large before it. A finder lights the frame on the shelf of its errand
 * [Indicator.ACTIVE] for as long as it lasts, a finder's own frame among them.
 */
object FrameIndicators {

    /** A finder or frame at [front], facing [facing], just placed. */
    fun trackAdded(level: ServerLevel, front: BlockPos, facing: Direction) {
        settle(level, front.relative(facing.opposite), facing)
    }

    /**
     * A finder or frame at [front], facing [facing], just broken: each shelf beside its own may now be on a part of the
     * wall of its own, and each finder whose wall it was told, since its carriage may have been going that way.
     */
    fun trackRemoved(level: ServerLevel, front: BlockPos, facing: Direction) {
        val shelf = front.relative(facing.opposite)
        val settled = HashSet<BlockPos>()
        for (way in alongTheWall(facing)) {
            val beside = shelf.relative(way)
            if (beside !in settled) settled += settle(level, beside, facing)
        }
        for (each in settled) (level.getBlockEntity(each.relative(facing)) as? BookFinderBlockEntity)?.wallChanged()
    }

    /** A finder facing [facing] whose errand moved from the shelf [from] to the shelf [to]. */
    fun errandMoved(level: Level?, facing: Direction, from: BlockPos?, to: BlockPos?) {
        if (level !is ServerLevel) return
        from?.let { show(level, it.relative(facing), facing) { shown -> if (shown == Indicator.ACTIVE) Indicator.READY else shown } }
        to?.let { show(level, it.relative(facing), facing) { shown -> if (shown == Indicator.READY) Indicator.ACTIVE else shown } }
    }

    /**
     * Every frame on the part of the wall joined to [shelf] shown as its finders reach it, and that part returned.
     * A frame lit for an errand stays lit while a finder still reaches it.
     */
    private fun settle(level: ServerLevel, shelf: BlockPos, facing: Direction): Set<BlockPos> {
        val joined = joinedTo(level, shelf, facing)
        val finders = joined.map { it.relative(facing) }.filter { level.getBlockState(it).`is`(BookFinders.BLOCK) }
        val reached = finders.flatMapTo(HashSet()) { BookFinderWall.of(level, it, facing).shelves }
        for (frameShelf in joined) {
            show(level, frameShelf.relative(facing), facing) { shown ->
                when {
                    frameShelf !in reached -> Indicator.INVALID
                    shown == Indicator.ACTIVE -> Indicator.ACTIVE
                    else -> Indicator.READY
                }
            }
        }
        return joined
    }

    /**
     * The shelves joined to [shelf] side to side or one over another, each facing [facing] with a finder or a frame in
     * front of it, as [BookFinderWall] joins them but without its limit; stopping at unloaded chunks and at
     * [MOST_SETTLED], past which a wall is many finders' walls over.
     */
    private fun joinedTo(level: ServerLevel, shelf: BlockPos, facing: Direction): Set<BlockPos> {
        val found = LinkedHashSet<BlockPos>()
        val waiting = ArrayDeque<BlockPos>()
        fun consider(candidate: BlockPos) {
            if (found.size >= MOST_SETTLED || candidate in found || !hasATrack(level, candidate, facing)) return
            found += candidate
            waiting.addLast(candidate)
        }
        consider(shelf)
        while (waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            for (way in alongTheWall(facing)) consider(next.relative(way))
        }
        return found
    }

    private fun hasATrack(level: ServerLevel, shelf: BlockPos, facing: Direction): Boolean {
        val front = shelf.relative(facing)
        if (!level.isLoaded(shelf) || !level.isLoaded(front)) return false
        val state = level.getBlockState(shelf)
        val isAShelfFacingOut = state.`is`(Blocks.CHISELED_BOOKSHELF) && state.getValue(ChiseledBookShelfBlock.FACING) == facing
        val inFront = level.getBlockState(front)
        val isFinderOrFrame = inFront.`is`(BookFinders.BLOCK) || inFront.`is`(BookFinders.FRAME)
        return isAShelfFacingOut && isFinderOrFrame && inFront.getValue(BookFinderFrameBlock.FACING) == facing
    }

    /** The frame or finder at [front] facing [facing], if there is one, shown as [change] has it. */
    private fun show(level: ServerLevel, front: BlockPos, facing: Direction, change: (Indicator) -> Indicator) {
        val state = level.getBlockState(front)
        val isAFrameOrFinder = state.`is`(BookFinders.FRAME) || state.`is`(BookFinders.BLOCK)
        val isAFrameFacingOut = isAFrameOrFinder && state.getValue(BookFinderFrameBlock.FACING) == facing
        if (!isAFrameFacingOut) return
        val shown = state.getValue(BookFinderFrameBlock.INDICATOR)
        val next = change(shown)
        if (next != shown) level.setBlock(front, state.setValue(BookFinderFrameBlock.INDICATOR, next), Block.UPDATE_CLIENTS)
    }

    private fun alongTheWall(facing: Direction): List<Direction> =
        listOf(Direction.UP, Direction.DOWN, facing.clockWise, facing.counterClockWise)

    /** Sixteen finders' walls, as far as one settling goes. */
    private const val MOST_SETTLED = BookFinderWall.MOST_SHELVES * 16
}
