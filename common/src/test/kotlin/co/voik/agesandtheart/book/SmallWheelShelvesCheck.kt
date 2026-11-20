package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Where the small book wheel carries its shelves round, and which shelf a look at it reaches. */
class SmallWheelShelvesCheck : FunSpec({

    /** Jonah's model's shelves, resting: front, top, back and bottom, only the front one sloping. */
    val atRest = listOf(
        Vec3(8.0, 9.5, 4.0) to -22.5,
        Vec3(8.0, 15.5, 8.0) to 0.0,
        Vec3(8.0, 9.5, 12.0) to 0.0,
        Vec3(8.0, 2.5, 8.0) to 0.0,
    )

    test("at each resting place a shelf is where the model rests it, and a whole turn round brings it back") {
        for ((position, rest) in atRest.withIndex()) {
            val (middle, tilt) = rest
            for (turns in -1..1) {
                val pose = SmallWheelShelves.poseAt(position + turns * 4.0)
                check(pose.middle.distanceTo(middle) < 1e-9 && abs(pose.tiltDegrees - tilt) < 1e-9) { "at $position: $pose" }
            }
        }
    }

    test("a shelf moves smoothly round, never jumping") {
        val steps = 400
        for (step in 0 until steps) {
            val here = SmallWheelShelves.poseAt(step * 4.0 / steps)
            val next = SmallWheelShelves.poseAt((step + 1) * 4.0 / steps)
            check(here.middle.distanceTo(next.middle) < 0.25) { "from step $step it moves ${here.middle.distanceTo(next.middle)} pixels" }
            check(abs(here.tiltDegrees - next.tiltDegrees) < 1.0) { "from step $step it tilts from ${here.tiltDegrees} to ${next.tiltDegrees}" }
        }
    }

    test("halfway from the front to the top, a shelf has risen along its arm, tilted halfway") {
        val halfway = SmallWheelShelves.poseAt(0.5)
        check(halfway.middle.y > 9.5 && halfway.middle.z < 8.0) { "halfway it is at ${halfway.middle}" }
        check(abs(halfway.tiltDegrees + 11.25) < 1e-9) { "halfway it tilts ${halfway.tiltDegrees}" }
    }

    /** A look at a wheel facing [facing], from [eye] to [hit], given as for one facing north, turned to face [facing]. */
    fun lookedAt(facing: Direction, eye: Vec3, hit: Vec3, books: Set<Int>): SmallWheelShelves.Look {
        fun turned(point: Vec3) = SmallWheelShelves.toTheBlock(facing, point.scale(16.0))
        return SmallWheelShelves.lookedAt(facing, turned(eye), turned(hit)) { it in books }
    }

    val everyShelf = setOf(SmallWheelShelves.FRONT, SmallWheelShelves.TOP, SmallWheelShelves.BACK, SmallWheelShelves.BOTTOM)

    test("a look meets the book on the shelf it reaches: the front's, the top's from above, the back's from behind, and the bottom's at a low angle") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val looks = mapOf(
                SmallWheelShelves.FRONT to lookedAt(facing, Vec3(0.5, 1.4, -1.2), Vec3(0.5, 0.8, 0.0), everyShelf),
                SmallWheelShelves.TOP to lookedAt(facing, Vec3(0.5, 2.6, -0.5), Vec3(0.5, 1.0, 0.4), everyShelf),
                SmallWheelShelves.BACK to lookedAt(facing, Vec3(0.5, 1.6, 2.2), Vec3(0.5, 0.8, 1.0), everyShelf),
                SmallWheelShelves.BOTTOM to lookedAt(facing, Vec3(0.5, 0.556, -0.8), Vec3(0.5, 0.406, 0.0), everyShelf),
            )
            for ((position, look) in looks) {
                check(look is SmallWheelShelves.Look.AtBook && look.position == position) { "facing $facing, the look for $position met $look" }
            }
        }
    }

    test("with no book there, the same looks meet the shelves") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val atTheFront = lookedAt(facing, Vec3(0.5, 1.4, -1.2), Vec3(0.5, 0.8, 0.0), emptySet())
            val low = lookedAt(facing, Vec3(0.5, 0.806, -0.8), Vec3(0.5, 0.406, 0.0), emptySet())
            check(atTheFront is SmallWheelShelves.Look.AtShelf && atTheFront.position == SmallWheelShelves.FRONT) { "facing $facing: $atTheFront" }
            check(low is SmallWheelShelves.Look.AtShelf && low.position == SmallWheelShelves.BOTTOM) { "facing $facing: $low" }
        }
    }

    test("a look from beneath a shelf meets the shelf under its book, not the book") {
        val look = lookedAt(Direction.NORTH, Vec3(0.5, 0.0625, -0.6), Vec3(0.5, 0.4375, 0.0), everyShelf)
        check(look is SmallWheelShelves.Look.AtShelf && look.position == SmallWheelShelves.FRONT) { "$look" }
    }

    test("a push is away from whoever pushes: from the front the top half lifts the front shelf, the bottom half sinks it, and from behind the other way") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val inFront = SmallWheelShelves.toTheBlock(facing, Vec3(8.0, 24.0, -24.0))
            val behind = SmallWheelShelves.toTheBlock(facing, Vec3(8.0, 24.0, 40.0))
            val high = Vec3(8.0, 14.0, 8.0)
            val low = Vec3(8.0, 4.0, 8.0)
            check(SmallWheelShelves.pushedBy(facing, inFront, high) == -1) { "facing $facing, from the front, high" }
            check(SmallWheelShelves.pushedBy(facing, inFront, low) == 1) { "facing $facing, from the front, low" }
            check(SmallWheelShelves.pushedBy(facing, behind, high) == 1) { "facing $facing, from behind, high" }
            check(SmallWheelShelves.pushedBy(facing, behind, low) == -1) { "facing $facing, from behind, low" }
        }
    }

    test("a book lies on the front shelf at a lectern's slope and on the back one flat, over the middle of each") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val front = SmallWheelShelves.bookSurface(facing, SmallWheelShelves.FRONT)
            val back = SmallWheelShelves.bookSurface(facing, SmallWheelShelves.BACK)
            check(abs(front.leanedBackDegrees - LecternBookPlane.TILT_DEGREES) < 1e-9) { "facing $facing, the front book leans ${front.leanedBackDegrees}" }
            check(abs(back.leanedBackDegrees - 90.0) < 1e-9) { "facing $facing, the back book leans ${back.leanedBackDegrees}" }
            check(front.top.distanceTo(SmallWheelShelves.toTheBlock(facing, Vec3(8.0, 9.96, 3.81))) < 0.01) { "facing $facing, the front book is at ${front.top}" }
            check(back.top.distanceTo(SmallWheelShelves.toTheBlock(facing, Vec3(8.0, 10.0, 12.0))) < 1e-9) { "facing $facing, the back book is at ${back.top}" }
        }
    }

})
