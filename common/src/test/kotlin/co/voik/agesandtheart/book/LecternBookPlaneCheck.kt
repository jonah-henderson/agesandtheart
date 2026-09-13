package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * Where a look at a book on a lectern lands.
 *
 * The frame is checked against the world as well as against itself, because the fault it can actually have
 * is a sign: a reader who clicks the page on their left and is answered about the one on their right.
 */
class LecternBookPlaneCheck : FunSpec({

    val lectern = BlockPos(10, 64, -20)

    /** A reader standing two blocks out on the side the lectern faces, at eye height. */
    fun readerFacing(facing: Direction): Vec3 =
        Vec3.atCenterOf(lectern).add(facing.stepX * 2.0, 1.1, facing.stepZ * 2.0)

    fun centreOf(rectangle: BookRectangle): Pair<Double, Double> = Pair(
        (rectangle.across.start + rectangle.across.endInclusive) / 2,
        (rectangle.up.start + rectangle.up.endInclusive) / 2,
    )

    fun lookAt(facing: Direction, across: Double, up: Double): LecternBookSpot? {
        val point = LecternBookPlane.pointOnTheBook(facing, lectern, across, up)
        return LecternBookPlane.spotLookedAt(facing, lectern, readerFacing(facing), point)
    }

    for (facing in Direction.Plane.HORIZONTAL) {
        test("facing $facing, a look at either page's panel lands in it") {
            for (page in BookPage.entries) {
                val (across, up) = centreOf(LecternBookPlane.panelOn(page))
                val landed = lookAt(facing, across, up)
                check(landed == LecternBookSpot(page, withinThePanel = true)) { "$page panel: landed on $landed" }
            }
        }
    }

    test("a page outside its panel is still that page") {
        // Near the left page's fore-edge and below its panel.
        check(lookAt(Direction.NORTH, 0.30, -0.2) == LecternBookSpot(BookPage.LEFT, withinThePanel = false))
    }

    test("the left page is on the reader's left") {
        // Facing north, the reader stands north of the lectern looking south, so their left hand is east.
        val (across, up) = centreOf(LecternBookPlane.panelOn(BookPage.LEFT))
        val leftPanel = LecternBookPlane.pointOnTheBook(Direction.NORTH, lectern, across, up)
        check(leftPanel.x > lectern.x + 0.5) { "the left panel sits at x=${leftPanel.x}, west of the lectern" }
    }

    test("the book leans back, away from its reader") {
        val top = LecternBookPlane.pointOnTheBook(Direction.NORTH, lectern, 0.0, 0.25)
        val bottom = LecternBookPlane.pointOnTheBook(Direction.NORTH, lectern, 0.0, -0.25)
        check(top.y > bottom.y) { "the top edge is not the higher one" }
        check(top.z > bottom.z) { "facing north, the top edge should be the southern one" }
    }

    test("a look from behind the lectern lands on nothing") {
        val behind = Vec3.atCenterOf(lectern).add(0.0, 1.1, 2.0)
        val atTheBook = Vec3.atCenterOf(lectern).add(0.0, 0.6, 0.0)
        check(LecternBookPlane.spotLookedAt(Direction.NORTH, lectern, behind, atTheBook) == null)
    }

    test("a look at the lectern's foot lands on nothing") {
        val foot = Vec3.atCenterOf(lectern).add(0.0, -0.4, 0.0)
        check(LecternBookPlane.spotLookedAt(Direction.NORTH, lectern, readerFacing(Direction.NORTH), foot) == null)
    }
})
