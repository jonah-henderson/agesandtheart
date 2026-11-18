package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.PaternosterBlockEntity
import co.voik.agesandtheart.book.PaternosterShelfBlock
import co.voik.agesandtheart.book.PaternosterShelfBlockEntity
import co.voik.agesandtheart.book.Paternosters
import co.voik.agesandtheart.book.ShelfShows
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
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** One shelf to draw: how far above or below its block it is, its book if it holds one, and whether to draw its platform. */
class ShelfPiece(val up: Float, val look: BookLook?, val withThePlatform: Boolean)

class PaternosterShelfRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    var pieces: List<ShelfPiece> = emptyList()
    val platform = BlockModelRenderState()
}

/**
 * What a paternoster shelf's block model cannot show: the book on it at rest, and while the loop turns, the platform
 * and its book sliding in from the block before, as a piston's head slides. A shelf whose next block along is inside
 * the base or the cap also draws the one leaving into it, so that none is seen to vanish there or appear from nowhere.
 *
 * The platform is the shelf block's own model of the platform alone ([ShelfShows.PLATFORM]), so the art draws it once.
 */
class PaternosterShelfRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<PaternosterShelfBlockEntity, PaternosterShelfRenderState>, RendersBeyondItsBlock {

    private val rendering = AgeBookRendering(context.sprites())
    private val blockModels: BlockModelResolver = context.blockModelResolver()

    override fun createRenderState(): PaternosterShelfRenderState = PaternosterShelfRenderState()

    override fun extractRenderState(
        shelf: PaternosterShelfBlockEntity,
        state: PaternosterShelfRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(shelf, state, partialTicks, cameraPosition, breakProgress)
        state.facing = shelf.blockState.getValue(PaternosterShelfBlock.FACING)
        state.pieces = piecesOf(shelf, partialTicks)
        if (state.pieces.any { it.withThePlatform }) {
            val platform = Paternosters.SHELF.defaultBlockState()
                .setValue(PaternosterShelfBlock.FACING, state.facing)
                .setValue(PaternosterShelfBlock.SHOWS, ShelfShows.PLATFORM)
            blockModels.update(state.platform, platform, PLATFORM)
        } else {
            state.platform.clear()
        }
    }

    private fun piecesOf(shelf: PaternosterShelfBlockEntity, partialTicks: Float): List<ShelfPiece> {
        val paternoster = shelf.base() ?: return emptyList()
        val loop = paternoster.loop ?: return emptyList()
        fun lookOf(index: Int) = paternoster.bookOn(Math.floorMod(index, loop.shelves)).takeUnless { it.isEmpty }?.let(BookLook::of)
        val here = loop.positionOf(shelf.column, shelf.row)
        val way = paternoster.direction
        if (way == 0) return listOf(ShelfPiece(0.0f, lookOf(here - paternoster.shift), withThePlatform = false))
        val step = ((paternoster.ticksIntoStep + partialTicks) / PaternosterBlockEntity.STEP_TICKS).coerceIn(0.0f, 1.0f)
        // Positions run along a column, so the block before and the block after are the ones below and above it.
        val cameFrom = loop.cellOf(here - way).second
        val entering = ShelfPiece((cameFrom - shelf.row) * (1.0f - step), lookOf(here - way - paternoster.shift), withThePlatform = true)
        val goesTo = loop.cellOf(here + way).second
        if (loop.isReachable(goesTo)) return listOf(entering)
        val leaving = ShelfPiece((goesTo - shelf.row) * step, lookOf(here - paternoster.shift), withThePlatform = true)
        return listOf(entering, leaving)
    }

    /** A shelf sliding in reaches a block below or above its own. */
    override fun renderBounds(blockEntity: BlockEntity): AABB = AABB(blockEntity.blockPos).inflate(0.0, 1.0, 0.0)

    override fun submit(state: PaternosterShelfRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        for (piece in state.pieces) {
            poseStack.pushPose()
            poseStack.translate(0.0f, piece.up, 0.0f)
            if (piece.withThePlatform && !state.platform.isEmpty) {
                state.platform.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
            }
            piece.look?.let { look ->
                poseStack.translate(MIDDLE, PLATFORM_TOP + BOOK_THICKNESS / 2, MIDDLE)
                poseStack.rotate(Axis.YP.rotationDegrees(-BookbinderRenderer.clockwiseTurnOf(state.facing)))
                // Lying on its back board as on a book wheel's tray: its spine to the front and its head to the left.
                poseStack.rotate(Axis.YP.rotationDegrees(-QUARTER_TURN))
                poseStack.rotate(Axis.XP.rotationDegrees(-QUARTER_TURN))
                poseStack.scale(BOOK_SCALE, BOOK_SCALE, BOOK_SCALE)
                poseStack.translate(-HALF_A_BOARD, 0.0f, 0.0f)
                rendering.submit(look, SHUT, poseStack, collector, state.lightCoords)
            }
            poseStack.popPose()
        }
    }

    private companion object {
        val SHUT = AgeBookModel.State(0.0f)
        val PLATFORM: BlockDisplayContext = BlockDisplayContext.create()
        const val MIDDLE = 0.5f
        const val QUARTER_TURN = 90.0f
        const val PIXEL = 1.0f / 16.0f
        const val NO_OUTLINE = 0

        /** The stand-in platform's top, two pixels up; the art's to move with its model. */
        const val PLATFORM_TOP = 2.0f * PIXEL

        const val BOOK_SCALE = 0.75f
        const val BOOK_THICKNESS = 2.0f * PIXEL * BOOK_SCALE
        const val HALF_A_BOARD = 3.0f * PIXEL
    }
}
