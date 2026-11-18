package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BinderClamps
import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.book.BookFinderBlockEntity
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.Bookbinder
import co.voik.agesandtheart.book.BookbinderBlock
import co.voik.agesandtheart.book.FrameTask
import co.voik.agesandtheart.book.LecternBookPlane
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.WallPoint
import co.voik.agesandtheart.client.panel.BookMaps
import co.voik.agesandtheart.client.panel.OpenBookPanels
import co.voik.agesandtheart.client.panel.PanelComposite
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.client.renderer.block.BlockModelResolver
import net.minecraft.client.renderer.block.model.BlockDisplayContext
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f

class BookFinderRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var task: FrameTask = FrameTask.RESTING

    /** How far through its task the frame is, 0 to 1; a task without an end stays at 0. */
    var progress: Float = 0.0f

    /** Where the frame is across the face of the wall, from the finder's own space. */
    var at: WallPoint = WallPoint(0.0, 0.0)

    /** The slot it draws a book from or pushes one into, while it does. */
    var slot: Int = 0

    var look: BookLook? = null
    var binding: BookBinding = BookBinding.THICK
    var panelPage: BookPage? = null
    var isTheShownOne: Boolean = false
    var mapPicture: RenderType? = null
    var frameLight: Int = 0
    val press = BlockModelRenderState()
}

/**
 * A book finder's frame, working (design §7.8.3): across the face of the wall along the path its finder
 * sent, drawing a book out of its slot into its clamps, bringing it back shut and upright, and turning it open and up
 * as a lectern holds a book, where it is read as a lectern's is — its panel too. The frame stands in on a small
 * bookbinder's press until the art.
 *
 * Drawn from the finder in the frame its model is, facing north: the wall's right is west, and the shelves to the south.
 */
class BookFinderRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BookFinderBlockEntity, BookFinderRenderState>, RendersBeyondItsBlock {

    private val rendering = AgeBookRendering(context.sprites())
    private val blockModels: BlockModelResolver = context.blockModelResolver()

    override fun createRenderState(): BookFinderRenderState = BookFinderRenderState()

    override fun extractRenderState(
        finder: BookFinderBlockEntity,
        state: BookFinderRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(finder, state, partialTicks, cameraPosition, breakProgress)
        val level = finder.level ?: return
        state.facing = finder.facing
        state.task = finder.task
        val elapsed = (level.gameTime - finder.startedAt) + partialTicks
        val lasts = finder.ticksOf(finder.task)
        state.progress = if (lasts == Int.MAX_VALUE) 0.0f else (elapsed / lasts).coerceIn(0.0f, 1.0f)
        state.at = when {
            finder.task.travels -> finder.path?.at(elapsed * BookFinderBlockEntity.BLOCKS_A_TICK) ?: WallPoint(0.0, 0.0)
            finder.task == FrameTask.PULLING || finder.task == FrameTask.PUSHING ->
                finder.errand?.let { WallPoint.of(finder.blockPos, finder.facing, it.shelf) } ?: WallPoint(0.0, 0.0)
            else -> WallPoint(0.0, 0.0)
        }
        state.slot = finder.errand?.slot ?: 0
        val right = finder.facing.counterClockWise
        val frameSpace = finder.blockPos.relative(right, Math.round(state.at.across).toInt()).above(Math.round(state.at.up).toInt())
        state.frameLight = LightCoordsUtil.getLightCoords(level, frameSpace)
        val book = finder.carried
        state.look = book.takeUnless { it.isEmpty }?.let(BookLook::of)
        state.binding = BookBinding.of(book)
        state.panelPage = LecternBooks.panelPageOf(book)
        val clamps = Bookbinder.BLOCK.defaultBlockState()
            .setValue(BookbinderBlock.FACING, Direction.NORTH)
            .setValue(BookbinderBlock.CLAMPS, if (state.look != null && state.binding == BookBinding.THIN) BinderClamps.NARROW else BinderClamps.WIDE)
        blockModels.update(state.press, clamps, PRESS)
        extractThePanel(finder, state)
    }

