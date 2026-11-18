package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookLiftBlockEntity
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.BookLiftShelfBlock
import co.voik.agesandtheart.book.BookLiftShelfBlockEntity
import co.voik.agesandtheart.book.BookLifts
import co.voik.agesandtheart.book.ShelfShows
import com.mojang.blaze3d.vertex.PoseStack
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
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * One shelf to draw: how far above or below its block it is, its book if it holds one, whether to draw its platform, how
 * far its book has swung open, and while it is open at all, the page its panel is on and what the panel shows.
 */
class ShelfPiece(
    val up: Float,
    val look: BookLook?,
    val withThePlatform: Boolean,
    val openness: Float = 0.0f,
    val page: BookPage? = null,
    val panel: OpenOnAStand.Panel? = null,
)

class BookLiftShelfRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var pieces: List<ShelfPiece> = emptyList()
    val platform = BlockModelRenderState()
}

/**
 * What a book lift shelf's block model cannot show: the book on it at rest, and while the loop turns, the platform
 * and its book sliding in from the block before, as a piston's head slides. A shelf whose next block along is inside
 * a frame also draws the one leaving into it, so that none is seen to vanish there or appear from nowhere. Every book lies
 * on its shelf as on a lectern, swinging open and shut as a lectern's does, and rides the lift so.
 *
 * The platform is the shelf block's own model of the platform alone ([ShelfShows.PLATFORM]), so the art draws it once.
 */
class BookLiftShelfRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<BookLiftShelfBlockEntity, BookLiftShelfRenderState>, RendersBeyondItsBlock {

    private val rendering = AgeBookRendering(context.sprites())
    private val blockModels: BlockModelResolver = context.blockModelResolver()

    override fun createRenderState(): BookLiftShelfRenderState = BookLiftShelfRenderState()

    override fun extractRenderState(
        shelf: BookLiftShelfBlockEntity,
        state: BookLiftShelfRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(shelf, state, partialTicks, cameraPosition, breakProgress)
        state.facing = shelf.blockState.getValue(BookLiftShelfBlock.FACING)
        state.pieces = piecesOf(shelf, partialTicks)
        if (state.pieces.any { it.withThePlatform }) {
            val platform = BookLifts.SHELF.defaultBlockState()
                .setValue(BookLiftShelfBlock.FACING, state.facing)
                .setValue(BookLiftShelfBlock.SHOWS, ShelfShows.PLATFORM)
            blockModels.update(state.platform, platform, PLATFORM)
        } else {
            state.platform.clear()
        }
    }

    private fun piecesOf(shelf: BookLiftShelfBlockEntity, partialTicks: Float): List<ShelfPiece> {
        val lift = shelf.base() ?: return emptyList()
        val loop = lift.loop ?: return emptyList()
        fun piece(up: Float, at: Int, withThePlatform: Boolean): ShelfPiece {
            val index = Math.floorMod(at, loop.shelves)
            val book = lift.bookOn(index)
            val look = book.takeUnless { it.isEmpty }?.let(BookLook::of) ?: return ShelfPiece(up, null, withThePlatform)
            val openness = StandSwings.opennessOf(lift, index, lift.isOpen(index), partialTicks)
            val page = LecternBooks.panelPageOf(book)
            val panel = if (page != null && openness > 0.0f) OpenOnAStand.panelOf(lift.level, lift.blockPos, index) else null
            return ShelfPiece(up, look, withThePlatform, openness, page, panel)
        }
        val here = loop.positionOf(shelf.column, shelf.row)
        val way = lift.direction
        if (way == 0) return listOf(piece(0.0f, here - lift.shift, withThePlatform = false))
        val step = ((lift.ticksIntoStep + partialTicks) / BookLiftBlockEntity.STEP_TICKS).coerceIn(0.0f, 1.0f)
        // Positions run along a column, so the block before and the block after are the ones below and above it.
        val cameFrom = loop.cellOf(here - way).second
        val entering = piece((cameFrom - shelf.row) * (1.0f - step), here - way - lift.shift, withThePlatform = true)
        val goesTo = loop.cellOf(here + way).second
        if (loop.isReachable(goesTo)) return listOf(entering)
        val leaving = piece((goesTo - shelf.row) * step, here - lift.shift, withThePlatform = true)
        return listOf(entering, leaving)
    }

    /** A shelf sliding in reaches a block below or above its own. */
    override fun renderBounds(blockEntity: BlockEntity): AABB = AABB(blockEntity.blockPos).inflate(0.0, 1.0, 0.0)

    override fun submit(state: BookLiftShelfRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        for (piece in state.pieces) {
            poseStack.pushPose()
            poseStack.translate(0.0f, piece.up, 0.0f)
            if (piece.withThePlatform && !state.platform.isEmpty) {
                state.platform.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            }
            // As a lectern holds a book, shut or open (Jonah, 2026-11-19): on the slope, read from the lift's front.
            piece.look?.let { look ->
                OpenOnAStand.poseOpen(poseStack, OPEN_AT, OpenOnAStand.readerYRotOf(state.facing), look.binding, piece.openness)
                OpenOnAStand.submit(rendering, look, piece.page, piece.panel, piece.openness, poseStack, collector, state.lightCoords)
            }
            poseStack.popPose()
        }
    }

    private companion object {
        val PLATFORM: BlockDisplayContext = BlockDisplayContext.create()
        const val NO_OUTLINE = 0

        /** Where a book on the shelf has its middle, as `BookLiftBlockEntity.openBookPose` says. */
        val OPEN_AT = Vec3(0.5, BookLiftBlockEntity.OPEN_BOOK_UP, 0.5)
    }
}
