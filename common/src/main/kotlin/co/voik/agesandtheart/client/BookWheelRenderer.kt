package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.BookWheelBlock
import co.voik.agesandtheart.book.BookWheelBlockEntity
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.WheelPiece
import co.voik.agesandtheart.book.CarouselShelves
import co.voik.agesandtheart.book.ModelledShelves
import co.voik.agesandtheart.book.WheelShelves
import co.voik.agesandtheart.book.WheelForm
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.client.renderer.block.BlockModelResolver
import net.minecraft.client.renderer.block.model.BlockDisplayContext
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

class BookWheelRenderState : BlockEntityRenderState() {
    var form: WheelForm = WheelForm.SMALL
    var facing: Direction = Direction.NORTH

    /** Each slot's book, or null where it is empty. */
    var looks: List<BookLook?> = emptyList()

    /** The tray at the front, fractional while the wheel turns. */
    var shownFront: Float = 0.0f

    /** How far each slot's book has swung open, shut (0) to open (1). */
    var openness: FloatArray = FloatArray(0)

    /** Each slot's book's panel while it is open at all, where it is one of ours. */
    var panels: List<OpenOnAStand.Panel?> = emptyList()

    /** Which page each slot's book carries its panel on, where it is one of ours. */
    var pages: List<BookPage?> = emptyList()

    /** The wheel's moving parts, in its wood: the turning wheel or a carousel's arm, and a shelf. */
    val shelf = BlockModelRenderState()
    val wheel = BlockModelRenderState()

    /** The light where each of the wheel's shelves is passing, which it and its book are drawn in. */
    var shelfLights: IntArray = IntArray(0)
}

/**
 * A book wheel's moving parts and its books. The block's own model is only the stand; this draws the wheel turning on
 * its axle, or the carousel's arms round its post, and the shelves carried round ([ModelledShelves]), each book as itself
 * ([AgeBookRendering]) lying as a lectern holds one (Jonah, 2026-11-19): its front board to the reader while it is shut,
 * swinging open and shut as a lectern's does.
 *
 * On a wheel whose axle runs across, every shelf and book is kept level as it goes round, as the cars of a Ferris wheel
 * are, read from the front; on the carousel each book stands against its arm's board and turns with it, read from out
 * beyond it. Each shelf and its book is lit by the block it is passing through.
 */
class BookWheelRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BookWheelBlockEntity, BookWheelRenderState>, RendersBeyondItsBlock {

    private val rendering = AgeBookRendering(context.sprites())
    private val blockModels: BlockModelResolver = context.blockModelResolver()

    override fun createRenderState(): BookWheelRenderState = BookWheelRenderState()

    override fun extractRenderState(
        wheel: BookWheelBlockEntity,
        state: BookWheelRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(wheel, state, partialTicks, cameraPosition, breakProgress)
        state.form = wheel.form
        state.facing = wheel.blockState.getValue(BookWheelBlock.FACING)
        state.looks = List(wheel.form.trays) { slot -> wheel.bookIn(slot).takeUnless { it.isEmpty }?.let(BookLook::of) }
        state.shownFront = wheel.turning.shownAt(partialTicks, wheel.frontTray)
        state.pages = List(wheel.form.trays) { slot -> LecternBooks.panelPageOf(wheel.bookIn(slot)) }
        state.openness = FloatArray(wheel.form.trays) { slot ->
            if (wheel.bookIn(slot).isEmpty) 0.0f else StandSwings.opennessOf(wheel, slot, wheel.isOpen(slot), partialTicks)
        }
        state.panels = List(wheel.form.trays) { slot ->
            val isShowingAPage = state.pages[slot] != null && state.openness[slot] > 0.0f
            if (isShowingAPage) OpenOnAStand.panelOf(wheel.level, wheel.blockPos, slot) else null
        }
        blockModels.update(state.shelf, wheel.blockState.setValue(BookWheelBlock.PIECE, WheelPiece.SHELF), SHELF)
        blockModels.update(state.wheel, wheel.blockState.setValue(BookWheelBlock.PIECE, WheelPiece.WHEEL), WHEEL)
        state.shelfLights = shelfLightsOf(wheel, easedRound(state.shownFront), state.lightCoords)
    }

    /** The light in the block each tray's shelf is passing through, or [holders] where there is no level to ask. */
    private fun shelfLightsOf(wheel: BookWheelBlockEntity, front: Double, holders: Int): IntArray {
        val level = wheel.level ?: return IntArray(wheel.form.trays) { holders }
        val facing = wheel.blockState.getValue(BookWheelBlock.FACING)
        return IntArray(wheel.form.trays) { tray ->
            val shelfMiddle = when (val shelves = wheel.form.shelves) {
                is WheelShelves -> shelves.poseAt(tray - front).middle
                CarouselShelves -> CarouselShelves.boardMiddleAt(tray - front)
            }
            val inTheBlock = ModelledShelves.toTheBlock(facing, shelfMiddle)
            val passing = wheel.blockPos.offset(floor(inTheBlock.x).toInt(), floor(inTheBlock.y).toInt(), floor(inTheBlock.z).toInt())
            LightCoordsUtil.getLightCoords(level, passing)
        }
    }

