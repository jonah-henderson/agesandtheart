package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/** Which of a book lift frame's buttons a click on its front lands on, whichever way it faces. */
class LiftButtonLayoutCheck : FunSpec({

    /** The middle of each button on the front, as Jonah's model has it facing north: across from its west edge, and up. */
    val middles = mapOf(
        LiftButton.CONTINUOUS_UP to (3.0 to 14.0),
        LiftButton.CONTINUOUS_DOWN to (3.0 to 11.0),
        LiftButton.STEP_UP to (8.0 to 14.0),
        LiftButton.STEP_DOWN to (8.0 to 11.0),
        LiftButton.STOP to (13.0 to 12.0),
    )

    /** The point on the front of a frame facing [facing], [across] and [up] pixels in as modelled facing north. */
    fun onTheFront(facing: Direction, across: Double, up: Double): Vec3 {
        val east = facing.clockWise
        val fromTheMiddle = (across - 8.0) / 16.0
        return Vec3(
            0.5 + fromTheMiddle * east.stepX + 0.5 * facing.stepX,
            up / 16.0,
            0.5 + fromTheMiddle * east.stepZ + 0.5 * facing.stepZ,
        )
    }

    test("each button is found at its middle, whichever way the frame faces") {
        for (facing in Direction.Plane.HORIZONTAL) {
            for ((button, middle) in middles) {
                val (across, up) = middle
                val point = onTheFront(facing, across, up)
                val found = LiftButtonLayout.buttonAt(LiftButtonLayout.acrossOf(facing, point), point.y * 16.0)
                check(found == button) { "facing $facing, $button's middle is found as $found" }
            }
        }
    }

    test("below and above the buttons is none") {
        check(LiftButtonLayout.buttonAt(8.0, 4.0) == null)
        check(LiftButtonLayout.buttonAt(8.0, 15.9) == null)
    }

    test("the button pressed for a column is its own up or down") {
        check(LiftButton.pressedFor(1, isContinuous = true) == LiftButton.CONTINUOUS_UP)
        check(LiftButton.pressedFor(-1, isContinuous = false) == LiftButton.STEP_DOWN)
        check(LiftButton.pressedFor(0, isContinuous = true) == LiftButton.STOP)
    }

    test("a book on the platform lies over the shelf's middle") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val middle = LiftPlatform.bookMiddle(facing)
            val fromTheMiddle = middle.subtract(0.5, 0.0, 0.5)
            check(fromTheMiddle.horizontalDistance() < 2.0 / 16.0) { "facing $facing, the book's middle is $middle" }
            check(middle.y in 0.25..0.5) { "facing $facing, the book's middle is ${middle.y} up" }
        }
    }
})
