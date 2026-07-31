package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/**
 * The desk's arithmetic.
 *
 * Deliberately not a test of `DeskIntake`: that compares item identities, and `AgeContent` cannot be
 * loaded offline — `Item.<init>` creates an intrusive holder, which the item registry refuses once
 * `Bootstrap.bootStrap()` has frozen it. What is testable here is the part that was wrong first time,
 * which is the counting rather than the item handling.
 */
class DeskStoresCheck : FunSpec({

    // Its own number, since the real one is the loader's and this suite has no loader.
    val capacity = 64L * 81_000L

    test("ink fills to the cap and reports the overflow") {
        val (filled, rejected) = DeskStores.EMPTY.addingInk(InkTier.COMMON, capacity + 500, capacity)
        check(filled.ink(InkTier.COMMON) == capacity) { "Tank holds ${filled.ink(InkTier.COMMON)}" }
        check(rejected == 500L) { "Expected 500 rejected, got $rejected" }
        check(filled.inkSpace(InkTier.COMMON, capacity) == 0L)
    }

    test("the three inks are separate tanks") {
        val (one, _) = DeskStores.EMPTY.addingInk(InkTier.FINE, 1000, capacity)
        check(one.ink(InkTier.FINE) == 1000L)
        check(one.ink(InkTier.COMMON) == 0L) { "Filling fine should not touch common" }
        check(one.ink(InkTier.MASTERWORK) == 0L)
    }

    test("paper stops at the cap") {
        val (filled, rejected) = DeskStores.EMPTY.addingPaper(InkTier.COMMON, DeskStores.PAPER_CAPACITY + 7)
        check(filled.paper(InkTier.COMMON) == DeskStores.PAPER_CAPACITY)
        check(rejected == 7)
    }

    /** The property the whole write path leans on: never half-paid. */
    test("spending is all or nothing") {
        val (stocked, _) = DeskStores.EMPTY.addingInk(InkTier.COMMON, 1000, capacity)
        val (withPaper, _) = stocked.addingPaper(InkTier.COMMON, 2)

        check(withPaper.spending(InkTier.COMMON, 1001, InkTier.COMMON, 1) == null) { "Too little ink" }
        check(withPaper.spending(InkTier.COMMON, 500, InkTier.COMMON, 3) == null) { "Too little paper" }

        val paid = withPaper.spending(InkTier.COMMON, 400, InkTier.COMMON, 1)
        check(paid != null) { "Affordable spend should succeed" }
        check(paid!!.ink(InkTier.COMMON) == 600L) { "Ink left ${paid.ink(InkTier.COMMON)}" }
        check(paid.paper(InkTier.COMMON) == 1) { "Paper left ${paid.paper(InkTier.COMMON)}" }
    }

    test("a failed spend leaves the stores untouched") {
        val (stocked, _) = DeskStores.EMPTY.addingInk(InkTier.MASTERWORK, 50, capacity)
        check(stocked.spending(InkTier.MASTERWORK, 100, InkTier.COMMON, 0) == null)
        check(stocked.ink(InkTier.MASTERWORK) == 50L) { "Nothing should have been deducted" }
    }

    test("the archive counts pages and refuses to over-draw") {
        val flat = Identifier.parse("agesandtheart:flat")
        val archive = PageArchive.EMPTY.with(flat, 3)
        check(archive.count(flat) == 3)
        check(archive.without(flat, 4) == null) { "Cannot take more than it holds" }
        val taken = archive.without(flat, 3)
        check(taken != null && taken.count(flat) == 0)
        check(taken!!.words.isEmpty()) { "An emptied word should leave no entry behind" }
    }
})
