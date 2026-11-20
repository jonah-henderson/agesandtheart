package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * How a book lift's shelves lie round it: up the first column, across the top, down the far one and back across the
 * bottom; and how many it holds.
 */
class BookLiftLoopCheck : FunSpec({

    val loops = (1..BookLiftLoop.WIDEST_SPAN).flatMap { span -> (1..4).map { BookLiftLoop(it, span) } }

    fun cell(across: Int, row: Int) = BookLiftLoop.Cell(across, row)

    test("twenty lengths of chain hold forty-four shelves side by side, ten twenty-four, and the widest lift, six frames between, fifty-six") {
        check(BookLiftLoop(BookLiftLoop.LONGEST_CHAIN).shelves == 44)
        check(BookLiftLoop(10).shelves == 24)
        check(BookLiftLoop.MOST_SHELVES == 56)
    }

    test("side by side, the loop runs up the first column, across the caps, and down the far one") {
        val loop = BookLiftLoop(3)
        val cells = (0 until loop.shelves).map(loop::cellOf)
        val expected = listOf(cell(0, 0), cell(0, 1), cell(0, 2), cell(0, 3), cell(0, 4), cell(1, 4), cell(1, 3), cell(1, 2), cell(1, 1), cell(1, 0))
        check(cells == expected) { "$cells" }
    }

    test("with frames between, it passes through them across the top and back across the bottom") {
        val loop = BookLiftLoop(1, span = 3)
        val cells = (0 until loop.shelves).map(loop::cellOf)
        val expected = listOf(
            cell(0, 0), cell(0, 1), cell(0, 2), cell(1, 2), cell(2, 2), cell(3, 2), cell(3, 1), cell(3, 0), cell(2, 0), cell(1, 0),
        )
        check(cells == expected) { "$cells" }
    }

    test("each step round the loop goes to the next block, and round the loop it comes back") {
        for (loop in loops) {
            for (position in 0 until loop.shelves) {
                val here = loop.cellOf(position)
                val next = loop.cellOf(position + 1)
                val distance = abs(next.across - here.across) + abs(next.row - here.row)
                check(distance == 1) { "$loop: position $position at $here goes to $next" }
                check(loop.cellOf(position + loop.shelves) == here && loop.cellOf(position - loop.shelves) == here)
            }
        }
    }

    test("every place on the loop's edge is one position, and nothing inside it is") {
        for (loop in loops) {
            val cells = (0 until loop.shelves).map(loop::cellOf)
            check(cells.toSet().size == loop.shelves) { "$loop visits a place twice" }
            val onTheEdge = cells.all { it.across == 0 || it.across == loop.span || it.row == 0 || it.row == loop.top }
            check(onTheEdge) { "$loop goes inside itself" }
        }
    }

    test("a shelf's column and row is the position whose place it is") {
        for (loop in loops) {
            for (column in 0..1) for (row in 1..loop.chainLength) {
                val found = loop.cellOf(loop.positionOf(column, row))
                check(found == cell(loop.acrossOf(column), row)) { "$loop: column $column row $row is at $found" }
            }
        }
    }

    test("turned on, the first column's shelves go up and the far one's down") {
        for (loop in loops) {
            val first = loop.cellOf(loop.positionOf(0, 1) + 1)
            val far = loop.cellOf(loop.positionOf(1, 1) + 1)
            check(first.row == 2 && far.row == 0) { "$loop: from row 1, the first column goes to $first and the far to $far" }
        }
    }

    test("only the chain's rows can be reached, not the bases' or the caps'") {
        val loop = BookLiftLoop(5, span = 4)
        check((0..loop.rows).filter(loop::isReachable) == (1..5).toList())
    }
})
