package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookBinding
import co.voik.agesandtheart.book.BookPage
import co.voik.agesandtheart.book.LecternBookPlane
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.LecternOpening
import co.voik.agesandtheart.client.panel.BookMaps
import co.voik.agesandtheart.client.panel.OpenBookPanels
import net.minecraft.client.renderer.rendertype.RenderType
import co.voik.agesandtheart.client.panel.PanelComposite
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.LecternRenderer
import net.minecraft.client.renderer.blockentity.state.LecternRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/** A lectern's render state, carrying a book of ours when there is one on it. */
class LecternBookRenderState : LecternRenderState() {

    /** Which page carries the book's panel, or null where the book is not ours and vanilla draws it. */
    var panelPage: BookPage? = null

    var look: BookLook? = null

    /** How far open the book has swung, from shut (0) to flat (1). */
    var openness: Float = 0.0f

    /** Whether this is the lectern whose panel the client is showing — the one that may show its Age. */
    var isTheShownOne: Boolean = false

    /** Where panels are maps, this book's own map ([BookMaps]), or null while it has none to show. */
    var mapPicture: RenderType? = null
}

/**
 * Vanilla's lectern renderer, drawing a book of ours (design §7.8.2) in our own [AgeBookModel], swinging open and
 * shut on its back board, and every other book as it always has.
 *
 * Registered in vanilla's place (`ClientRegistrations`), and it extends rather than wraps so that vanilla's books go
 * through the class they always did — on NeoForge, patched render bounds included.
 */
class LecternBookRenderer(context: BlockEntityRendererProvider.Context) : LecternRenderer(context) {

    private val rendering = AgeBookRendering(context.sprites())

    /**
     * How far each lectern's book has swung, kept per lectern for as long as the client holds it. A lectern
     * says only whether its book is open, so the swing between is the client's to keep.
     */
    private val swings = WeakHashMap<LecternBlockEntity, Swing>()

    /** How far a book has swung, when it was last seen, and whether it was showing its Age when last open. */
    private class Swing(var progress: Float, var seenAt: Double, var wasShowingItsAge: Boolean = false)

    override fun createRenderState(): LecternRenderState = LecternBookRenderState()

    override fun extractRenderState(
        lectern: LecternBlockEntity,
        state: LecternRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(lectern, state, partialTicks, cameraPosition, breakProgress)
        if (state !is LecternBookRenderState) return
        state.panelPage = LecternBooks.panelPageOf(lectern.book)
        state.look = BookLook.of(lectern.book)
        state.isTheShownOne = false
        state.mapPicture = null
        if (state.panelPage == null) return
        val isOpen = LecternOpening.isOpen(lectern.blockState)
        val swing = swingOf(lectern, isOpen, partialTicks)
        state.openness = eased(swing.progress)
        val thisBook = BookBeingRead.OnALectern(lectern.blockPos)
        if (BookMaps.isOn) {
            // Every book its own map, kept through the swing shut; one inside an Age being shown wears the mist.
            val inThePlayersWorld = lectern.level === Minecraft.getInstance().level
            state.mapPicture = if (inThePlayersWorld) BookMaps.pictureOnAPage(thisBook) else null
            if (state.mapPicture == null && state.openness > 0.0f) OpenBookPanels.sawAMistedBook()
            return
        }
        if (!isOpen) {
            // Still swinging shut: a book that was showing its Age keeps the last of it, unless another book has
            // been shown since and the picture is that one's now; any other wears the mist.
            val isSwinging = state.openness > 0.0f
            val nothingElseIsShown = OpenBookPanels.shown == null || OpenBookPanels.shown == thisBook
            state.isTheShownOne = isSwinging && swing.wasShowingItsAge && nothingElseIsShown
            if (isSwinging && !state.isTheShownOne) OpenBookPanels.sawAMistedBook()
            return
        }

        // Only a lectern in the player's own world may show the Age. One inside an Age a panel is showing — even
        // this very lectern, where a book lies on the spot its own panel frames — wears the mist, or the picture
        // would be drawn into itself.
        val inThePlayersWorld = lectern.level === Minecraft.getInstance().level
        state.isTheShownOne = inThePlayersWorld && OpenBookPanels.shown == thisBook
        swing.wasShowingItsAge = state.isTheShownOne
        if (state.isTheShownOne) OpenBookPanels.sawTheShownBook() else OpenBookPanels.sawAMistedBook()
        // Until the Age has first been drawn, the shown panel wears the mist too.
        if (state.isTheShownOne && PanelComposite.liveView() == null) OpenBookPanels.sawAMistedBook()
    }

