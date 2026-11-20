package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookLiftBlockEntity
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.BookLiftShelfBlock
import co.voik.agesandtheart.book.BookLiftShelfBlockEntity
import co.voik.agesandtheart.book.LiftPlatform
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
import net.minecraft.world.level.block.ChainBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

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

    /** The chain the shelf was, and how far up its column it has moved this step, a block's length at most. */
    val chain = BlockModelRenderState()
    var chainMoved: Float = 0.0f

    /** Whether the chain is drawn a block lower too, from the base, which the lowest shelf's does. */
    var isLowest: Boolean = false
}

/**
 * What a book lift shelf's block model cannot show: the book on it at rest, and while the loop turns, the platform
 * and its book sliding in from the block before, as a piston's head slides. A shelf whose next block along is inside
 * a frame also draws the one leaving into it, so that none is seen to vanish there or appear from nowhere. Every book lies
 * on its platform's slope as on a lectern ([LiftPlatform]), swinging open and shut as a lectern's does, and rides the
 * lift so.
 *
 * The platform is the shelf block's own model ([ShelfShows.PLATFORM]), so the art draws it once. The chains are the
 * chain the shelf was, one in each corner a pixel in from the block's edges, and run with the platforms: each shelf
 * draws a block of chain moved as far as its column has, and the lowest one the block below it too, into the base.
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
        blockModels.update(state.chain, upright(shelf.chain), CHAIN)
        state.chainMoved = chainMovedOf(shelf, partialTicks)
        state.isLowest = shelf.row == 1
        if (state.pieces.any { it.withThePlatform }) {
            // The shelf's own state, so the platform keeps its chain's tint as it slides.
            val platform = shelf.blockState.setValue(BookLiftShelfBlock.SHOWS, ShelfShows.PLATFORM)
            blockModels.update(state.platform, platform, PLATFORM)
        } else {
            state.platform.clear()
        }
    }

    private fun upright(chain: BlockState): BlockState =
        if (chain.hasProperty(ChainBlock.AXIS)) chain.setValue(ChainBlock.AXIS, Direction.Axis.Y) else chain

    /** How far up its column [shelf]'s chain has moved into this step, as a part of a block from 0 to 1. */
    private fun chainMovedOf(shelf: BookLiftShelfBlockEntity, partialTicks: Float): Float {
        val lift = shelf.base() ?: return 0.0f
        if (lift.direction == 0) return 0.0f
        val step = ((lift.ticksIntoStep + partialTicks) / BookLiftBlockEntity.STEP_TICKS).coerceIn(0.0f, 1.0f)
        // On is up the first column and down the second.
        val upThisColumn = if (shelf.column == 0) lift.direction else -lift.direction
        val moved = upThisColumn * step
        return moved - floor(moved)
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
        val cameFrom = loop.cellOf(here - way).row
        val entering = piece((cameFrom - shelf.row) * (1.0f - step), here - way - lift.shift, withThePlatform = true)
        val goesTo = loop.cellOf(here + way).row
        if (loop.isReachable(goesTo)) return listOf(entering)
        val leaving = piece((goesTo - shelf.row) * step, here - lift.shift, withThePlatform = true)
        return listOf(entering, leaving)
    }

    /** A shelf sliding in reaches a block below or above its own. */
    override fun renderBounds(blockEntity: BlockEntity): AABB = AABB(blockEntity.blockPos).inflate(0.0, 1.0, 0.0)

    override fun submit(state: BookLiftShelfRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        submitChains(state, poseStack, collector)
        for (piece in state.pieces) {
            poseStack.pushPose()
            poseStack.translate(0.0f, piece.up, 0.0f)
            if (piece.withThePlatform && !state.platform.isEmpty) {
                state.platform.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            }
            // As a lectern holds a book, shut or open (Jonah, 2026-11-19): on the slope, read from the lift's front.
            piece.look?.let { look ->
                val middle = LiftPlatform.bookMiddle(state.facing)
                OpenOnAStand.poseOpen(poseStack, middle, OpenOnAStand.readerYRotOf(state.facing), look.binding, piece.openness)
                OpenOnAStand.submit(rendering, look, piece.page, piece.panel, piece.openness, poseStack, collector, state.lightCoords)
            }
            poseStack.popPose()
        }
    }

    private fun submitChains(state: BookLiftShelfRenderState, poseStack: PoseStack, collector: SubmitNodeCollector) {
        if (state.chain.isEmpty) return
        val blocksDrawn = if (state.isLowest) listOf(0.0f, -1.0f) else listOf(0.0f)
        for (corner in CORNERS) for (block in blocksDrawn) {
            poseStack.pushPose()
            poseStack.translate(corner.x, (state.chainMoved + block).toDouble(), corner.z)
            state.chain.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            poseStack.popPose()
        }
    }

    private companion object {
        val PLATFORM: BlockDisplayContext = BlockDisplayContext.create()
        val CHAIN: BlockDisplayContext = BlockDisplayContext.create()
        const val NO_OUTLINE = 0

        /** From the middle of the block, where a chain's model stands, to each corner's chain. */
        private const val TO_A_CORNER =
            (BookLiftShelfBlock.CHAIN_INSET + BookLiftShelfBlock.CHAIN_WIDTH / 2.0 - 8.0) / 16.0
        val CORNERS: List<Vec3> = listOf(
            Vec3(TO_A_CORNER, 0.0, TO_A_CORNER),
            Vec3(-TO_A_CORNER, 0.0, TO_A_CORNER),
            Vec3(TO_A_CORNER, 0.0, -TO_A_CORNER),
            Vec3(-TO_A_CORNER, 0.0, -TO_A_CORNER),
        )
    }
}