    /** As a lectern's: the panel the client shows, or the mist, or where panels are maps, the book's own map. */
    private fun extractThePanel(finder: BookFinderBlockEntity, state: BookFinderRenderState) {
        state.isTheShownOne = false
        state.mapPicture = null
        if (state.panelPage == null || openness(state) <= 0.0f) return
        val thisBook = BookBeingRead.InAFinder(finder.blockPos)
        val inThePlayersWorld = finder.level === Minecraft.getInstance().level
        if (BookMaps.isOn) {
            state.mapPicture = if (inThePlayersWorld) BookMaps.pictureOnAPage(thisBook) else null
            if (state.mapPicture == null) OpenBookPanels.sawAMistedBook()
            return
        }
        state.isTheShownOne = inThePlayersWorld && state.task == FrameTask.PRESENTING && OpenBookPanels.shown == thisBook
        if (state.isTheShownOne) OpenBookPanels.sawTheShownBook() else OpenBookPanels.sawAMistedBook()
        if (state.isTheShownOne && PanelComposite.liveView() == null) OpenBookPanels.sawAMistedBook()
    }

    override fun shouldRenderOffScreen(): Boolean = true

    /** Somewhere on its wall, which is at most [co.voik.agesandtheart.book.BookFinderWall.MOST_SHELVES] shelves. */
    override fun renderBounds(blockEntity: BlockEntity): AABB = AABB(blockEntity.blockPos).inflate(WALL_REACH)

