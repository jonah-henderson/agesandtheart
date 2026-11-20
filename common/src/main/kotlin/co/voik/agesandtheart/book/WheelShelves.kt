package co.voik.agesandtheart.book

import co.voik.agesandtheart.book.ModelledShelves.Companion.BOOK_OVER_THE_SHELF
import co.voik.agesandtheart.book.ModelledShelves.Companion.EDGE_ON
import co.voik.agesandtheart.book.ModelledShelves.Companion.MIDDLE
import co.voik.agesandtheart.book.ModelledShelves.Companion.PIXELS
import co.voik.agesandtheart.book.ModelledShelves.Companion.alongTheLookTo
import co.voik.agesandtheart.book.ModelledShelves.Companion.frameMet
import co.voik.agesandtheart.book.ModelledShelves.Companion.inTheModel
import co.voik.agesandtheart.book.ModelledShelves.Companion.nearestOf
import co.voik.agesandtheart.book.ModelledShelves.Companion.toTheBlock
import co.voik.agesandtheart.book.ModelledShelves.Companion.turnedToFace
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** What a look at a modelled wheel meets first, and where, in the model's pixels. */
sealed interface ShelfLook {
    val point: Vec3

    data class AtBook(val position: Int, override val point: Vec3) : ShelfLook
    data class AtShelf(val position: Int, override val point: Vec3) : ShelfLook
    data class AtFrame(override val point: Vec3) : ShelfLook
}

/**
 * The shelves of a wheel drawn from Jonah's model, at rest, in the pixels of the block holding it as the model faces
 * north, and what using them means. Positions are counted round from the front.
 */
sealed interface ModelledShelves {
    val positions: Int

    /**
     * What a look from [eye] through [hit], both in blocks from the corner of the block holding the wheel, meets first
     * on a wheel facing [facing] at rest: a book on the shelf at a position [hasABook], a shelf, the frame; or, meeting
     * none of them, the block where it was hit.
     */
    fun lookedAt(facing: Direction, eye: Vec3, hit: Vec3, hasABook: (Int) -> Boolean): ShelfLook

    /**
     * Which way a use landing at [point], in the model's pixels, pushes a wheel facing [facing], used from [eye] in
     * blocks from the corner of the block holding it: the way the look, pushed on at [point], would turn the wheel
     * about its axle (Jonah). Each shelf moves on to the next position (-1, the one before it coming to the front) or
     * back (1); a look straight at the axle turns it neither way (0).
     */
    fun pushedBy(facing: Direction, eye: Vec3, point: Vec3): Int

    /** The surface a book lies on, on the shelf resting at [position], in the block of a wheel facing [facing]. */
    fun bookSurface(facing: Direction, position: Int): BookSurface

