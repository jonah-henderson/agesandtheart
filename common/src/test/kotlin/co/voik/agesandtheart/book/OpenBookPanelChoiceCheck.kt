package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos

/** Which open book gets the one panel a client can draw, and that it does not flicker between two. */
class OpenBookPanelChoiceCheck : FunSpec({

    val here = BookBeingRead.OnALectern(BlockPos(0, 64, 0))
    val there = BookBeingRead.OnALectern(BlockPos(6, 64, 0))

    fun at(book: BookBeingRead, distance: Double) = NearbyOpenBook(book, distance)

    test("nothing near shows nothing") {
        check(OpenBookPanelChoice.choose(null, emptyList()) == null)
    }

    test("a book within reach is taken up") {
        check(OpenBookPanelChoice.choose(null, listOf(at(here, 5.0))) == here)
    }

    test("a book just past reach is not taken up") {
        check(OpenBookPanelChoice.choose(null, listOf(at(here, 8.5))) == null)
    }

    test("but one already showing is kept there") {
        check(OpenBookPanelChoice.choose(here, listOf(at(here, 8.5))) == here)
    }

    test("and let go a block further out") {
        check(OpenBookPanelChoice.choose(here, listOf(at(here, 9.5))) == null)
    }

    test("a nearer book does not take the panel by a little") {
        check(OpenBookPanelChoice.choose(here, listOf(at(here, 5.0), at(there, 3.5))) == here)
    }

    test("but does by more than the margin") {
        check(OpenBookPanelChoice.choose(here, listOf(at(here, 6.0), at(there, 3.0))) == there)
    }

    test("two lecterns two blocks apart still hand over, to somebody standing at the other") {
        val besideIt = BookBeingRead.OnALectern(BlockPos(2, 64, 0))
        check(OpenBookPanelChoice.choose(here, listOf(at(here, 2.3), at(besideIt, 1.1))) == besideIt)
    }

    test("and do not, to somebody standing between them") {
        val besideIt = BookBeingRead.OnALectern(BlockPos(2, 64, 0))
        check(OpenBookPanelChoice.choose(here, listOf(at(here, 1.6), at(besideIt, 1.4))) == here)
    }

    test("a book that has shut or gone hands the panel on") {
        check(OpenBookPanelChoice.choose(here, listOf(at(there, 7.0))) == there)
    }

    test("a fallen book is chosen as a lectern is, by distance alone") {
        val fallen = BookBeingRead.OnTheGround(entityId = 7)
        check(OpenBookPanelChoice.choose(null, listOf(at(here, 5.0), at(fallen, 2.0))) == fallen)
    }
})