    /** A wheel reaches past its own block, and is drawn while that one is out of sight. */
    override fun shouldRenderOffScreen(): Boolean = true

    /** The large wheel's model reaches a block past the one holding it every way. */
    override fun renderBounds(blockEntity: BlockEntity): AABB = AABB(blockEntity.blockPos).inflate(1.0)

    override fun submit(state: BookWheelRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val front = easedRound(state.shownFront)
        poseStack.pushPose()
        poseStack.translate(HALF, 0.0, HALF)
        poseStack.rotate(Axis.YP.rotationDegrees(-turnFromNorthOf(state.facing)))
        poseStack.translate(-HALF, 0.0, -HALF)
        when (val shelves = state.form.shelves) {
            is WheelShelves -> submitOnAnAxleAcross(shelves, front, state, poseStack, collector)
            CarouselShelves -> submitCarousel(front, state, poseStack, collector)
        }
        poseStack.popPose()
    }

    /** A wheel turning on an axle running across, and its shelves carried round level, each book on its shelf. */
    private fun submitOnAnAxleAcross(
        shelves: WheelShelves,
        front: Double,
        state: BookWheelRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        poseStack.pushPose()
        val axle = shelves.axle.scale(1.0 / PIXELS)
        poseStack.translate(axle.x, axle.y, axle.z)
        poseStack.rotate(Axis.XP.rotationDegrees(shelves.turnedDegrees(front).toFloat()))
        poseStack.translate(-axle.x, -axle.y, -axle.z)
        state.wheel.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
        poseStack.popPose()

        for (tray in 0 until state.form.trays) {
            val pose = shelves.poseAt(tray - front)
            val light = state.shelfLights.getOrElse(tray) { state.lightCoords }
            poseStack.pushPose()
            val middle = pose.middle.scale(1.0 / PIXELS)
            poseStack.translate(middle.x, middle.y, middle.z)
            poseStack.rotate(Axis.XP.rotationDegrees(pose.tiltDegrees.toFloat()))
            poseStack.translate(-HALF, -HALF, -HALF)
            state.shelf.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            poseStack.popPose()
            val top = pose.top.scale(1.0 / PIXELS)
            submitBook(tray, top, OpenOnAStand.readerYRotOf(Direction.NORTH), pose.leanedBackDegrees, light, state, poseStack, collector)
        }
    }

    /** The carousel's arms turning round its post, each with its board, and each book standing against its board. */
    private fun submitCarousel(front: Double, state: BookWheelRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        val post = CarouselShelves.AXLE.scale(1.0 / PIXELS)
        for (tray in 0 until state.form.trays) {
            val position = tray - front
            val light = state.shelfLights.getOrElse(tray) { state.lightCoords }
            poseStack.pushPose()
            // The diagonal arms a hair lower, so where the arms meet by the post no two lie in one plane (Jonah).
            val lowered = if (tray % 2 == 1) DIAGONAL_ARMS_LOWERED else 0.0
            poseStack.translate(post.x, -lowered, post.z)
            poseStack.rotate(Axis.YP.rotationDegrees(CarouselShelves.armTurnedDegrees(position).toFloat()))
            poseStack.translate(-post.x, 0.0, -post.z)
            state.wheel.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            poseStack.popPose()
            val readerYRot = OpenOnAStand.readerYRotOf(CarouselShelves.outwardAt(position))
            val book = CarouselShelves.bookAt(position).scale(1.0 / PIXELS)
            submitBook(tray, book, readerYRot, CarouselShelves.LEANED_BACK_DEGREES, light, state, poseStack, collector)
        }
    }

    /** The book in [slot], if any, lying on a surface whose middle is [top], leaning back [leanedBack] from upright. */
    private fun submitBook(
        slot: Int,
        top: Vec3,
        readerYRot: Float,
        leanedBack: Double,
        light: Int,
        state: BookWheelRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
    ) {
        val look = state.looks.getOrNull(slot) ?: return
        val open = state.openness[slot]
        poseStack.pushPose()
        OpenOnAStand.poseLyingOn(poseStack, top, readerYRot, leanedBack.toFloat(), look.binding, open)
        OpenOnAStand.submit(rendering, look, state.pages[slot], state.panels[slot], open, poseStack, collector, light)
        poseStack.popPose()
    }

    private companion object {
        const val HALF = 0.5
        const val PIXELS = 16.0
        const val NO_OUTLINE = 0

        /** How much lower a carousel's diagonal arms are drawn: 0.02 of a pixel. */
        const val DIAGONAL_ARMS_LOWERED = 0.02 / 16.0
        val SHELF: BlockDisplayContext = BlockDisplayContext.create()
        val WHEEL: BlockDisplayContext = BlockDisplayContext.create()

        /** How far a blockstate turns a model built facing north to face [facing], clockwise from above. */
        fun turnFromNorthOf(facing: Direction): Float = (facing.toYRot() + 180.0f) % 360.0f

        /** The tray at the front, eased in and out of each quarter turn so the shelves start and settle gently. */
        fun easedRound(shownFront: Float): Double {
            val whole = floor(shownFront.toDouble())
            val share = shownFront - whole
            return whole + share * share * (3.0 - 2.0 * share)
        }
    }
}