    companion object {
        const val PIXELS = 16.0
        const val MIDDLE = 8.0

        /** How far over its shelf's surface a book's open pages stand. */
        const val BOOK_OVER_THE_SHELF = 2.0

        /** How far a look is followed, in pixels. */
        const val LOOKED_THROUGH = 160.0

        const val EDGE_ON = 1e-6

        /** A point in the model's pixels, facing north, in the block holding a wheel facing [facing]. */
        fun toTheBlock(facing: Direction, pixels: Vec3): Vec3 {
            val east = facing.clockWise
            val back = facing.opposite
            val across = pixels.x - MIDDLE
            val toTheBack = pixels.z - MIDDLE
            return Vec3(
                (MIDDLE + across * east.stepX + toTheBack * back.stepX) / PIXELS,
                pixels.y / PIXELS,
                (MIDDLE + across * east.stepZ + toTheBack * back.stepZ) / PIXELS,
            )
        }

        /** A direction in the model, facing north, in the world of a wheel facing [facing]. */
        fun turnedToFace(facing: Direction, direction: Vec3): Vec3 {
            val east = facing.clockWise
            val back = facing.opposite
            return Vec3(
                direction.x * east.stepX + direction.z * back.stepX,
                direction.y,
                direction.x * east.stepZ + direction.z * back.stepZ,
            )
        }

        /** A point in the block holding a wheel facing [facing], in the model's pixels facing north. */
        fun inTheModel(facing: Direction, inTheBlock: Vec3): Vec3 {
            val east = facing.clockWise
            val back = facing.opposite
            val fromTheMiddle = inTheBlock.subtract(0.5, 0.0, 0.5)
            return Vec3(
                MIDDLE + (fromTheMiddle.x * east.stepX + fromTheMiddle.z * east.stepZ) * PIXELS,
                inTheBlock.y * PIXELS,
                MIDDLE + (fromTheMiddle.x * back.stepX + fromTheMiddle.z * back.stepZ) * PIXELS,
            )
        }

        /**
         * How far along a look from [from] it meets a rectangle about [middle] facing [normal], [halfAcross] either way
         * along [across] and [halfAlong] along [along]; or null where it misses, or where it comes from behind and
         * [onlyFromTheFront].
         */
        fun alongTheLookTo(
            middle: Vec3,
            normal: Vec3,
            across: Vec3,
            along: Vec3,
            halfAcross: Double,
            halfAlong: Double,
            from: Vec3,
            looking: Vec3,
            onlyFromTheFront: Boolean,
        ): Double? {
            val into = looking.dot(normal)
            val isEdgeOn = abs(into) < EDGE_ON
            if (isEdgeOn || (onlyFromTheFront && into > 0.0)) return null
            val distance = middle.subtract(from).dot(normal) / into
            if (distance <= 0.0) return null
            val landed = from.add(looking.scale(distance)).subtract(middle)
            val isOnIt = abs(landed.dot(across)) <= halfAcross && abs(landed.dot(along)) <= halfAlong
            return if (isOnIt) distance else null
        }

        /** The nearest of what a look from [from] along [looking] met, or the frame where it was [through] the block. */
        fun nearestOf(met: List<Pair<Double, ShelfLook>>, through: Vec3): ShelfLook =
            met.minByOrNull { it.first }?.second ?: ShelfLook.AtFrame(through)

        /** Where a look from [from] along [looking] meets each of [boxes]. */
        fun frameMet(boxes: List<AABB>, from: Vec3, looking: Vec3): List<Pair<Double, ShelfLook>> {
            val end = from.add(looking.scale(LOOKED_THROUGH))
            return boxes.mapNotNull { box -> box.clip(from, end).orElse(null)?.let { it.distanceTo(from) to ShelfLook.AtFrame(it) } }
        }
    }
}

/**
 * Where a wheel's shelves are on an axle running across, as Jonah's model rests them facing north, in the pixels of the
 * block holding the wheel. Each shelf turns round the axle with the wheel, and from one resting place to the next it moves between the
 * two places' distances out from the axle and across, and tilts between their tilts, so that it passes through every
 * resting pose on its way round. On the small wheel that is a shelf sliding along an arm as in a groove (Jonah); on the
 * large one every shelf hangs level on a pin at the same distance, and only the front one tilts.
 *
 * Positions are counted round from the front, over the top: the front is 0, and a shelf at position 1 is the next one
 * up.
 */
