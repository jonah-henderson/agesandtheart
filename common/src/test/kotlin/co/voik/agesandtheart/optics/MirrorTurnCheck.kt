package co.voik.agesandtheart.optics

import co.voik.agesandtheart.optics.ReflectorBlock.Mirror
import io.kotest.core.spec.style.FunSpec

/** How a reflector, splitter or merger's mirror turns when used (`optics/ReflectorBlock`). */
class MirrorTurnCheck : FunSpec({
    fun turnedTimes(mirror: Mirror, times: Int, back: Boolean = false) =
        generateSequence(mirror) { it.turned(back) }.take(times + 1).toList()

    test("a level mirror turns a quarter about the vertical each use, round four, and sneaking turns it back") {
        val level = Mirror.NORTH_EAST
        check(turnedTimes(level, 4).map(Mirror::getSerializedName) == listOf("north_east", "east_south", "south_west", "west_north", "north_east")) {
            "${turnedTimes(level, 4)}"
        }
        for (mirror in turnedTimes(level, 3)) check(mirror.turned(back = false).turned(back = true) == mirror) { "$mirror did not turn back" }
    }

    test("a vertical mirror turns about its level pivot, up, across and down, and never out of its plane") {
        for (placed in listOf(Mirror.NORTH_UP, Mirror.EAST_DOWN)) {
            val turns = turnedTimes(placed, 4)
            check(turns.last() == placed) { "$placed came round to ${turns.last()}" }
            check(turns.all { it.pivot == placed.pivot }) { "$placed left its pivot: $turns" }
            check(turns.all { it.first.axis.isVertical != it.second.axis.isVertical }) { "$placed left its plane: $turns" }
        }
    }

    test("no use takes any form out of its plane") {
        fun isLevel(mirror: Mirror) = mirror.first.axis.isHorizontal && mirror.second.axis.isHorizontal
        for (start in Mirror.entries) {
            for (used in listOf(start.turned(back = false), start.turned(back = true), start.swapped())) {
                check(isLevel(used) == isLevel(start) && used.pivot == start.pivot) { "$start became $used" }
            }
        }
    }

    test("swapping keeps the plane and moves the straight face") {
        check(Mirror.NORTH_EAST.swapped() == Mirror.EAST_NORTH)
        check(Mirror.NORTH_EAST.swapped().pivot == Mirror.NORTH_EAST.pivot)
    }
})
