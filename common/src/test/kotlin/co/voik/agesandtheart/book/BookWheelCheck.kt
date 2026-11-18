package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction

/** Which face of a book wheel shows which tray, and how a client turns its drawing after the server. */
class BookWheelCheck : FunSpec({

    test("each face shows a tray except the ends of the axle") {
        val across = WheelFace.entries.associateWith { WheelAxle.ACROSS.positionShownBy(it) }
        check(across == mapOf(
            WheelFace.FRONT to 0, WheelFace.TOP to 1, WheelFace.BACK to 2, WheelFace.BOTTOM to 3,
            WheelFace.LEFT to null, WheelFace.RIGHT to null,
        )) { "across: $across" }
        val upright = WheelFace.entries.associateWith { WheelAxle.UPRIGHT.positionShownBy(it) }
        check(upright == mapOf(
            WheelFace.FRONT to 0, WheelFace.RIGHT to 1, WheelFace.BACK to 2, WheelFace.LEFT to 3,
            WheelFace.TOP to null, WheelFace.BOTTOM to null,
        )) { "upright: $upright" }
    }

    test("a wheel's right is where a large wheel's second block goes") {
        for (facing in Direction.Plane.HORIZONTAL) {
            val second = WheelForm.LARGE.towardTheSecond(facing)!!
            check(WheelFace.of(second, facing) == WheelFace.RIGHT) { "facing $facing, the second block is ${WheelFace.of(second, facing)}" }
            check(WheelFace.of(facing, facing) == WheelFace.FRONT)
        }
    }

    test("a client turns the short way round, and a half turn the way the wheel last turned") {
        fun settledAfter(from: Int, to: Int, lastTurn: Int): Float {
            val turning = WheelTurning()
            turning.tick(from, 1)
            repeat(TICKS_TO_SETTLE) { turning.tick(to, lastTurn) }
            return turning.shownAt(1.0f, to)
        }
        check(settledAfter(0, 3, 1) == -1.0f) { "from 0 to 3 should go back one" }
        check(settledAfter(3, 0, 1) == 4.0f) { "from 3 to 0 should go on one, to 4" }
        check(settledAfter(0, 2, 1) == 2.0f) { "a half turn on" }
        check(settledAfter(0, 2, -1) == -2.0f) { "a half turn back" }
    }
}) {
    private companion object {
        const val TICKS_TO_SETTLE = 40
    }
}
