package co.voik.agesandtheart.book

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One page of an open book, as the reader standing at the lectern sees it. */
enum class BookPage { LEFT, RIGHT }

/** Where a look lands on an open book: which page, and whether inside the panel that page would carry. */
data class LecternBookSpot(val page: BookPage, val withinThePanel: Boolean)

/**
 * A rectangle on the book, in blocks from the centre of the spine: [across] toward the reader's left, so the
 * right page is negative, and [up] toward the top edge.
 */
data class BookRectangle(
    val across: ClosedFloatingPointRange<Double>,
    val up: ClosedFloatingPointRange<Double>,
) {
    fun contains(acrossAt: Double, upAt: Double): Boolean = acrossAt in across && upAt in up
}

/**
 * Where the book on a lectern lies, and what a look at it lands on.
 *
 * The transform is `LecternRenderer`'s, and [CENTRE_HEIGHT], [TILT_DEGREES] and [DOWN_THE_SLOPE] are the
 * numbers our renderer draws with, so what is drawn and what is clicked cannot drift apart. A click lands on
 * the lectern's voxel shape, which is a stair of boxes the book does not rest on, so the ray is carried on
 * to the book's own plane instead.
 *
 * Left and right are the reader's, standing on the lectern's `FACING` side. Vanilla's model names the page
 * on the reader's right its left, which is why nothing here reads the model's part names.
 */
object LecternBookPlane {

    /** How high the book's centre sits above the lectern's block position, before it slides down the slope. */
    const val CENTRE_HEIGHT = 1.0625

    /** How far the book leans back from upright. */
    const val TILT_DEGREES = 67.5

    /** How far the book slides down the slope, toward the reader. */
    const val DOWN_THE_SLOPE = 0.125

    /** The top page stands proud of the model's origin by its pivot offset and its own thickness. */
    private const val PAGE_SURFACE = 1.5 / 16.0

    /** One page of the model, spine to fore-edge and bottom to top. */
    private const val PAGE_WIDTH = 5.0 / 16.0
    private const val PAGE_HEIGHT = 8.0 / 16.0

    /** The covers, which stand past the pages and are still the book. */
    private const val BOOK_HALF_WIDTH = 6.0 / 16.0
    private const val BOOK_HALF_HEIGHT = 5.0 / 16.0

    /** The book screen's panel as a share of its leaf: 104 of 128 across, 65 of 180 tall, 30 down from the top. */
    private const val PANEL_SHARE_ACROSS = 104.0 / 128.0
    private const val PANEL_SHARE_TALL = 65.0 / 180.0
    private const val PANEL_SHARE_FROM_THE_TOP = 30.0 / 180.0

    private val UP = Vec3(0.0, 1.0, 0.0)

    /** The panel [page] would carry, centred across the leaf and set down from its top as the screen's is. */
    fun panelOn(page: BookPage): BookRectangle {
        val width = PAGE_WIDTH * PANEL_SHARE_ACROSS
        val fromTheSpine = (PAGE_WIDTH - width) / 2
        val across = when (page) {
            BookPage.LEFT -> fromTheSpine..(fromTheSpine + width)
            BookPage.RIGHT -> -(fromTheSpine + width)..-fromTheSpine
        }
        val top = PAGE_HEIGHT / 2 - PAGE_HEIGHT * PANEL_SHARE_FROM_THE_TOP
        return BookRectangle(across, (top - PAGE_HEIGHT * PANEL_SHARE_TALL)..top)
    }

    /** Where on the open book a look from [eye] through [through] lands, or null where it misses the book. */
    fun spotLookedAt(facing: Direction, pos: BlockPos, eye: Vec3, through: Vec3): LecternBookSpot? {
        val frame = frameOf(facing, pos)
        val direction = through.subtract(eye)
        val intoTheFace = direction.dot(frame.normal)
        // Edge-on or from behind: a book is read from its reader's side and no other.
        if (intoTheFace >= 0.0) return null
        val alongTheRay = frame.surface.subtract(eye).dot(frame.normal) / intoTheFace
        if (alongTheRay <= 0.0) return null

        val landed = eye.add(direction.scale(alongTheRay)).subtract(frame.surface)
        val across = landed.dot(frame.towardTheLeft)
        val up = landed.dot(frame.upThePage)
        val onTheBook = abs(across) <= BOOK_HALF_WIDTH && abs(up) <= BOOK_HALF_HEIGHT
        if (!onTheBook) return null

        val page = if (across >= 0.0) BookPage.LEFT else BookPage.RIGHT
        return LecternBookSpot(page, withinThePanel = panelOn(page).contains(across, up))
    }

    /** Where in the world a point on the book lies, [across] and [up] measured as [BookRectangle] measures them. */
    fun pointOnTheBook(facing: Direction, pos: BlockPos, across: Double, up: Double): Vec3 {
        val frame = frameOf(facing, pos)
        return frame.surface.add(frame.towardTheLeft.scale(across)).add(frame.upThePage.scale(up))
    }

    /** The book's page surface, and the three directions it is measured in. */
    private class Frame(val surface: Vec3, val normal: Vec3, val upThePage: Vec3, val towardTheLeft: Vec3)

    private fun frameOf(facing: Direction, pos: BlockPos): Frame {
        val tilt = Math.toRadians(TILT_DEGREES)
        val towardTheReader = horizontal(facing)
        val normal = towardTheReader.scale(cos(tilt)).add(UP.scale(sin(tilt)))
        val upThePage = towardTheReader.scale(-sin(tilt)).add(UP.scale(cos(tilt)))
        val centre = Vec3(pos.x + 0.5, pos.y + CENTRE_HEIGHT, pos.z + 0.5).add(upThePage.scale(-DOWN_THE_SLOPE))
        return Frame(
            surface = centre.add(normal.scale(PAGE_SURFACE)),
            normal = normal,
            upThePage = upThePage,
            // The reader stands on the facing side looking back across the lectern, so their left is clockwise.
            towardTheLeft = horizontal(facing.clockWise),
        )
    }

    private fun horizontal(direction: Direction) = Vec3(direction.stepX.toDouble(), 0.0, direction.stepZ.toDouble())
}
