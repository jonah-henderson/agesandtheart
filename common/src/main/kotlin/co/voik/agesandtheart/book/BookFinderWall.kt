package co.voik.agesandtheart.book

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChiseledBookShelfBlock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A block space in front of a book finder's wall, counted from the finder's own: [across] to the right as the wall is
 * faced, and [up].
 */
data class WallCell(val across: Int, val up: Int) {
    companion object {
        /** The space in front of [shelf], for a finder at [finder] facing [facing]. */
        fun of(finder: BlockPos, facing: Direction, shelf: BlockPos): WallCell {
            val right = facing.counterClockWise
            val offset = shelf.relative(facing).subtract(finder)
            return WallCell(offset.x * right.stepX + offset.z * right.stepZ, offset.y)
        }
    }
}

/** A point in the plane of those spaces, in blocks, each space's middle at whole numbers. */
data class WallPoint(val across: Double, val up: Double) {
    fun distanceTo(other: WallPoint): Double = hypot(other.across - across, other.up - up)

    companion object {
        /** The middle of the space in front of [shelf], for a finder at [finder] facing [facing]. */
        fun of(finder: BlockPos, facing: Direction, shelf: BlockPos): WallPoint = FramePath.pointOf(WallCell.of(finder, facing, shelf))
    }
}

/**
 * A way the frame travels across the face of the wall, at an even pace along it: [points] joined by straight lines,
 * finely enough that a curve is drawn as one.
 */
data class FramePath(val points: List<WallPoint>) {

    val length: Double = points.zipWithNext { a, b -> a.distanceTo(b) }.sum()

    /** Where the frame is [travelled] blocks along, held at either end. */
    fun at(travelled: Double): WallPoint {
        var left = travelled.coerceAtLeast(0.0)
        for ((a, b) in points.zipWithNext()) {
            val step = a.distanceTo(b)
            if (left <= step && step > 0.0) {
                val share = left / step
                return WallPoint(a.across + (b.across - a.across) * share, a.up + (b.up - a.up) * share)
            }
            left -= step
        }
        return points.last()
    }

    /** The same way travelled the other way, as the frame comes home by the way it went. */
    fun reversed(): FramePath = FramePath(points.reversed())

    companion object {
        /**
         * The way from [from] to [to] over [room], or null where there is none: always on the grid of shelves, a space at
         * a time to a side or up or down, never across a corner of the wall where there is no shelf (Jonah, 2026-11-19).
         *
         * Where it fits, the grid's reading of a curve (Jonah, 2026-11-18): the height covered as the square root of the
         * distance along, so it sets off up or down the wall, steps across it in a staircase as it bends, and comes in
         * on long runs along it, arriving on a move along the wall. Where something stands in the curve's way, the walk
         * from space to space through the wall, curved wherever there is room along it (Jonah, 2026-11-19): from each
         * space, a curve to the farthest space of the walk it reaches without leaving the wall, so a frame that must
         * thread a narrow place first still sweeps across the open wall beyond it.
         */
        fun between(from: WallCell, to: WallCell, room: Set<WallCell>): FramePath? {
            if (from == to) return FramePath(listOf(pointOf(from), pointOf(to)))
            stepsOfTheCurve(from, to, room)?.let { return pathThrough(it) }
            val walk = walk(from, to, room) ?: return null
            val cells = mutableListOf(from)
            var at = 0
            while (at < walk.lastIndex) {
                // The next space of the walk is a single step within the wall, so there is always one to take.
                val farthest = (walk.lastIndex downTo at + 1).first { stepsOfTheCurve(walk[at], walk[it], room) != null }
                cells += stepsOfTheCurve(walk[at], walk[farthest], room)!!.drop(1)
                at = farthest
            }
            return pathThrough(cells)
        }

        /**
         * The spaces the [curve] from [from] to [to] passes, each a single step from the last — where the curve crosses
         * a corner, the step taken first is whichever side of it is a shelf, up or down before along — or null where it
         * leaves [room].
         */
        fun stepsOfTheCurve(from: WallCell, to: WallCell, room: Set<WallCell>): List<WallCell>? {
            val steps = mutableListOf(from)
            for (point in curve(from, to).points) {
                val next = cellUnder(point)
                val last = steps.last()
                if (next == last) continue
                if (next.across != last.across && next.up != last.up) {
                    val upFirst = WallCell(last.across, next.up)
                    val alongFirst = WallCell(next.across, last.up)
                    steps += if (upFirst in room) upFirst else alongFirst
                }
                steps += next
            }
            return steps.takeIf { cells -> cells.all { it in room } }
        }

        /** Through the middles of [cells], keeping only the turns, at an even pace. */
        private fun pathThrough(cells: List<WallCell>): FramePath {
            val turns = cells.filterIndexed { index, cell ->
                if (index == 0 || index == cells.lastIndex) return@filterIndexed true
                val before = cells[index - 1]
                val after = cells[index + 1]
                (cell.across - before.across) != (after.across - cell.across) || (cell.up - before.up) != (after.up - cell.up)
            }
            return FramePath(turns.map(::pointOf))
        }

        /**
         * From [from] to [to] as `up = √along`: drawn by `s` from 0 to 1, along the wall `s²` of the way and up it
         * `s`, so it leaves straight up or down and arrives at half the slope of a straight line. The frame never flies
         * it; it is what [stepsOfTheCurve] reads off the grid.
         */
        fun curve(from: WallCell, to: WallCell): FramePath {
            val across = (to.across - from.across).toDouble()
            val up = (to.up - from.up).toDouble()
            val samples = max(2, (SAMPLES_A_BLOCK * (abs(across) + abs(up))).toInt())
            return FramePath(
                (0..samples).map { step ->
                    val s = step.toDouble() / samples
                    WallPoint(from.across + across * s * s, from.up + up * s)
                },
            )
        }

        /**
         * The fewest spaces from [from] to [to] through [room], each a step to a side or up or down. Searched back from
         * [to] trying along the wall first, so that among the shortest ways it is one that arrives along the wall.
         */
        fun walk(from: WallCell, to: WallCell, room: Set<WallCell>): List<WallCell>? {
            if (from !in room || to !in room) return null
            val towardTo = HashMap<WallCell, WallCell>()
            val waiting = ArrayDeque(listOf(to))
            val seen = hashSetOf(to)
            while (waiting.isNotEmpty()) {
                val cell = waiting.removeFirst()
                if (cell == from) break
                for ((across, up) in STEPS) {
                    val next = WallCell(cell.across + across, cell.up + up)
                    if (next !in room || !seen.add(next)) continue
                    towardTo[next] = cell
                    waiting.addLast(next)
                }
            }
            if (from !in seen) return null
            val way = mutableListOf(from)
            while (way.last() != to) way += towardTo.getValue(way.last())
            return way
        }

        fun pointOf(cell: WallCell): WallPoint = WallPoint(cell.across.toDouble(), cell.up.toDouble())

        fun cellUnder(point: WallPoint): WallCell = WallCell(Math.round(point.across).toInt(), Math.round(point.up).toInt())

        /** Along the wall first, then up and down. */
        private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        /** Fine enough that no space a curve passes through is stepped over. */
        private const val SAMPLES_A_BLOCK = 8
    }
}

