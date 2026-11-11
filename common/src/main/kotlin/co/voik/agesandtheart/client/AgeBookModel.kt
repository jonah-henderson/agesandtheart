package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.Model
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import kotlin.math.PI
import kotlin.math.abs

/**
 * The descriptive and linking books, drawn shut or open wherever one lies: on a lectern ([LecternBookRenderer]),
 * fallen ([BookEntityRenderer]) or as an item ([AgeBookItemRenderer]). One model, its boards as thick as the
 * book's [binding], and a texture for each book.
 *
 * Authored shut in Blockbench (`agesandtheart-art-sources/linking_book.bbmodel`), and laid out here as its Java
 * export lays it out, under a [BOOK] part that turns it into the frame vanilla's book is drawn in: X off the page,
 * Y up it, Z toward the reader's left. Open flat, the boards' undersides lie on X = 0.
 */
class AgeBookModel(root: ModelPart, val binding: BookBinding) :
    Model<AgeBookModel.State>(root, { texture -> RenderTypes.entityCutout(texture) }) {

    /** The binding's pieces, as thick as this book's boards. */
    val bindingFaces: List<BindingFace> = bindingFacesOf(binding)

    private val book = root.getChild(BOOK)
    private val leftLid = book.getChild(LEFT_LID)
    private val rightLid = book.getChild(RIGHT_LID)
    private val seam = book.getChild(SEAM)
    private val leftPages = book.getChild(LEFT_PAGES)
    private val rightPages = book.getChild(RIGHT_PAGES)
    private val blankRightPages = book.getChild(BLANK_RIGHT_PAGES)
    private val bindingParts = listOf(leftLid, rightLid, seam)
    private val leaves = listOf(book.getChild(FIRST_LEAF), book.getChild(SECOND_LEAF))

    /**
     * A book [openness] of the way from shut (0) to flat (1), each of its two turning leaves somewhere between
     * lying on the right-hand page (0) and on the left-hand one (1). A leaf lying on either page is not drawn. Only
     * the [drawn] parts show, and the right-hand page is written on where [writtenOnTheRight], blank as the left is
     * otherwise.
     */
    data class State(
        val openness: Float,
        val firstLeaf: Float = 0.0f,
        val secondLeaf: Float = 0.0f,
        val drawn: Drawn = Drawn.WHOLE,
        val writtenOnTheRight: Boolean = true,
    )

    /** Which of the book's parts a pass draws: the binding takes its dye, and the pages never do. */
    enum class Drawn { WHOLE, BINDING, PAGES }

    override fun setupAnim(state: State) {
        super.setupAnim(state)
        val swing = state.openness * QUARTER_TURN
        val slide = -state.openness * PAGES_SLIDE
        leftLid.yRot += swing
        rightLid.yRot -= swing
        leftPages.yRot += swing
        rightPages.yRot -= swing
        leftPages.z = slide
        rightPages.z = slide
        blankRightPages.yRot -= swing
        blankRightPages.z = slide
        val showsBinding = state.drawn != Drawn.PAGES
        val showsPages = state.drawn != Drawn.BINDING
        for (part in bindingParts) part.visible = showsBinding
        leftPages.visible = showsPages
        rightPages.visible = showsPages && state.writtenOnTheRight
        blankRightPages.visible = showsPages && !state.writtenOnTheRight
        for ((leaf, turned) in leaves.zip(listOf(state.firstLeaf, state.secondLeaf))) {
            // From the right-hand page's angle to the left-hand one's, over the gutter.
            leaf.yRot += swing * (2 * turned - 1)
            leaf.z = slide
            leaf.visible = showsPages && turned > 0.0f && turned < 1.0f
        }
    }

    /** The three pieces of the binding, each with one face outside. */
    enum class BindingPiece { FRONT, SPINE, BACK }

    /**
     * One piece of the binding, a box in its part's own units, described by its outside face: [acrossFrom] to
     * [acrossTo] runs from the spine to the fore-edge on a board, and from the back to the front across the spine;
     * [top] to [bottom] runs down it; [outside] is how far out the face lies, [thickness] in from which is the face
     * turned to the pages.
     */
    data class BindingFace(
        val piece: BindingPiece,
        val acrossFrom: Float,
        val acrossTo: Float,
        val top: Float,
        val bottom: Float,
        val outside: Float,
        val thickness: Float,
    ) {
        val width: Float get() = abs(acrossTo - acrossFrom)
        val height: Float get() = abs(bottom - top)
    }

    /** [poseStack] carried into [piece]'s part as the model last posed it, as rendering the part carries it. */
    fun poseOnto(piece: BindingPiece, poseStack: PoseStack) {
        val part = when (piece) {
            BindingPiece.FRONT -> leftLid
            BindingPiece.SPINE -> seam
            BindingPiece.BACK -> rightLid
        }
        root().translateAndRotate(poseStack)
        book.translateAndRotate(poseStack)
        part.translateAndRotate(poseStack)
    }

    /**
     * [poseStack] carried into [page]'s block of pages as the model last posed it. Its surface is Z = 0, read from
     * -Z; X runs from the gutter toward the reader's right, and Y down the page, so a point [BookRectangle][co.voik.agesandtheart.book.BookRectangle]
     * measures at `across` and `up` lies at (-across, -up, 0) on either page, however far the book is open.
     */
    fun poseOntoPage(page: BookPage, poseStack: PoseStack) {
        root().translateAndRotate(poseStack)
        book.translateAndRotate(poseStack)
        when (page) {
            BookPage.LEFT -> leftPages
            BookPage.RIGHT -> rightPages
        }.translateAndRotate(poseStack)
    }

    companion object {
        private const val BOOK = "book"
        private const val LEFT_LID = "left_lid"
        private const val RIGHT_LID = "right_lid"
        private const val SEAM = "seam"
        private const val LEFT_PAGES = "left_pages"
        private const val RIGHT_PAGES = "right_pages"
        private const val BLANK_RIGHT_PAGES = "blank_right_pages"
        private const val FIRST_LEAF = "flip_page_l"
        private const val SECOND_LEAF = "flip_page_r"

        private const val QUARTER_TURN = (PI / 2).toFloat()
        private const val HALF_TURN = PI.toFloat()

        /** How far toward the reader the pages' hinge slides as the book opens, to lie on the boards. */
        private const val PAGES_SLIDE = 1.0f

        private const val UNITS_PER_BLOCK = 16.0f
        private const val TEXTURE_SIZE = 32

        private const val BOARD_WIDTH = 6.0f
        private const val SPINE_WIDTH = 2.0f
        private const val HEIGHT = 8.0f
        private const val HALF_HEIGHT = HEIGHT / 2

        /** The shut book's length, spine to fore-edge, in model units. */
        fun shutLength(binding: BookBinding): Float = binding.boardThickness + BOARD_WIDTH

        /**
         * The binding's pieces, by their outside faces, which lie [BookBinding.boardThickness] out. Up the book is
         * -Y in every part, the front board is the left lid, which reaches -X from its hinge, and the back is the
         * right lid, which reaches +X; the spine's front edge is -X.
         */
        fun bindingFacesOf(binding: BookBinding): List<BindingFace> {
            val thickness = binding.boardThickness
            return listOf(
                BindingFace(BindingPiece.FRONT, 0.0f, -BOARD_WIDTH, -HALF_HEIGHT, HALF_HEIGHT, thickness, thickness),
                BindingFace(BindingPiece.SPINE, SPINE_WIDTH / 2, -SPINE_WIDTH / 2, -HALF_HEIGHT, HALF_HEIGHT, thickness, thickness),
                BindingFace(BindingPiece.BACK, 0.0f, BOARD_WIDTH, -HALF_HEIGHT, HALF_HEIGHT, thickness, thickness),
            )
        }

        /**
         * Both books' one texture, its binding in greyscale for the dye to colour. Laid out as the Blockbench model
         * lays it out: the front board at (0, 0), the back at (14, 0), the spine at (0, 9), the blank pages at (6, 9)
         * and the written ones at (6, 18).
         */
        val TEXTURE: Identifier = "textures/entity/book/age_book.png".location()

        /** Whether [book]'s right-hand page is written on: a descriptive book's is, a linking book's blank as its left. */
        fun writtenOnTheRight(book: ItemStack): Boolean = !book.`is`(AgeContent.LINKING_BOOK)

        private const val FRONT_BOARD_U = 0
        private const val BACK_BOARD_U = 14
        private const val BOARDS_V = 0
        private const val SPINE_U = 0
        private const val SPINE_V = 9
        private const val PAGES_U = 6
        private const val BLANK_PAGES_V = 9
        private const val WRITTEN_PAGES_V = 18

        /**
         * Where a binding box of [binding]'s thickness starts on the texture to sample its outside face where a thick
         * one does: a box's faces lie its thickness apart, so a thin one starts two across and one down, and its inside
         * face reads a pixel left of a thick one's.
         */
        private fun bindingOffset(u: Int, v: Int, binding: BookBinding): Pair<Int, Int> = when (binding) {
            BookBinding.THICK -> u to v
            BookBinding.THIN -> u + THIN_ACROSS to v + THIN_DOWN
        }

        private const val THIN_ACROSS = 2
        private const val THIN_DOWN = 1

        /** A leaf has no thickness, so it starts where a block of pages' face does, a pixel in. */
        private const val LEAF_INSET = 1

        /**
         * The book shut, each board and block of pages turned a quarter from where it lies open, its boards and
         * spine as thick as [binding]'s. Blockbench's frame is X mirrored and Y flipped from this one's, with the
         * spine across X and the pages facing -Z; the [BOOK] part turns that into the frame vanilla's book is drawn
         * in, lifted by the boards' thickness so that, open, they rest on X = 0.
         */
        fun createLayer(binding: BookBinding): LayerDefinition {
            val boards = binding.boardThickness
            val mesh = MeshDefinition()
            val book = mesh.root.addOrReplaceChild(
                BOOK,
                CubeListBuilder.create(),
                PartPose.offsetAndRotation(boards, 0.0f, 0.0f, HALF_TURN, QUARTER_TURN, 0.0f),
            )
            val (frontU, frontV) = bindingOffset(FRONT_BOARD_U, BOARDS_V, binding)
            val (backU, backV) = bindingOffset(BACK_BOARD_U, BOARDS_V, binding)
            val (spineU, spineV) = bindingOffset(SPINE_U, SPINE_V, binding)
            book.addOrReplaceChild(
                LEFT_LID,
                CubeListBuilder.create().texOffs(frontU, frontV).addBox(-BOARD_WIDTH, -HALF_HEIGHT, 0.0f, BOARD_WIDTH, HEIGHT, boards),
                PartPose.offsetAndRotation(-SPINE_WIDTH / 2, 0.0f, 0.0f, 0.0f, -QUARTER_TURN, 0.0f),
            )
            book.addOrReplaceChild(
                RIGHT_LID,
                CubeListBuilder.create().texOffs(backU, backV).addBox(0.0f, -HALF_HEIGHT, 0.0f, BOARD_WIDTH, HEIGHT, boards),
                PartPose.offsetAndRotation(SPINE_WIDTH / 2, 0.0f, 0.0f, 0.0f, QUARTER_TURN, 0.0f),
            )
            book.addOrReplaceChild(
                SEAM,
                CubeListBuilder.create().texOffs(spineU, spineV)
                    .addBox(-SPINE_WIDTH / 2, -HALF_HEIGHT, 0.0f, SPINE_WIDTH, HEIGHT, boards),
                PartPose.ZERO,
            )
            book.addOrReplaceChild(
                LEFT_PAGES,
                CubeListBuilder.create().texOffs(PAGES_U, BLANK_PAGES_V).addBox(-6.0f, -4.0f, 0.0f, 6.0f, 8.0f, 1.0f),
                PartPose.rotation(0.0f, -QUARTER_TURN, 0.0f),
            )
            for ((pages, v) in listOf(RIGHT_PAGES to WRITTEN_PAGES_V, BLANK_RIGHT_PAGES to BLANK_PAGES_V)) {
                book.addOrReplaceChild(
                    pages,
                    CubeListBuilder.create().texOffs(PAGES_U, v).addBox(0.0f, -4.0f, 0.0f, 6.0f, 8.0f, 1.0f),
                    PartPose.rotation(0.0f, QUARTER_TURN, 0.0f),
                )
            }
            for (leaf in listOf(FIRST_LEAF, SECOND_LEAF)) {
                book.addOrReplaceChild(
                    leaf,
                    CubeListBuilder.create().texOffs(PAGES_U + LEAF_INSET, BLANK_PAGES_V + LEAF_INSET).addBox(0.0f, -4.0f, 0.0f, 6.0f, 8.0f, 0.0f),
                    PartPose.rotation(0.0f, QUARTER_TURN, 0.0f),
                )
            }
            return LayerDefinition.create(mesh, TEXTURE_SIZE, TEXTURE_SIZE)
        }
    }
}
