package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec

/** How a book lift's shelves lie round its two columns, and how many it holds. */
class BookLiftLoopCheck : FunSpec({

    test("twenty lengths of chain hold forty-four shelves, and ten twenty-four") {
        check(BookLiftLoop(BookLiftLoop.LONGEST_CHAIN).shelves == 44)
        check(BookLiftLoop(10).shelves == 24)
        check(BookLiftLoop.MOST_SHELVES == 44)
    }

    test("the loop runs up the left column, across the cap, and down the right") {
        val loop = BookLiftLoop(3)
        val cells = (0 until loop.shelves).map(loop::cellOf)
        check(cells == listOf(0 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 4, 1 to 4, 1 to 3, 1 to 2, 1 to 1, 1 to 0)) { "$cells" }
    }

    test("every cell is one position, and round the loop it comes back") {
        val loop = BookLiftLoop(BookLiftLoop.LONGEST_CHAIN)
        for (position in 0 until loop.shelves) {
            val (column, row) = loop.cellOf(position)
            check(loop.positionOf(column, row) == position) { "position $position is $column, $row and back ${loop.positionOf(column, row)}" }
            check(loop.cellOf(position + loop.shelves) == loop.cellOf(position))
            check(loop.cellOf(position - loop.shelves) == loop.cellOf(position))
        }
    }

    test("only the chain's rows can be reached, not the base's or the cap's") {
        val loop = BookLiftLoop(5)
        check((0..loop.rows).filter(loop::isReachable) == (1..5).toList())
    }
})
