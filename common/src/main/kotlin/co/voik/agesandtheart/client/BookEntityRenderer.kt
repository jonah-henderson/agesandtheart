package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookEntity
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.client.panel.OpenBookPanels
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.`object`.book.BookModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture

/** What the renderer needs to know, extracted off the entity before drawing. */
class BookRenderState : EntityRenderState() {
    /** The way its reader was facing when they let it fall. */
    var yRot: Float = 0.0f

    /** Which page carries the book's panel. */
    var panelPage: BookPage? = null

    /** Whether this is the book whose panel the client is showing — the one that may show its Age. */
    var isTheShownOne: Boolean = false
}

/**
 * A book resting on the ground: the open book a lectern draws ([LecternBookRenderer]), fallen flat on its
 * back and turned to read the way its reader was facing, with its panel on the page as a lectern's has. The
 * cover is still the enchanting table's; ours is asset work.
 */
class BookEntityRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<BookEntity, BookRenderState>(context) {

    private val sprites = context.sprites
    private val bookModel = BookModel(context.bakeLayer(ModelLayers.BOOK))

    override fun createRenderState(): BookRenderState = BookRenderState()

    override fun extractRenderState(entity: BookEntity, state: BookRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.yRot = entity.yRot
        state.panelPage = LecternBooks.panelPageOf(entity.book)
        // Only a book in the player's own world may show the Age, as only a lectern there may.
        val inThePlayersWorld = entity.level() === Minecraft.getInstance().level
        state.isTheShownOne = inThePlayersWorld && OpenBookPanels.shown == BookBeingRead.OnTheGround(entity.id)
        if (state.panelPage == null) return
        if (state.isTheShownOne) OpenBookPanels.sawTheShownBook() else OpenBookPanels.sawAMistedBook()
    }

    override fun submit(
        state: BookRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.0f, LIFT, 0.0f)
        // The model's pages face +X and its top runs up +Y. A quarter turn about Z lays it face up; the turn
        // about Y then sends its top the way the reader was looking, so it reads upright from where they stood.
        poseStack.rotate(Axis.YP.rotationDegrees(QUARTER_TURN - state.yRot))
        poseStack.rotate(Axis.ZP.rotationDegrees(QUARTER_TURN))
        collector.submitModel(
            bookModel,
            PanelOnThePage.pose(FLAT_OPEN),
            poseStack,
            state.lightCoords,
            OverlayTexture.NO_OVERLAY,
            UNTINTED,
            EnchantTableRenderer.BOOK_TEXTURE,
            sprites,
            NO_OUTLINE,
        )
        state.panelPage?.let { PanelOnThePage.submit(it, state.isTheShownOne, FLAT_OPEN, poseStack, collector) }
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    private companion object {
        /** Off the ground just enough not to z-fight with the block below. */
        const val LIFT = 0.01f
        const val QUARTER_TURN = 90.0f

        /** Both covers flat on the ground, where a lectern's stand at vanilla's angle. */
        const val FLAT_OPEN = (Math.PI / 2).toFloat()

        const val UNTINTED = -1
        const val NO_OUTLINE = 0
    }
}
