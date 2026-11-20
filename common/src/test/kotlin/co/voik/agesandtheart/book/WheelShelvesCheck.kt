package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** The small wheel's positions, round from the front. */
private const val FRONT = 0
private const val TOP = 1
private const val BACK = 2
private const val BOTTOM = 3

/** Where the modelled book wheels carry their shelves round, which shelf a look at them reaches, and the large one's blocks. */
class WheelShelvesCheck : FunSpec({

    val small = WheelShelves.SMALL
    val large = WheelShelves.LARGE

    /** Jonah's model's shelves, resting: front, top, back and bottom, only the front one sloping. */
    val atRest = listOf(
        Vec3(8.0, 9.5, 4.0) to -22.5,
        Vec3(8.0, 15.5, 8.0) to 0.0,
        Vec3(8.0, 9.5, 12.0) to 0.0,
        Vec3(8.0, 2.5, 8.0) to 0.0,
    )

    test("at each resting place a small wheel's shelf is where the model rests it, and a whole turn round brings it back") {
        for ((position, rest) in atRest.withIndex()) {
            val (middle, tilt) = rest
            for (turns in -1..1) {
                val pose = small.poseAt(position + turns * 4.0)
                check(pose.middle.distanceTo(middle) < 1e-9 && abs(pose.tiltDegrees - tilt) < 1e-9) { "at $position: $pose" }
            }
        }
    }

    test("a shelf moves smoothly round, never jumping") {
        val steps = 400
        for (step in 0 until steps) {
            val here = small.poseAt(step * 4.0 / steps)
            val next = small.poseAt((step + 1) * 4.0 / steps)
            check(here.middle.distanceTo(next.middle) < 0.25) { "from step $step it moves ${here.middle.distanceTo(next.middle)} pixels" }
            check(abs(here.tiltDegrees - next.tiltDegrees) < 1.0) { "from step $step it tilts from ${here.tiltDegrees} to ${next.tiltDegrees}" }
        }
    }

    test("halfway from the front to the top, a shelf has risen along its arm, tilted halfway") {
        val halfway = small.poseAt(0.5)
        check(halfway.middle.y > 9.5 && halfway.middle.z < 8.0) { "halfway it is at ${halfway.middle}" }
        check(abs(halfway.tiltDegrees + 11.25) < 1e-9) { "halfway it tilts ${halfway.tiltDegrees}" }
    }

    /** A look at a wheel facing [facing], from [eye] to [hit], given as for one facing north, turned to face [facing]. */
    fun lookedAt(facing: Direction, eye: Vec3, hit: Vec3, books: Set<Int>, wheel: ModelledShelves = small): ShelfLook {
        fun turned(point: Vec3) = ModelledShelves.toTheBlock(facing, point.scale(16.0))
        return wheel.lookedAt(facing, turned(eye), turned(hit)) { it in books }
    }

    val everyShelf = setOf(FRONT, TOP, BACK, BOTTOM)

    test("a look meets the book on the shelf it reaches: the front's, the top's from above, the back's from behind, and the bottom's at a low angle") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val looks = mapOf(
                FRONT to lookedAt(facing, Vec3(0.5, 1.4, -1.2), Vec3(0.5, 0.8, 0.0), everyShelf),
                TOP to lookedAt(facing, Vec3(0.5, 2.6, -0.5), Vec3(0.5, 1.0, 0.4), everyShelf),
                BACK to lookedAt(facing, Vec3(0.5, 1.6, 2.2), Vec3(0.5, 0.8, 1.0), everyShelf),
                BOTTOM to lookedAt(facing, Vec3(0.5, 0.556, -0.8), Vec3(0.5, 0.406, 0.0), everyShelf),
            )
            for ((position, look) in looks) {
                check(look is ShelfLook.AtBook && look.position == position) { "facing $facing, the look for $position met $look" }
            }
        }
    }

    test("with no book there, the same looks meet the shelves") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val atTheFront = lookedAt(facing, Vec3(0.5, 1.4, -1.2), Vec3(0.5, 0.8, 0.0), emptySet())
            val low = lookedAt(facing, Vec3(0.5, 0.806, -0.8), Vec3(0.5, 0.406, 0.0), emptySet())
            check(atTheFront is ShelfLook.AtShelf && atTheFront.position == FRONT) { "facing $facing: $atTheFront" }
            check(low is ShelfLook.AtShelf && low.position == BOTTOM) { "facing $facing: $low" }
        }
    }

    test("a look from beneath a shelf meets the shelf under its book, not the book") {
        val look = lookedAt(Direction.NORTH, Vec3(0.5, 0.0625, -0.6), Vec3(0.5, 0.4375, 0.0), everyShelf)
        check(look is ShelfLook.AtShelf && look.position == FRONT) { "$look" }
    }

    test("a push is away from whoever pushes: from the front the top half lifts the front shelf, the bottom half sinks it, and from behind the other way") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val inFront = ModelledShelves.toTheBlock(facing, Vec3(8.0, 24.0, -24.0))
            val behind = ModelledShelves.toTheBlock(facing, Vec3(8.0, 24.0, 40.0))
            val high = Vec3(8.0, 14.0, 8.0)
            val low = Vec3(8.0, 4.0, 8.0)
            check(small.pushedBy(facing, inFront, high) == -1) { "facing $facing, from the front, high" }
            check(small.pushedBy(facing, inFront, low) == 1) { "facing $facing, from the front, low" }
            check(small.pushedBy(facing, behind, high) == 1) { "facing $facing, from behind, high" }
            check(small.pushedBy(facing, behind, low) == -1) { "facing $facing, from behind, low" }
        }
    }

    test("a book lies on the front shelf at a lectern's slope and on the back one flat, over the middle of each") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val front = small.bookSurface(facing, FRONT)
            val back = small.bookSurface(facing, BACK)
            check(abs(front.leanedBackDegrees - LecternBookPlane.TILT_DEGREES) < 1e-9) { "facing $facing, the front book leans ${front.leanedBackDegrees}" }
            check(abs(back.leanedBackDegrees - 90.0) < 1e-9) { "facing $facing, the back book leans ${back.leanedBackDegrees}" }
            check(front.top.distanceTo(ModelledShelves.toTheBlock(facing, Vec3(8.0, 9.96, 3.81))) < 0.01) { "facing $facing, the front book is at ${front.top}" }
            check(back.top.distanceTo(ModelledShelves.toTheBlock(facing, Vec3(8.0, 10.0, 12.0))) < 1e-9) { "facing $facing, the back book is at ${back.top}" }
        }
    }


    test("the large wheel's shelves hang every eighth of a turn round its axle, level, but for the front one's lectern slope") {
        for (position in 0 until 8) {
            val pose = large.poseAt(position.toDouble())
            val fromTheAxle = pose.middle.subtract(large.axle)
            check(abs(fromTheAxle.length() - 13.5) < 1e-9 && fromTheAxle.x == 0.0) { "at $position the shelf hangs at $fromTheAxle" }
            val round = Math.toDegrees(kotlin.math.atan2(fromTheAxle.y, -fromTheAxle.z))
            check(abs(Math.floorMod(round.toInt() + 360, 360) - position * 45) <= 1) { "at $position the shelf is $round degrees round" }
            val tilt = if (position == 0) -22.5 else 0.0
            check(abs(pose.tiltDegrees - tilt) < 1e-9) { "at $position the shelf tilts ${pose.tiltDegrees}" }
        }
        check(large.positions == 8)
    }

    test("between resting places a large wheel's shelf stays on its circle") {
        for (step in 0 until 80) {
            val pose = large.poseAt(step / 10.0)
            check(abs(pose.middle.subtract(large.axle).length() - 13.5) < 1e-9) { "at ${step / 10.0} the shelf is off its circle" }
        }
    }

    test("a look at the large wheel's front shelf from in front of it meets its book, and a push from the front's top half lifts it") {
        for (facing in Direction.Plane.HORIZONTAL) {
            // In the model's blocks: the front shelf's middle is 16 pixels across, 9 up and 5.5 in front of the holder.
            val eye = Vec3(1.0, 1.6, -2.0)
            val book = lookedAt(facing, eye, Vec3(1.0, 0.7, -0.75), setOf(0), large)
            check(book is ShelfLook.AtBook && book.position == 0) { "facing $facing, the look met $book" }
            val inFront = ModelledShelves.toTheBlock(facing, eye.scale(16.0))
            check(large.pushedBy(facing, inFront, Vec3(16.0, 20.0, 8.0)) == -1) { "facing $facing, from the front, high" }
        }
    }

    /** Each wheel's blocks: all counted once, each beside its neighbours, the holder where the blockstate and loot name it. */
    fun checkTheBlocksOf(cells: WheelCells, holder: Int) {
        check((0 until cells.count).map { cells.indexOf(cells.cellOf(it)) } == (0 until cells.count).toList())
        check(cells.holder == holder) { "the block holding it is cell ${cells.holder}, where its loot table and blockstate name $holder" }
        for (facing in Direction.Plane.HORIZONTAL) {
            val offsets = (0 until cells.count).map { cells.offsetOf(it, facing) }
            check(offsets.toSet().size == cells.count) { "facing $facing, two cells share a block" }
            for (index in 0 until cells.count) for (direction in Direction.entries) {
                val beside = cells.beside(index, facing, direction) ?: continue
                val step = cells.offsetOf(beside, facing).subtract(cells.offsetOf(index, facing))
                check(step == direction.unitVec3i) { "facing $facing, cell $index toward $direction is $beside, $step away" }
            }
        }
    }

    test("the large wheel's blocks are two across, three up and three back, placed by an end of its axle") {
        checkTheBlocksOf(WheelCells.LARGE, holder = 8)
        for (facing in Direction.Plane.HORIZONTAL) {
            // An end of the axle: the left-hand one, the wheel standing to its right; against a support on the right,
            // the right-hand one, which holds it.
            val placed = WheelCells.LARGE.offsetOf(WheelCells.LARGE.placedFor(facing, Direction.UP), facing)
            check(placed == BlockPos.ZERO.relative(facing.clockWise)) { "facing $facing, the placed block is $placed from the holder" }
            val againstTheLeft = WheelCells.LARGE.placedFor(facing, facing.counterClockWise)
            val againstTheRight = WheelCells.LARGE.placedFor(facing, facing.clockWise)
            check(againstTheLeft == WheelCells.LARGE.placedFor(facing, Direction.UP)) { "facing $facing, placed against the left" }
            check(againstTheRight == WheelCells.LARGE.holder) { "facing $facing, placed against the right" }
        }
    }

    test("the carousel's blocks are three across and three back round the block placed, which holds it") {
        checkTheBlocksOf(WheelCells.CAROUSEL, holder = 4)
        for (facing in Direction.Plane.HORIZONTAL) for (face in Direction.entries) {
            check(WheelCells.CAROUSEL.placedFor(facing, face) == WheelCells.CAROUSEL.holder) { "facing $facing, against $face" }
        }
    }

    test("a carousel's book stands against its board, leaning back 15 degrees, read from outside, every eighth of a turn round") {
        for (position in 0 until 8) {
            val surface = CarouselShelves.bookSurface(Direction.NORTH, position)
            val outward = CarouselShelves.outwardAt(position.toDouble())
            val fromThePost = surface.top.scale(16.0).subtract(CarouselShelves.AXLE)
            val horizontally = Vec3(fromThePost.x, 0.0, fromThePost.z)
            check(abs(horizontally.normalize().dot(outward) - 1.0) < 1e-6) { "at $position the book is not out along its arm: $horizontally, $outward" }
            check(horizontally.length() in 19.0..22.0) { "at $position the book stands ${horizontally.length()} from the post" }
            check(abs(surface.leanedBackDegrees - 15.0) < 1e-9)
        }
        // The front book faces the front, and the next one, to the right of whoever faces the front, faces that way.
        check(CarouselShelves.outwardAt(0.0).distanceTo(Vec3(0.0, 0.0, -1.0)) < 1e-9)
        check(CarouselShelves.outwardAt(2.0).distanceTo(Vec3(-1.0, 0.0, 0.0)) < 1e-9)
    }

    test("a look at the carousel's front book meets it, and a push across the front turns it the way it is pushed") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val book = CarouselShelves.bookAt(0.0)
            val eye = book.add(0.0, 8.0, -24.0)
            val looked = lookedAt(facing, eye.scale(1.0 / 16), book.scale(1.0 / 16), setOf(0), CarouselShelves)
            check(looked is ShelfLook.AtBook && looked.position == 0) { "facing $facing, the look met $looked" }
            // Positions count on toward the model's west, the right of whoever faces the front. Pushed from that side,
            // the front board goes east, back round; from the other side, west, on round.
            val fromTheWest = ModelledShelves.toTheBlock(facing, Vec3(-16.0, 8.0, -24.0))
            val fromTheEast = ModelledShelves.toTheBlock(facing, Vec3(32.0, 8.0, -24.0))
            check(CarouselShelves.pushedBy(facing, fromTheWest, Vec3(4.0, 8.0, -12.0)) == 1) { "facing $facing, pushed east" }
            check(CarouselShelves.pushedBy(facing, fromTheEast, Vec3(12.0, 8.0, -12.0)) == -1) { "facing $facing, pushed west" }
        }
    }

    test("a push from on top, on the front of the top, sinks the front shelf on either wheel; a look straight at the axle pushes nothing") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val aboveTheFront = ModelledShelves.toTheBlock(facing, Vec3(8.0, 40.0, 2.0))
            check(small.pushedBy(facing, aboveTheFront, Vec3(8.0, 16.0, 2.0)) == 1) { "facing $facing, the small wheel from on top" }
            val aboveTheLargeFront = ModelledShelves.toTheBlock(facing, Vec3(16.0, 56.0, -8.0))
            check(large.pushedBy(facing, aboveTheLargeFront, Vec3(16.0, 31.0, -8.0)) == 1) { "facing $facing, the large wheel from on top" }
            val levelWithTheAxle = ModelledShelves.toTheBlock(facing, Vec3(8.0, 9.0, -24.0))
            check(small.pushedBy(facing, levelWithTheAxle, Vec3(8.0, 9.0, 0.0)) == 0) { "facing $facing, a look at the axle" }
        }
    }

    test("the large wheel's neighbouring shelves, 10 deep, never pass through each other through a whole turn") {
        val depth = 10.0
        for (step in 0 until 800) {
            val front = step / 100.0
            for (tray in 0 until 8) {
                val here = large.poseAt(tray - front)
                val next = large.poseAt(tray + 1 - front)
                val apart = next.middle.subtract(here.middle)
                val isSideBySide = abs(apart.y) < here.thickness
                check(!isSideBySide || abs(apart.z) >= depth) { "at $front, trays $tray and ${tray + 1} meet: $apart" }
            }
        }
    }

    test("a carousel's corner blocks are solid only behind their diagonal board: by the post, and nowhere the board is") {
        fun isSolidAt(towardX: Int, towardZ: Int, x: Double, z: Double): Boolean {
            val shape = CarouselShelves.shapeOf(towardX, towardZ) ?: return true
            return shape.toAabbs().any { it.contains(Vec3(x / 16, 0.5, z / 16)) }
        }
        check(CarouselShelves.shapeOf(1, 0) == null && CarouselShelves.shapeOf(0, -1) == null) { "an edge block is whole" }
        for (towardX in listOf(-1, 1)) for (towardZ in listOf(-1, 1)) {
            // In the corner's own pixels, measured from its side nearest the post.
            fun x(fromThePost: Double) = if (towardX > 0) fromThePost else 16.0 - fromThePost
            fun z(fromThePost: Double) = if (towardZ > 0) fromThePost else 16.0 - fromThePost
            check(isSolidAt(towardX, towardZ, x(1.0), z(1.0)) && isSolidAt(towardX, towardZ, x(3.5), z(3.5))) { "corner $towardX, $towardZ is open by the post" }
            // The diagonal board's middle stands about six pixels in either way, and its ends further.
            for ((across, back) in listOf(6.0 to 6.0, 2.0 to 13.0, 13.0 to 2.0, 15.0 to 15.0)) {
                check(!isSolidAt(towardX, towardZ, x(across), z(back))) { "corner $towardX, $towardZ is solid at $across, $back" }
            }
        }
    }
})
