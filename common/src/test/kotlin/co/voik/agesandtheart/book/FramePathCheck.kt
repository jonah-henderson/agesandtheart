package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** How a book finder's frame crosses its wall: a curve where it fits, leaving upright and arriving level, and a walk where not. */
class FramePathCheck : FunSpec({

    fun block(across: IntRange, up: IntRange): Set<WallCell> =
        across.flatMap { a -> up.map { u -> WallCell(a, u) } }.toSet()

    test("on an open wall it takes the curve, leaving up the wall and arriving along it") {
        val path = FramePath.between(WallCell(0, 0), WallCell(6, 4), block(-2..8, -2..6))!!
        val (first, second) = path.points.take(2)
        val (beforeLast, last) = path.points.takeLast(2)
        check(abs(second.up - first.up) > abs(second.across - first.across)) { "it should set off upright: $first to $second" }
        check(abs(last.across - beforeLast.across) > abs(last.up - beforeLast.up)) { "it should arrive along the wall: $beforeLast to $last" }
        check(last == WallPoint(6.0, 4.0) && first == WallPoint(0.0, 0.0))
    }

    test("downward and leftward too") {
        val path = FramePath.between(WallCell(0, 0), WallCell(-5, -3), block(-6..1, -4..1))!!
        check(path.points.last() == WallPoint(-5.0, -3.0))
        val (beforeLast, last) = path.points.takeLast(2)
        check(abs(last.across - beforeLast.across) > abs(last.up - beforeLast.up))
    }

    test("straight along or straight up where there is nothing to curve") {
        val along = FramePath.between(WallCell(0, 0), WallCell(4, 0), block(0..4, 0..0))!!
        check(along.points.all { it.up == 0.0 })
        val up = FramePath.between(WallCell(0, 0), WallCell(0, 3), block(0..0, 0..3))!!
        check(up.points.all { it.across == 0.0 })
    }

    test("where the curve would leave the wall it walks, and arrives along the wall where it can") {
        // An L of shelves: along the bottom, then up the far end. The curve would cut the corner over empty air.
        val wall = block(0..5, 0..0) + block(5..5, 0..4) + block(4..4, 4..4)
        val path = FramePath.between(WallCell(0, 0), WallCell(4, 4), wall)!!
        check(path.points.all { FramePath.cellUnder(it) in wall }) { "it left the wall: ${path.points}" }
        val (beforeLast, last) = path.points.takeLast(2)
        check(last.up == beforeLast.up) { "it should arrive along the wall: $beforeLast to $last" }
    }

    test("no way at all where the wall does not join") {
        check(FramePath.between(WallCell(0, 0), WallCell(5, 0), block(0..1, 0..0) + block(4..5, 0..0)) == null)
    }

    test("a path is travelled at an even pace from end to end") {
        val path = FramePath(listOf(WallPoint(0.0, 0.0), WallPoint(3.0, 0.0), WallPoint(3.0, 4.0)))
        check(path.length == 7.0)
        check(path.at(1.5) == WallPoint(1.5, 0.0))
        check(path.at(5.0) == WallPoint(3.0, 2.0))
        check(path.at(100.0) == WallPoint(3.0, 4.0))
    }
})
