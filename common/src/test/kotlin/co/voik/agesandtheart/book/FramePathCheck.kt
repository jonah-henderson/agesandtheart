package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * How a book finder's frame crosses its wall: on the grid of shelves always, the grid's reading of a curve where it fits,
 * leaving upright and arriving level, and a walk where not.
 */
class FramePathCheck : FunSpec({

    fun block(across: IntRange, up: IntRange): Set<WallCell> =
        across.flatMap { a -> up.map { u -> WallCell(a, u) } }.toSet()

    /** Whether every leg of [path] runs straight along the wall or straight up it, from one shelf's middle to another's. */
    fun isOnTheGrid(path: FramePath): Boolean =
        path.points.all { it.across % 1.0 == 0.0 && it.up % 1.0 == 0.0 } &&
            path.points.zipWithNext().all { (a, b) -> a.across == b.across || a.up == b.up }

    /** Every space a leg of [path] passes over. */
    fun spacesOf(path: FramePath): List<WallCell> = path.points.zipWithNext().flatMap { (a, b) ->
        val steps = maxOf(abs(b.across - a.across), abs(b.up - a.up)).toInt()
        (0..steps).map { i ->
            val share = if (steps == 0) 0.0 else i.toDouble() / steps
            FramePath.cellUnder(WallPoint(a.across + (b.across - a.across) * share, a.up + (b.up - a.up) * share))
        }
    }

    test("it never leaves the grid, and never a shelf") {
        val wall = block(0..3, 0..0) + block(3..10, 0..6) + block(0..0, 1..3)
        for (to in wall) {
            val path = FramePath.between(WallCell(0, 0), to, wall)!!
            check(isOnTheGrid(path)) { "off the grid to $to: ${path.points}" }
            check(spacesOf(path).all { it in wall }) { "off the wall to $to: ${path.points}" }
        }
    }

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

    test("past a narrow place it still curves across the open wall beyond") {
        // A corridor one shelf high, then a wide wall: the whole way cannot be one curve, but the open part can, as a
        // staircase rather than one turn up and one along.
        val wall = block(0..3, 0..0) + block(3..10, 0..6)
        val path = FramePath.between(WallCell(0, 0), WallCell(10, 6), wall)!!
        val turnsPastTheCorridor = path.points.count { it.across >= 3.0 } - 2
        check(turnsPastTheCorridor > 2) { "it should step across the open wall as it bends: ${path.points}" }
    }

    test("the way home is the way out, retraced") {
        val out = FramePath.between(WallCell(0, 0), WallCell(6, 4), block(-2..8, -2..6))!!
        check(out.reversed().points == out.points.reversed())
        check(out.reversed().points.first() == WallPoint(6.0, 4.0))
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

    test("the rest of a way starts where the carriage is and keeps every turn ahead") {
        val path = FramePath(listOf(WallPoint(0.0, 0.0), WallPoint(3.0, 0.0), WallPoint(3.0, 4.0)))
        check(path.after(1.5).points == listOf(WallPoint(1.5, 0.0), WallPoint(3.0, 0.0), WallPoint(3.0, 4.0)))
        check(path.after(5.0).points == listOf(WallPoint(3.0, 2.0), WallPoint(3.0, 4.0)))
        check(path.after(0.0).length == path.length)
        check(path.after(100.0).points == listOf(WallPoint(3.0, 4.0)))
    }
})