    override fun submit(state: BookFinderRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val frame = frameMiddle(state)
        poseStack.pushPose()
        poseStack.mulPose(facingTurn(state.facing))
        poseStack.translate(frame.x - PRESS_SIZE / 2, frame.y - PRESS_BELOW, frame.z - PRESS_SIZE / 2)
        poseStack.scale(PRESS_SIZE, PRESS_SIZE, PRESS_SIZE)
        state.press.submit(poseStack, collector, state.frameLight, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
        poseStack.popPose()

        val look = state.look ?: return
        val open = openness(state)
        val bookPose = bookPose(state, frame, open)
        val bookState = BookOnTheSlope.bookAt(open)
        val light = if (state.task.travels || state.task == FrameTask.PULLING || state.task == FrameTask.PUSHING) state.frameLight else state.lightCoords
        poseStack.pushPose()
        poseStack.mulPose(bookPose)
        rendering.submit(look, bookState, poseStack, collector, light)
        state.panelPage?.takeIf { open > 0.0f }?.let { page ->
            PanelOnThePage.submit(page, state.isTheShownOne, state.mapPicture, rendering.modelFor(look.binding), bookState, poseStack, collector)
        }
        poseStack.popPose()
    }

    /** The middle of the frame, in the model's frame facing north, from the finder's corner. */
    private fun frameMiddle(state: BookFinderRenderState): Vector3f =
        Vector3f((MIDDLE - state.at.across).toFloat(), (MIDDLE + state.at.up).toFloat(), MIDDLE)

    /** How far open the book is: shut until the second half of its turning, and open while it is held. */
    private fun openness(state: BookFinderRenderState): Float = when (state.task) {
        FrameTask.OPENING -> ((state.progress - TURNED_AT) / (1.0f - TURNED_AT)).coerceIn(0.0f, 1.0f)
        FrameTask.CLOSING -> ((1.0f - state.progress - TURNED_AT) / (1.0f - TURNED_AT)).coerceIn(0.0f, 1.0f)
        FrameTask.PRESENTING -> 1.0f
        else -> 0.0f
    }

    /** Where the book is drawn, from the finder's corner, in the world's own frame. */
    private fun bookPose(state: BookFinderRenderState, frame: Vector3f, open: Float): Matrix4f {
        val inTheClamps = clamped(state.facing, frame, Vector3f())
        return when (state.task) {
            FrameTask.PULLING -> clamped(state.facing, frame, slotOffset(state.slot).mul(1.0f - eased(state.progress)))
            FrameTask.PUSHING -> clamped(state.facing, frame, slotOffset(state.slot).mul(eased(state.progress)))
            FrameTask.OPENING, FrameTask.CLOSING -> {
                val turned = if (state.task == FrameTask.OPENING) state.progress else 1.0f - state.progress
                if (turned >= TURNED_AT) heldOpen(state, open) else between(inTheClamps, heldOpen(state, 0.0f), eased(turned / TURNED_AT))
            }
            FrameTask.PRESENTING -> heldOpen(state, 1.0f)
            else -> inTheClamps
        }
    }

    /** Standing upright in the clamps, spine out from the wall, [offset] from the middle of them. */
    private fun clamped(facing: Direction, frame: Vector3f, offset: Vector3f): Matrix4f {
        val pose = PoseStack()
        pose.mulPose(facingTurn(facing))
        pose.translate(frame.x + offset.x, frame.y + offset.y, frame.z + offset.z)
        // The shut model's spine is its origin and its fore-edge along +X: turned so the fore-edge points into the wall.
        pose.rotate(Axis.YP.rotationDegrees(-QUARTER_TURN))
        pose.scale(CLAMPED_SCALE, CLAMPED_SCALE, CLAMPED_SCALE)
        pose.translate(-HALF_A_BOARD, 0.0f, 0.0f)
        return Matrix4f(pose.last().pose())
    }

    /** As a lectern holds a book [open] of the way open, lowered to the middle of the finder's space. */
    private fun heldOpen(state: BookFinderRenderState, open: Float): Matrix4f {
        val pose = PoseStack()
        pose.translate(MIDDLE.toDouble(), LecternBookPlane.CENTRE_HEIGHT + BOOK_LOWER_THAN_A_LECTERNS, MIDDLE.toDouble())
        pose.rotate(Axis.YP.rotationDegrees(-state.facing.clockWise.toYRot()))
        pose.rotate(Axis.ZP.rotationDegrees(LecternBookPlane.TILT_DEGREES.toFloat()))
        pose.translate(-LecternBookPlane.ONTO_THE_SLOPE, -LecternBookPlane.DOWN_THE_SLOPE, 0.0)
        BookOnTheSlope.layOnItsBack(open, state.binding, pose)
        return Matrix4f(pose.last().pose())
    }

    /** [from] turned and moved toward [to], [share] of the way. */
    private fun between(from: Matrix4f, to: Matrix4f, share: Float): Matrix4f {
        val fromTranslation = from.getTranslation(Vector3f())
        val toTranslation = to.getTranslation(Vector3f())
        val fromScale = from.getScale(Vector3f())
        val toScale = to.getScale(Vector3f())
        val fromTurn = from.getNormalizedRotation(Quaternionf())
        val toTurn = to.getNormalizedRotation(Quaternionf())
        return Matrix4f()
            .translation(fromTranslation.lerp(toTranslation, share))
            .rotate(fromTurn.slerp(toTurn, share))
            .scale(fromScale.lerp(toScale, share))
    }

    /** From the middle of the clamps to the middle of the book's [slot] in the shelf behind, in the model's frame. */
    private fun slotOffset(slot: Int): Vector3f {
        val column = slot % SLOT_COLUMNS
        val row = slot / SLOT_COLUMNS
        // The first column is the left as the wall is faced, which in the model's frame is east.
        return Vector3f(SLOT_ACROSS[column], if (row == 0) SLOT_UP else -SLOT_UP, SLOT_DEPTH)
    }

    private fun facingTurn(facing: Direction): Matrix4f = Matrix4f()
        .translate(MIDDLE, MIDDLE, MIDDLE)
        .rotate(Axis.YP.rotationDegrees(-BookbinderRenderer.clockwiseTurnOf(facing)))
        .translate(-MIDDLE, -MIDDLE, -MIDDLE)

    private fun eased(progress: Float): Float = progress * progress * (3 - 2 * progress)

    private companion object {
        val PRESS: BlockDisplayContext = BlockDisplayContext.create()
        const val MIDDLE = 0.5f
        const val QUARTER_TURN = 90.0f
        const val PIXEL = 1.0f / 16.0f
        const val NO_OUTLINE = 0
        const val WALL_REACH = 32.0

        /** The stand-in press, scaled down under the book. */
        const val PRESS_SIZE = 0.4f
        const val PRESS_BELOW = 0.3f

        /** A book in the clamps is drawn the size of one on a chiselled shelf. */
        const val CLAMPED_SCALE = 0.6f
        const val HALF_A_BOARD = 3.0f * PIXEL

        /** How much of its turning the book spends coming from the clamps to the slope, before it opens. */
        const val TURNED_AT = 0.5f

        /** As the finder holds it, from `BookFinderBlockEntity`. */
        const val BOOK_LOWER_THAN_A_LECTERNS = 0.5 - LecternBookPlane.CENTRE_HEIGHT

        /** A chiselled shelf's slots: three across, from the left, and two rows, the top first. */
        const val SLOT_COLUMNS = 3
        val SLOT_ACROSS = floatArrayOf(0.3125f, -0.03125f, -0.34375f)
        const val SLOT_UP = 0.25f

        /** Into the shelf behind, where a book stands in its slot. */
        const val SLOT_DEPTH = 0.6f
    }
}