/**
 * The chiselled bookshelves a book finder can reach (design §7.8.3): every one joined to the shelf it is
 * mounted on, side to side or one over another, facing the way it does, with a book finder frame over its front, which
 * is the track its frame travels (Jonah, 2026-11-19). A wall reaching into an unloaded chunk stops there, and none is
 * larger than [MOST_SHELVES].
 */
class BookFinderWall(val finder: BlockPos, val facing: Direction, val shelves: Set<BlockPos>) {

    /** Toward the right as the wall is faced. */
    private val right: Direction = facing.counterClockWise

    /** The spaces in front of the shelves, the finder's own among them. */
    val room: Set<WallCell> = shelves.mapTo(HashSet()) { cellOf(it) }

    /** The space in front of [shelf]. */
    fun cellOf(shelf: BlockPos): WallCell = WallCell.of(finder, facing, shelf)

    /** The shelf behind [cell]. */
    fun shelfAt(cell: WallCell): BlockPos = finder.relative(right, cell.across).above(cell.up).relative(facing.opposite)

    companion object {
        const val MOST_SHELVES = 256

        /** The wall behind a finder at [finder] facing [facing], as it stands now. */
        fun of(level: Level, finder: BlockPos, facing: Direction): BookFinderWall {
            val first = finder.relative(facing.opposite)
            val found = LinkedHashSet<BlockPos>()
            val waiting = ArrayDeque<BlockPos>()
            fun consider(shelf: BlockPos) {
                if (found.size >= MOST_SHELVES || shelf in found || !level.isLoaded(shelf) || !level.isLoaded(shelf.relative(facing))) return
                val state = level.getBlockState(shelf)
                val isAShelfOfTheWall = state.`is`(Blocks.CHISELED_BOOKSHELF) && state.getValue(ChiseledBookShelfBlock.FACING) == facing
                if (!isAShelfOfTheWall) return
                val front = level.getBlockState(shelf.relative(facing))
                val hasATrack = shelf.relative(facing) == finder ||
                    (front.`is`(BookFinders.FRAME) && front.getValue(BookFinderFrameBlock.FACING) == facing)
                if (!hasATrack) return
                found += shelf
                waiting.addLast(shelf)
            }
            consider(first)
            while (waiting.isNotEmpty()) {
                val shelf = waiting.removeFirst()
                for (way in listOf(Direction.UP, Direction.DOWN, facing.clockWise, facing.counterClockWise)) consider(shelf.relative(way))
            }
            return BookFinderWall(finder, facing, found)
        }
    }
}
