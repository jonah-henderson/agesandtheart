package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos

/**
 * How finders sharing a wall keep their carriages apart: each space a carriage passes is held while any part of it is
 * there, and two carriages meet only where their holds on one space overlap in time.
 */
class FinderTrafficCheck : FunSpec({

    fun spaceOf(cell: WallCell): BlockPos = BlockPos(cell.across, cell.up, 0)

    fun along(vararg cells: WallCell): FramePath = FramePath(cells.map(FramePath::pointOf))

    test("a carriage in the middle of a space is in that one, and between two is in both") {
        check(Timetable.cellsUnder(WallPoint(2.0, 3.0)) == listOf(WallCell(2, 3))) { "a middle is one space" }
        check(Timetable.cellsUnder(WallPoint(2.4, 3.0)).toSet() == setOf(WallCell(2, 3), WallCell(3, 3))) { "between is both" }
    }

    test("a trip holds every space it passes, each overlapping the next, and where it stops for good") {
        val way = along(WallCell(0, 0), WallCell(4, 0))
        val held = Timetable(100L, ::spaceOf).stand(WallCell(0, 0), 10).travel(way).standForGood(WallCell(4, 0)).reservations
        for (across in 0..4) {
            check(held.any { it.space == BlockPos(across, 0, 0) }) { "space $across is not held: $held" }
        }
        val onTheWay = held.drop(1).dropLast(1)
        check(onTheWay.zipWithNext().all { (a, b) -> b.from <= a.until }) { "a gap between spaces: $onTheWay" }
        check(held.last().until == Reservation.FOR_GOOD) { "the end is not held for good" }
    }

    test("two carriages crossing the same space meet only if they are in it at once") {
        val across = along(WallCell(0, 0), WallCell(4, 0))
        val up = along(WallCell(2, -2), WallCell(2, 2))
        val first = Timetable(0L, ::spaceOf).travel(across).reservations
        val atOnce = Timetable(0L, ::spaceOf).travel(up).reservations
        val later = Timetable(Timetable.ticksAlong(across).toLong() + 5, ::spaceOf).travel(up).reservations
        check(atOnce.any { mine -> first.any(mine::clashesWith) }) { "crossing at once should meet" }
        check(later.none { mine -> first.any(mine::clashesWith) }) { "crossing once the other has passed should not meet" }
    }
})
