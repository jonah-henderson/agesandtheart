package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.book.BookBeingRead
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import java.util.UUID

/** That a panel asked for too soon is served late rather than never — and that a hand never waits on a lectern. */
class PanelPacingCheck : FunSpec({

    val gap = 10L
    val viewer = UUID(0L, 1L)
    val inHand = BookBeingRead.InHand(InteractionHand.MAIN_HAND)
    val lectern = BookBeingRead.OnALectern(BlockPos(4, 64, 4))
    val nextLectern = BookBeingRead.OnALectern(BlockPos(9, 64, 4))
    val lastLectern = BookBeingRead.OnALectern(BlockPos(14, 64, 4))

    test("a first request is served at once") {
        check(PanelPacing(gap).admit(viewer, inHand, now = 100))
    }

    test("a book shut and opened again at once is held, then served as soon as the pace allows") {
        val pacing = PanelPacing(gap)
        pacing.admit(viewer, inHand, now = 100)
        check(!pacing.admit(viewer, inHand, now = 103)) { "served inside the pace" }
        check(pacing.due(now = 109).isEmpty()) { "handed back before the pace allowed" }
        check(pacing.due(now = 110) == mapOf(viewer to inHand)) { "not handed back once the pace allowed" }
        check(pacing.due(now = 111).isEmpty()) { "handed back twice" }
    }

    test("a newer request replaces a held one") {
        val pacing = PanelPacing(gap)
        pacing.admit(viewer, lectern, now = 100)
        pacing.admit(viewer, nextLectern, now = 102)
        pacing.admit(viewer, lastLectern, now = 104)
        check(pacing.due(now = 110) == mapOf(viewer to lastLectern))
    }

    test("a hand is never held up by a lectern") {
        val pacing = PanelPacing(gap)
        pacing.admit(viewer, lectern, now = 100)
        check(pacing.admit(viewer, inHand, now = 101))
    }

    test("a close drops what was held") {
        val pacing = PanelPacing(gap)
        pacing.admit(viewer, inHand, now = 100)
        pacing.admit(viewer, inHand, now = 101)
        pacing.cancel(viewer)
        check(pacing.due(now = 200).isEmpty())
    }

    test("a player who left starts afresh") {
        val pacing = PanelPacing(gap)
        pacing.admit(viewer, inHand, now = 100)
        pacing.forget(viewer)
        check(pacing.admit(viewer, inHand, now = 101))
    }
})
