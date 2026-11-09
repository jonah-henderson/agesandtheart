package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.CoverLayout
import co.voik.agesandtheart.client.AgeBookModel.BindingPiece
import com.mojang.blaze3d.vertex.PoseStack
import io.kotest.core.spec.style.FunSpec
import org.joml.Vector3f
import kotlin.math.abs

/**
 * The book model shuts into a whole book and opens flat onto its boards, swings on a lectern with its back board on
 * the slope, a panel laid in a page's frame lies on that page, and a pattern laid where
 * [AgeBookModel.bindingFaces] says lies on the binding and inside the banner's field. A linking book's thin boards
 * shut it to half a descriptive book's thickness.
 */
class AgeBookModelCheck : FunSpec({

    fun modelFor(binding: BookBinding) = AgeBookModel(AgeBookModel.createLayer(binding).bakeRoot(), binding)

    val model by lazy { modelFor(BookBinding.THICK) }
    val thin by lazy { modelFor(BookBinding.THIN) }

    /** Where each visible part's cubes lie, in model units, in the frame the renderers draw in. */
    data class Bounds(val corners: List<Vector3f>) {
        val minX get() = corners.minOf { it.x }
        val maxX get() = corners.maxOf { it.x }
        val minZ get() = corners.minOf { it.z }
        val maxZ get() = corners.maxOf { it.z }
    }

    fun partsAt(
        state: AgeBookModel.State,
        of: AgeBookModel = model,
        laid: (PoseStack) -> Unit = {},
    ): Map<String, Bounds> {
        of.setupAnim(state)
        val corners = mutableMapOf<String, MutableList<Vector3f>>()
        of.root().visit(PoseStack().also(laid)) { pose, path, _, cube ->
            val part = path.substringAfterLast('/')
            for (x in listOf(cube.minX, cube.maxX)) for (y in listOf(cube.minY, cube.maxY)) for (z in listOf(cube.minZ, cube.maxZ)) {
                val corner = pose.pose().transformPosition(Vector3f(x, y, z).div(UNITS_PER_BLOCK)).mul(UNITS_PER_BLOCK)
                corners.getOrPut(part) { mutableListOf() }.add(corner)
            }
        }
        return corners.mapValues { (_, list) -> Bounds(list) }
    }

    fun near(a: Float, b: Float) = abs(a - b) < TOLERANCE

    test("open flat, the boards rest on X = 0 and the pages meet at the gutter on top of them") {
        val parts = partsAt(AgeBookModel.State(1.0f))
        val boards = listOf("left_lid", "right_lid", "seam").map { parts.getValue(it) }
        check(boards.all { near(it.minX, 0.0f) && near(it.maxX, 1.0f) }) { "the boards lie at ${boards.map { it.minX..it.maxX }}" }
        val left = parts.getValue("left_pages")
        val right = parts.getValue("right_pages")
        check(near(left.minX, 1.0f) && near(left.maxX, 2.0f)) { "the left pages lie at ${left.minX}..${left.maxX}" }
        check(near(left.minZ, 0.0f) && near(right.maxZ, 0.0f)) { "the pages meet at ${left.minZ} and ${right.maxZ}" }
    }

    test("shut, the boards close over the pages four pixels thick") {
        val parts = partsAt(AgeBookModel.State(0.0f))
        val leftBoard = parts.getValue("left_lid")
        val rightBoard = parts.getValue("right_lid")
        check(near(leftBoard.maxZ, 2.0f) && near(rightBoard.minZ, -2.0f)) { "the boards close at ${leftBoard.maxZ} and ${rightBoard.minZ}" }
        val pages = listOf("left_pages", "right_pages").map { parts.getValue(it) }
        check(pages.all { it.minZ >= -1.0f - TOLERANCE && it.maxZ <= 1.0f + TOLERANCE }) { "the pages lie at ${pages.map { it.minZ..it.maxZ }}" }
    }

    test("a turning leaf is drawn only while it turns") {
        val leaf = model.root().getChild("book").getChild("flip_page_l")
        val shownWhen = listOf(0.0f, 0.5f, 1.0f).associateWith { turned ->
            model.setupAnim(AgeBookModel.State(1.0f, firstLeaf = turned))
            leaf.visible
        }
        check(shownWhen == mapOf(0.0f to false, 0.5f to true, 1.0f to false)) { "the leaf shows $shownWhen" }
    }

    test("shut on a lectern, the book lies on the slope front cover up, its boards centred") {
        val parts = partsAt(AgeBookModel.State(0.0f)) { BookOnTheSlope.layOnItsBack(0.0f, BookBinding.THICK, it) }
        val back = parts.getValue("right_lid")
        val front = parts.getValue("left_lid")
        check(near(back.minX, 0.0f) && near(front.maxX, 4.0f)) { "the back lies at ${back.minX}, the front's top at ${front.maxX}" }
        check(near(front.minZ, -3.0f) && near(front.maxZ, 3.0f)) { "the shut book lies across ${front.minZ}..${front.maxZ}" }
        check(parts.values.all { it.minX >= -TOLERANCE }) { "something lies under the slope" }
    }

    for (binding in BookBinding.entries) {
        test("swinging on a lectern, a $binding book's back board never leaves the slope or sinks into it") {
            val lowest = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f).associateWith { swing ->
                val book = if (binding == BookBinding.THICK) model else thin
                partsAt(BookOnTheSlope.bookAt(swing), of = book) { BookOnTheSlope.layOnItsBack(swing, binding, it) }
                    .getValue("right_lid").minX
            }
            check(lowest.values.all { it in -TOLERANCE..RESTING_ON_THE_SLOPE }) { "the back board's lowest point lies at $lowest" }
        }
    }

    test("open on a lectern, both boards rise from the slope alike, a little short of flat") {
        val parts = partsAt(BookOnTheSlope.bookAt(1.0f)) { BookOnTheSlope.layOnItsBack(1.0f, BookBinding.THICK, it) }
        val front = parts.getValue("left_lid")
        val back = parts.getValue("right_lid")
        check(near(front.maxX, back.maxX) && front.maxX > 1.0f + TOLERANCE) { "the boards rise to ${front.maxX} and ${back.maxX}" }
    }

    test("the binding's faces lie on the outside of its boards and spine") {
        model.setupAnim(AgeBookModel.State(1.0f))
        val reaches = mapOf(BindingPiece.FRONT to 1.0f..7.0f, BindingPiece.SPINE to -1.0f..1.0f, BindingPiece.BACK to -7.0f..-1.0f)
        for (face in model.bindingFaces) {
            val poseStack = PoseStack()
            model.poseOnto(face.piece, poseStack)
            val corners = listOf(face.acrossFrom, face.acrossTo).flatMap { across ->
                listOf(face.top, face.bottom).map { up ->
                    poseStack.last().pose().transformPosition(Vector3f(across, up, face.outside).div(UNITS_PER_BLOCK)).mul(UNITS_PER_BLOCK)
                }
            }
            val reach = corners.minOf { it.z }..corners.maxOf { it.z }
            val expected = reaches.getValue(face.piece)
            check(corners.all { near(it.x, 0.0f) }) { "${face.piece} lies at ${corners.map { it.x }}, not on the underside" }
            check(near(reach.start, expected.start) && near(reach.endInclusive, expected.endInclusive)) { "${face.piece} reaches $reach" }
        }
    }

    for (layout in CoverLayout.entries) {
        test("$layout keeps every face inside the banner's field, and runs on across the spine") {
            val faces = model.bindingFaces.associateBy { it.piece }
            fun at(piece: BindingPiece, across: Float, down: Float) =
                BookCover.fieldAt(layout, model.bindingFaces, faces.getValue(piece), across, down)
            val corners = BindingPiece.entries.flatMap { piece -> listOf(0f, 1f).flatMap { a -> listOf(0f, 1f).map { d -> at(piece, a, d) } } }
            val inside = corners.all { (x, y) -> x in 0.0f..BookCover.FIELD_WIDTH && y in 0.0f..BookCover.FIELD_HEIGHT }
            check(inside) { "$layout reads outside the field: $corners" }
            if (layout == CoverLayout.WRAP_AROUND) {
                val seams = listOf(at(BindingPiece.BACK, 0f, 0.5f) to at(BindingPiece.SPINE, 0f, 0.5f), at(BindingPiece.SPINE, 1f, 0.5f) to at(BindingPiece.FRONT, 0f, 0.5f))
                check(seams.all { (one, other) -> near(one.first, other.first) && near(one.second, other.second) }) { "the wrap breaks at $seams" }
            }
            if (layout == CoverLayout.MIRRORED) {
                val atTheSpine = listOf(at(BindingPiece.FRONT, 0f, 0.5f), at(BindingPiece.BACK, 0f, 0.5f)).map { it.first }
                check(atTheSpine.all { near(it, 0.0f) }) { "the boards meet the spine at columns $atTheSpine" }
            }
        }
    }

    test("a linking book shuts two pixels thick, its spine filling its back, and opens flat on its thin boards") {
        val shut = partsAt(AgeBookModel.State(0.0f), of = thin)
        val front = shut.getValue("left_lid")
        val back = shut.getValue("right_lid")
        val boards = BookBinding.THIN.boardThickness
        check(near(front.maxZ, 1.0f + boards) && near(back.minZ, -1.0f - boards)) { "shut at ${back.minZ}..${front.maxZ}" }
        val open = partsAt(AgeBookModel.State(1.0f), of = thin)
        val pages = open.getValue("left_pages")
        check(near(open.getValue("left_lid").minX, 0.0f) && near(pages.maxX, boards + 1.0f)) { "open, the pages top at ${pages.maxX}" }
    }

    test("shut on a lectern, a linking book's back board lies on the slope too") {
        val back = partsAt(AgeBookModel.State(0.0f), of = thin) { BookOnTheSlope.layOnItsBack(0.0f, BookBinding.THIN, it) }
            .getValue("right_lid")
        check(near(back.minX, 0.0f) && near(back.minZ, -3.0f)) { "the back lies at ${back.minX}, across ${back.minZ}" }
    }

    test("a panel laid in its page's frame lies on that page, up the page and toward its side") {
        model.setupAnim(AgeBookModel.State(1.0f))
        for ((page, side) in listOf(BookPage.LEFT to 1.0f, BookPage.RIGHT to -1.0f)) {
            val poseStack = PoseStack()
            model.poseOntoPage(page, poseStack)
            val across = side * 3.0f / UNITS_PER_BLOCK
            val up = 2.0f / UNITS_PER_BLOCK
            val landed = poseStack.last().pose().transformPosition(Vector3f(-across, -up, 0.0f)).mul(UNITS_PER_BLOCK)
            val expected = Vector3f(2.0f, 2.0f, side * 3.0f)
            check(landed.distance(expected) < TOLERANCE) { "a point on the $page page lands at $landed, not $expected" }
        }
    }
})

private const val UNITS_PER_BLOCK = 16.0f
private const val TOLERANCE = 0.001f

/** How near the slope a board's hinge end may stand as it turns about its inner edge: a hair. */
private const val RESTING_ON_THE_SLOPE = 0.01f