class WheelShelves(
    /** Where the axle runs, across the model. */
    val axle: Vec3,
    /** The shelves as the model rests them, by position. */
    private val atRest: List<ShelfPose>,
    /** Half a shelf across and along its top, and a little over, so that a look at its edge meets it. */
    private val halfAcross: Double,
    private val halfAlong: Double,
    /** What else of the wheel a look may meet, at rest. */
    private val frame: List<AABB>,
) : ModelledShelves {
    /** A shelf's middle, its tilt about the model's x axis (a negative tilt lowers its front edge), and how thick it is. */
    data class ShelfPose(val middle: Vec3, val tiltDegrees: Double, val thickness: Double) {
        private val tilt: Double get() = Math.toRadians(tiltDegrees)

        /** Out of the shelf's top. */
        val normal: Vec3 get() = Vec3(0.0, cos(tilt), sin(tilt))

        /** Along the shelf's top toward its back edge. */
        val towardTheBack: Vec3 get() = Vec3(0.0, -sin(tilt), cos(tilt))

        /** The middle of the shelf's top, where a book lies. */
        val top: Vec3 get() = middle.add(normal.scale(thickness / 2))

        /** How far a book on it, read from the front, leans back from upright: a lectern's 67.5 at the front, flat 90. */
        val leanedBackDegrees: Double get() = QUARTER_TURN_DEGREES + tiltDegrees
    }

    override val positions: Int get() = atRest.size

    private val step: Double get() = FULL_TURN / positions

    /** Out from the axle toward the resting place [round] radians round from the front. */
    private fun outward(round: Double) = Vec3(0.0, sin(round), -cos(round))

    /** Across that, the way the wheel turns. */
    private fun sideways(round: Double) = Vec3(0.0, cos(round), sin(round))

    /** How far out from the axle, and across, the shelf resting at [position] is. */
    private fun outFromTheAxle(position: Int): Pair<Double, Double> {
        val round = position * step
        val fromTheAxle = atRest[position].middle.subtract(axle)
        return fromTheAxle.dot(outward(round)) to fromTheAxle.dot(sideways(round))
    }

    /** The shelf at [position] round, fractional between its resting places. */
    fun poseAt(position: Double): ShelfPose {
        val wrapped = position - positions * floor(position / positions)
        val from = floor(wrapped).toInt()
        val to = (from + 1) % positions
        val share = wrapped - from
        fun between(a: Double, b: Double) = a + (b - a) * share
        val (outFrom, acrossFrom) = outFromTheAxle(from)
        val (outTo, acrossTo) = outFromTheAxle(to)
        val round = wrapped * step
        val middle = axle
            .add(outward(round).scale(between(outFrom, outTo)))
            .add(sideways(round).scale(between(acrossFrom, acrossTo)))
        val resting = atRest[from]
        return ShelfPose(middle, between(resting.tiltDegrees, atRest[to].tiltDegrees), resting.thickness)
    }

    /** How far the wheel has turned, in degrees about the model's x axis, with [front] the tray at the front. */
    fun turnedDegrees(front: Double): Double = -front * Math.toDegrees(step)

    override fun bookSurface(facing: Direction, position: Int): BookSurface {
        val pose = atRest[position]
        return BookSurface(toTheBlock(facing, pose.top), pose.leanedBackDegrees, turnedToFace(facing, TOWARD_THE_FRONT))
    }

    override fun lookedAt(facing: Direction, eye: Vec3, hit: Vec3, hasABook: (Int) -> Boolean): ShelfLook {
        val from = inTheModel(facing, eye)
        val through = inTheModel(facing, hit)
        val looking = through.subtract(from).normalize()
        val met = mutableListOf<Pair<Double, ShelfLook>>()
        for ((position, pose) in atRest.withIndex()) {
            fun meets(middle: Vec3, onlyFromTheFront: Boolean) =
                alongTheLookTo(middle, pose.normal, ACROSS, pose.towardTheBack, halfAcross, halfAlong, from, looking, onlyFromTheFront)
            if (hasABook(position)) {
                meets(pose.top.add(pose.normal.scale(BOOK_OVER_THE_SHELF)), onlyFromTheFront = true)
                    ?.let { met += it to ShelfLook.AtBook(position, from.add(looking.scale(it))) }
            }
            meets(pose.middle, onlyFromTheFront = false)?.let { met += it to ShelfLook.AtShelf(position, from.add(looking.scale(it))) }
        }
        return nearestOf(met + frameMet(frame, from, looking), through)
    }

    /** From the front, above the axle lifts the front shelf and below it lowers it; from on top, the front lowers it. */
    override fun pushedBy(facing: Direction, eye: Vec3, point: Vec3): Int {
        val fromTheAxle = point.subtract(axle)
        val looking = point.subtract(inTheModel(facing, eye))
        // Turning the front toward the top moves a point at (y, z) from the axle along (-z, y).
        val turnsTheFrontUp = fromTheAxle.y * looking.z - fromTheAxle.z * looking.y
        return when {
            abs(turnsTheFrontUp) < EDGE_ON -> 0
            turnsTheFrontUp > 0.0 -> -1
            else -> 1
        }
    }

    companion object {
        private const val FULL_TURN = 2 * Math.PI
        private const val QUARTER_TURN_DEGREES = 90.0
        private val ACROSS = Vec3(1.0, 0.0, 0.0)
        private val TOWARD_THE_FRONT = Vec3(0.0, 0.0, -1.0)

        /** The lectern's slope, which only the front shelf takes. */
        private const val SLOPE = -22.5

        /**
         * Jonah's small wheel: four shelves on a cross of arms, the front one at the lectern's slope and the rest level
         * (Jonah, 2026-11-19), a pixel thick and 14 by 8.
         */
        val SMALL = WheelShelves(
            axle = Vec3(8.0, 9.0, 8.0),
            atRest = listOf(
                ShelfPose(Vec3(8.0, 9.5, 4.0), SLOPE, 1.0),
                ShelfPose(Vec3(8.0, 15.5, 8.0), 0.0, 1.0),
                ShelfPose(Vec3(8.0, 9.5, 12.0), 0.0, 1.0),
                ShelfPose(Vec3(8.0, 2.5, 8.0), 0.0, 1.0),
            ),
            halfAcross = 7.5,
            halfAlong = 4.5,
            frame = listOf(
                AABB(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
                AABB(-1.0, 0.0, 7.0, 0.0, 10.0, 9.0),
                AABB(16.0, 0.0, 7.0, 17.0, 10.0, 9.0),
                AABB(0.0, 2.0, 7.0, 1.0, 16.0, 9.0),
                AABB(15.0, 2.0, 7.0, 16.0, 16.0, 9.0),
                AABB(0.0, 8.0, 1.0, 1.0, 10.0, 15.0),
                AABB(15.0, 8.0, 1.0, 16.0, 10.0, 15.0),
            ),
        )

        /** Where the large wheel's shelves hang from the axle: the pin through each one's middle. */
        private const val LARGE_RADIUS = 13.5
        private const val LARGE_SHELVES = 8
        /** Through the middle of the block holding it, as the supports a player builds hold it (Jonah). */
        private val LARGE_AXLE = Vec3(16.0, 8.0, 8.0)

        private fun largeShelfAt(position: Int): ShelfPose {
            val round = position * FULL_TURN / LARGE_SHELVES
            val pin = LARGE_AXLE.add(0.0, LARGE_RADIUS * sin(round), -LARGE_RADIUS * cos(round))
            return ShelfPose(pin, if (position == 0) SLOPE else 0.0, LARGE_THICKNESS)
        }

        /** Thinner than the lectern's top it is, so the shelves pass each other (Jonah). */
        private const val LARGE_THICKNESS = 2.0

        /**
         * Jonah's large wheel: eight shelves, each a lectern's top cut to 16 by 10, 2 thick, hung level on a pin through
         * its middle, every eighth of a turn round the axle, the front one at the lectern's slope (Jonah, 2026-11-19).
         * Its model is built round the middle block of its right-hand column, and it stands on nothing: players build
         * its supports (Jonah).
         */
        val LARGE = WheelShelves(
            axle = LARGE_AXLE,
            atRest = (0 until LARGE_SHELVES).map(::largeShelfAt),
            halfAcross = 8.5,
            halfAlong = 5.5,
            frame = listOf(
                AABB(0.0, 6.0, 6.0, 32.0, 10.0, 10.0),
                AABB(5.0, -14.0, -14.0, 7.0, 30.0, 30.0),
                AABB(25.0, -14.0, -14.0, 27.0, 30.0, 30.0),
            ),
        )

    }
}

/**
 * Jonah's book carousel's eight shelves, on arms turning round an upright post, as the model rests them facing north.
 * Each shelf is a board at the tip of its arm, leaning back 15 degrees from upright, with a lip at its foot; a book
 * stands on the lip against the board, read from outside the carousel, and turns round with its arm. Position 0 is the
 * arm to the front, and each position after it an eighth of a turn on, the next to the right of whoever faces the front.
 */
data object CarouselShelves : ModelledShelves {
    override val positions: Int = 8

    /** The post the arms turn round. */
    val AXLE = Vec3(8.0, 8.0, 8.0)

    /** How far back from upright a board, and a book against it, leans. */
    const val LEANED_BACK_DEGREES = 15.0

    /** The front board as the model has it before it is tilted back, and the pivot it is tilted about. */
    private const val BOARD_OUTSIDE = -13.61261
    private const val BOARD_INSIDE = -11.61261
    private const val BOARD_FOOT = -4.59675
    private const val LIP_TOP = -3.59675
    private val BOARD_PIVOT = Vec3(MIDDLE, 0.75, MIDDLE)

    /** A book's height, from the lip up the board; a book's middle is half that up. */
    private const val BOOK_HEIGHT = 8.0

    /** The board's height, over its foot. */
    private const val BOARD_HEIGHT = 10.0

    private const val HALF_ACROSS = 8.5
    private const val HALF_ALONG = BOARD_HEIGHT / 2 + 0.5

    private const val EIGHTH_OF_A_TURN = 45.0

    /** The plinth and the post. */
    private val FRAME = listOf(AABB(0.0, 0.0, 0.0, 16.0, 2.0, 16.0), AABB(7.0, 2.0, 7.0, 9.0, 16.0, 9.0))

    /**
     * How a corner block of the carousel stands, as one to the east and south of the post has it: solid only behind its
     * diagonal arm's board, between the board and the post, so that the board and all beyond it can be walked through
     * (Jonah, 2026-11-20). A stair of pixel squares, each wholly nearer the post than the nearest of the board.
     */
    private val CORNER_TO_THE_SOUTH_EAST: List<AABB> by lazy {
        (0 until PIXELS.toInt()).mapNotNull { back ->
            val reach = (0 until PIXELS.toInt()).count { across -> isBehindTheBoard(across + 1.0, back + 1.0) }
            if (reach == 0) null else AABB(0.0, 0.0, back.toDouble(), reach.toDouble(), PIXELS, back + 1.0)
        }
    }

    /**
     * Whether a point [across] and [back] pixels into a corner block from its side nearest the post is nearer the post,
     * along the diagonal, than its board: the board's inside top edge, which leans in closest.
     */
    private fun isBehindTheBoard(across: Double, back: Double): Boolean {
        val alongTheDiagonal = (MIDDLE + across + MIDDLE + back) / sqrt(2.0)
        val boardsNearest = MIDDLE - tilted(Vec3(MIDDLE, BOARD_FOOT + BOARD_HEIGHT, BOARD_INSIDE)).z
        return alongTheDiagonal <= boardsNearest
    }

    /**
     * The shape of the carousel's block [towardX] and [towardZ] blocks from the one holding it, each -1, 0 or 1 in the
     * world's axes: a stair at a corner, or null for a whole block.
     */
    fun shapeOf(towardX: Int, towardZ: Int): VoxelShape? {
        if (towardX == 0 || towardZ == 0) return null
        return CORNERS.getValue(towardX to towardZ)
    }

    private val CORNERS: Map<Pair<Int, Int>, VoxelShape> = listOf(-1, 1).flatMap { x -> listOf(-1, 1).map { z -> x to z } }
        .associateWith { (x, z) ->
            CORNER_TO_THE_SOUTH_EAST
                .map { box ->
                    val west = if (x > 0) box.minX else PIXELS - box.maxX
                    val east = if (x > 0) box.maxX else PIXELS - box.minX
                    val north = if (z > 0) box.minZ else PIXELS - box.maxZ
                    val south = if (z > 0) box.maxZ else PIXELS - box.minZ
                    Block.box(west, box.minY, north, east, box.maxY, south)
                }
                .reduce(Shapes::or)
        }

    /** [point] in the front board's frame tilted back as the model tilts it. */
    private fun tilted(point: Vec3): Vec3 {
        val tilt = Math.toRadians(LEANED_BACK_DEGREES)
        val from = point.subtract(BOARD_PIVOT)
        return BOARD_PIVOT.add(0.0, from.y * cos(tilt) - from.z * sin(tilt), from.y * sin(tilt) + from.z * cos(tilt))
    }

    private fun tiltedDirection(direction: Vec3): Vec3 = tilted(BOARD_PIVOT.add(direction)).subtract(BOARD_PIVOT)

    /** [point] turned [position] eighths round the post, the way the positions count. */
    private fun turnedRound(point: Vec3, position: Double): Vec3 {
        val round = Math.toRadians(armTurnedDegrees(position))
        val from = point.subtract(AXLE)
        return AXLE.add(from.x * cos(round) + from.z * sin(round), from.y, -from.x * sin(round) + from.z * cos(round))
    }

    private fun turnedDirection(direction: Vec3, position: Double): Vec3 = turnedRound(AXLE.add(direction), position).subtract(AXLE)

    /** How far the arm at [position] round has turned from the front, about the post, in degrees. */
    fun armTurnedDegrees(position: Double): Double = position * EIGHTH_OF_A_TURN

    /** The middle of the board's face where a book stands against it, at [position] round, fractional while it turns. */
    fun bookAt(position: Double): Vec3 = turnedRound(tilted(Vec3(MIDDLE, LIP_TOP + BOOK_HEIGHT / 2, BOARD_OUTSIDE)), position)

    /** Out from the post, level, toward whoever reads the book at [position] round. */
    fun outwardAt(position: Double): Vec3 = turnedDirection(TOWARD_THE_FRONT, position)

    /** The middle of the board at [position] round, inside it. */
    fun boardMiddleAt(position: Double): Vec3 =
        turnedRound(tilted(Vec3(MIDDLE, BOARD_FOOT + BOARD_HEIGHT / 2, (BOARD_OUTSIDE + BOARD_INSIDE) / 2)), position)

    override fun bookSurface(facing: Direction, position: Int): BookSurface = BookSurface(
        toTheBlock(facing, bookAt(position.toDouble())),
        LEANED_BACK_DEGREES,
        turnedToFace(facing, outwardAt(position.toDouble())),
    )

    override fun lookedAt(facing: Direction, eye: Vec3, hit: Vec3, hasABook: (Int) -> Boolean): ShelfLook {
        val from = inTheModel(facing, eye)
        val through = inTheModel(facing, hit)
        val looking = through.subtract(from).normalize()
        val met = mutableListOf<Pair<Double, ShelfLook>>()
        for (position in 0 until positions) {
            val round = position.toDouble()
            val normal = turnedDirection(tiltedDirection(TOWARD_THE_FRONT), round)
            val across = turnedDirection(ACROSS, round)
            val up = turnedDirection(tiltedDirection(UP), round)
            fun meets(middle: Vec3, onlyFromTheFront: Boolean) = alongTheLookTo(
                middle, normal, across, up, HALF_ACROSS, HALF_ALONG, from, looking, onlyFromTheFront,
            )
            if (hasABook(position)) {
                meets(bookAt(round).add(normal.scale(BOOK_OVER_THE_SHELF)), onlyFromTheFront = true)
                    ?.let { met += it to ShelfLook.AtBook(position, from.add(looking.scale(it))) }
            }
            meets(boardMiddleAt(round), onlyFromTheFront = false)
                ?.let { met += it to ShelfLook.AtShelf(position, from.add(looking.scale(it))) }
        }
        return nearestOf(met + frameMet(FRAME, from, looking), through)
    }

    /** The way the look, pushed on at [point], would turn the carousel about its post. */
    override fun pushedBy(facing: Direction, eye: Vec3, point: Vec3): Int {
        val fromThePost = point.subtract(AXLE)
        val looking = point.subtract(inTheModel(facing, eye))
        // Turning on to the next position moves a point at (x, z) from the post along (z, -x).
        val turnsItOn = fromThePost.z * looking.x - fromThePost.x * looking.z
        return when {
            abs(turnsItOn) < EDGE_ON -> 0
            turnsItOn > 0.0 -> -1
            else -> 1
        }
    }

    private val TOWARD_THE_FRONT = Vec3(0.0, 0.0, -1.0)
    private val ACROSS = Vec3(1.0, 0.0, 0.0)
    private val UP = Vec3(0.0, 1.0, 0.0)
}

/**
 * The blocks a wheel bigger than a block stands in, [across] by [up] by [back], each the cell it is in, counted across
 * from the right as its front is faced (the model's x), up from the bottom and back from the front. Its model, and the
 * books, are kept in the block its model is built round ([holder]); the block placed is [placedAt], or
 * [placedAgainstTheRightAt] where it is placed against a block on the right of whoever faces its front, and the rest
 * stand round it.
 */
class WheelCells(
    val across: Int,
    val up: Int,
    val back: Int,
    private val holderAt: Cell,
    private val placedAt: Cell,
    private val placedAgainstTheRightAt: Cell = placedAt,
) {
    data class Cell(val across: Int, val up: Int, val back: Int)

    val count: Int get() = across * up * back

    val all: List<Cell> get() = (0 until count).map(::cellOf)

    val holder: Int get() = indexOf(holderAt)

    /** The block placed, for a wheel facing [facing] placed against the face [clickedFace] of the block beside it. */
    fun placedFor(facing: Direction, clickedFace: Direction): Int {
        // A face turned clockwise of the front, seen from above, is on the right of the block placed against it.
        val isAgainstTheRight = clickedFace == facing.clockWise
        return indexOf(if (isAgainstTheRight) placedAgainstTheRightAt else placedAt)
    }

    fun indexOf(cell: Cell): Int = cell.across + across * (cell.up + up * cell.back)

    fun cellOf(index: Int): Cell = Cell(index % across, (index / across) % up, index / (across * up))

    /** From the block holding the wheel to the cell [index], for a wheel facing [facing]. */
    fun offsetOf(index: Int, facing: Direction): BlockPos {
        val cell = cellOf(index)
        return BlockPos.ZERO
            .relative(facing.clockWise, cell.across - holderAt.across)
            .relative(Direction.UP, cell.up - holderAt.up)
            .relative(facing.opposite, cell.back - holderAt.back)
    }

    /** The cell beside the cell [index] toward [direction] in a wheel facing [facing], or null where that is outside it. */
    fun beside(index: Int, facing: Direction, direction: Direction): Int? {
        val cell = cellOf(index)
        val next = when (direction) {
            facing.clockWise -> cell.copy(across = cell.across + 1)
            facing.counterClockWise -> cell.copy(across = cell.across - 1)
            Direction.UP -> cell.copy(up = cell.up + 1)
            Direction.DOWN -> cell.copy(up = cell.up - 1)
            facing.opposite -> cell.copy(back = cell.back + 1)
            else -> cell.copy(back = cell.back - 1)
        }
        val isInside = next.across in 0 until across && next.up in 0 until up && next.back in 0 until back
        return if (isInside) indexOf(next) else null
    }

    companion object {
        /**
         * The large wheel: two across, three up and three back, its model built round the middle block of the
         * right-hand column. The block placed is an end of its axle, where the player was looking (Jonah): against a
         * support on the right, the right-hand end; otherwise the left-hand one, the wheel standing to its right.
         */
        val LARGE = WheelCells(2, 3, 3, holderAt = Cell(0, 1, 1), placedAt = Cell(1, 1, 1), placedAgainstTheRightAt = Cell(0, 1, 1))

        /** The carousel: three across and three back, one up, round the block placed, which holds it. */
        val CAROUSEL = WheelCells(3, 1, 3, holderAt = Cell(1, 0, 1), placedAt = Cell(1, 0, 1))
    }
}