    /**
     * [lectern]'s book's swing, moved on to now: a full [SWING_TICKS] from shut to open. A book first seen is
     * already where its lectern says.
     */
    private fun swingOf(lectern: LecternBlockEntity, isOpen: Boolean, partialTicks: Float): Swing {
        val now = (lectern.level?.gameTime ?: 0L) + partialTicks.toDouble()
        val swing = swings.getOrPut(lectern) { Swing(if (isOpen) 1.0f else 0.0f, now) }
        val step = ((now - swing.seenAt) / SWING_TICKS).toFloat().coerceAtLeast(0.0f)
        swing.progress = (if (isOpen) swing.progress + step else swing.progress - step).coerceIn(0.0f, 1.0f)
        swing.seenAt = now
        return swing
    }

    /** Eased at both ends of the swing. */
    private fun eased(progress: Float): Float = progress * progress * (3 - 2 * progress)

    override fun submit(
        state: LecternRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        camera: CameraRenderState,
    ) {
        val ours = ourBookIn(state) ?: return super.submit(state, poseStack, collector, camera)
        val pose = BookOnTheSlope.bookAt(ours.openness)
        poseStack.pushPose()
        poseStack.translate(HALF_A_BLOCK, LecternBookPlane.CENTRE_HEIGHT, HALF_A_BLOCK)
        poseStack.rotate(Axis.YP.rotationDegrees(-ours.yRot))
        poseStack.rotate(Axis.ZP.rotationDegrees(LecternBookPlane.TILT_DEGREES.toFloat()))
        poseStack.translate(-LecternBookPlane.ONTO_THE_SLOPE, -LecternBookPlane.DOWN_THE_SLOPE, 0.0)
        val look = ours.look
        if (look != null) {
            BookOnTheSlope.layOnItsBack(ours.openness, look.binding, poseStack)
            rendering.submit(look, pose, poseStack, collector, ours.lightCoords)
            val model = rendering.modelFor(look.binding)
            val isOpenAtAll = ours.openness > 0.0f
            if (isOpenAtAll) ours.panelPage?.let { PanelOnThePage.submit(it, ours.isTheShownOne, ours.mapPicture, model, pose, poseStack, collector) }
        }
        poseStack.popPose()
    }

    /** [state] as ours where the lectern holds a book of ours, and null to leave it to vanilla. */
    private fun ourBookIn(state: LecternRenderState): LecternBookRenderState? {
        val ours = state as? LecternBookRenderState ?: return null
        return ours.takeIf { it.hasBook && it.panelPage != null }
    }

    companion object {
        private const val HALF_A_BLOCK = 0.5

        /** Half a second from shut to open, as the model's own animation takes. */
        const val SWING_TICKS = 10.0
    }
}

/** How a book of ours lies on a lectern's slope, in the frame [AgeBookModel] is drawn in. */
internal object BookOnTheSlope {

    /**
     * The book [swing] of the way from shut to open on the lectern: open, it stands a little short of flat, as
     * vanilla's lectern book does, so its boards rise off the slope rather than lie in it.
     */
    fun bookAt(swing: Float): AgeBookModel.State = AgeBookModel.State(swing * RESTING_OPENNESS)

    /**
     * The book [swing] of the way open ([bookAt]) turned about its back board's hinge, as far as it would take to
     * lay the back board flat had the book opened all the way: so shut, the back board lies on the slope, and open,
     * both boards rise from it alike. It slides toward the reader's left as it shuts, so that a shut book sits
     * centred rather than hanging off its spine; shut, it lies front cover up with its spine on the reader's left.
     */
    fun layOnItsBack(swing: Float, binding: BookBinding, poseStack: PoseStack) {
        val shutness = 1.0f - swing
        val hingeOffTheSlope = binding.boardThickness / MODEL_UNITS_PER_BLOCK
        poseStack.translate(hingeOffTheSlope, 0.0f, BACK_HINGE_ACROSS + shutness * SHUT_SHIFT_TOWARD_THE_LEFT)
        poseStack.rotate(Axis.YP.rotationDegrees(shutness * QUARTER_TURN))
        poseStack.translate(-hingeOffTheSlope, 0.0f, -BACK_HINGE_ACROSS)
    }

    private const val MODEL_UNITS_PER_BLOCK = 16.0f
    private const val QUARTER_TURN = 90.0f

    /** Where the back board hinges in the open book: on its inner face, a board's thickness off the slope, at the spine's right-hand edge. */
    private const val BACK_HINGE_ACROSS = -1.0f / MODEL_UNITS_PER_BLOCK

    /** Half a shut book's boards, which lie wholly right of the spine. */
    private const val SHUT_SHIFT_TOWARD_THE_LEFT = 4.0f / MODEL_UNITS_PER_BLOCK

    /** Each board 86° from shut, as vanilla's lectern book stands. */
    private const val RESTING_OPENNESS = 0.95f
}
