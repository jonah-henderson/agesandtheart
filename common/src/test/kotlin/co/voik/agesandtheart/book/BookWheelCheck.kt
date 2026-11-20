package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec

/** How a client turns a book wheel's drawing after the server. */
class BookWheelCheck : FunSpec({

    test("a client turns the short way round, and a half turn the way the wheel last turned") {
        fun settledAfter(from: Int, to: Int, lastTurn: Int, trays: Int = 4): Float {
            val turning = WheelTurning()
            turning.tick(from, 1, trays)
            repeat(TICKS_TO_SETTLE) { turning.tick(to, lastTurn, trays) }
            return turning.shownAt(1.0f, to)
        }
        check(settledAfter(0, 3, 1) == -1.0f) { "from 0 to 3 should go back one" }
        check(settledAfter(3, 0, 1) == 4.0f) { "from 3 to 0 should go on one, to 4" }
        check(settledAfter(0, 2, 1) == 2.0f) { "a half turn on" }
        check(settledAfter(0, 2, -1) == -2.0f) { "a half turn back" }
        check(settledAfter(0, 7, 1, trays = 8) == -1.0f) { "on eight trays, from 0 to 7 should go back one" }
        check(settledAfter(0, 4, -1, trays = 8) == -4.0f) { "on eight trays, a half turn back" }
    }
}) {
    private companion object {
        const val TICKS_TO_SETTLE = 60
    }
}
