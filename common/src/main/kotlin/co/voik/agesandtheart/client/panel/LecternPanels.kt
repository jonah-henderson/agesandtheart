package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.LecternOpening
import co.voik.agesandtheart.book.LecternPanelChoice
import co.voik.agesandtheart.book.NearbyLectern
import co.voik.agesandtheart.client.BookScreen
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.phys.Vec3

/**
 * The lectern whose panel this client shows, and the laying down of lectern pictures with no screen up (design
 * §7.8.2).
 *
 * A client draws one panel ([LinkingPanel]), so of every open book of ours within reach the nearest resolves
 * and the rest wear the mist. **A book screen outranks them all**, a lectern's own book included: it takes the
 * panel up for the book it shows, and this stands aside for as long as the screen is open.
 *
 * Two beats, because a lectern has no screen to lend it either. The client tick chooses the lectern and ticks
 * the panel, as `BookScreen.tick` would; the frame lays the pictures down ([PanelComposite]) — and only those a
 * lectern drawn that frame will show, since a panel nobody can see is a whole level render for nothing.
 */
object LecternPanels {

    /** Whether the shown lectern was extracted for this frame. Set during extraction, spent by [drawFrame]. */
    private var shownSeenThisFrame = false

    /** And whether any lectern wearing the mist was. */
    private var mistedSeenThisFrame = false

    /** The lectern being shown, or null while the panel is a hand's or nobody's. */
    val shown: BlockPos? get() = (LinkingPanel.showingFor as? BookBeingRead.OnALectern)?.pos

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        // An open book screen owns the panel and ticks it; the choice resumes from its lectern once it closes.
        if (minecraft.gui.screen() is BookScreen) return

        val showing = shown
        val wanted = LecternPanelChoice.choose(showing, openBooksNear(level, player.position()))
        when {
            wanted == showing -> Unit
            wanted == null -> LinkingPanel.release()
            else -> LinkingPanel.ask(BookBeingRead.OnALectern(wanted))
        }
        if (shown != null) LinkingPanel.tick()
    }

    /** Called by the lectern's renderer as it extracts the lectern being shown. */
    fun sawTheShownLectern() {
        shownSeenThisFrame = true
    }

    /** Called by the lectern's renderer as it extracts any other open book of ours. */
    fun sawAMistedLectern() {
        mistedSeenThisFrame = true
    }

    /**
     * Lays down the pictures this frame's lecterns will show, before the GUI is extracted (`GameRendererMixin`).
     *
     * The live one is skipped where the lectern's own book screen is open at its panel, which composes the same
     * picture this frame and the lectern shows that.
     */
    @JvmStatic
    fun drawFrame(delta: DeltaTracker) {
        val lectern = shown
        val shownIsSeen = shownSeenThisFrame
        shownSeenThisFrame = false
        if (lectern != null && shownIsSeen && !isComposedByItsScreen(lectern)) {
            PanelComposite.composeLive(LinkingPanel.preview, delta)
        }
        // After the live picture, whose Age may itself have had a lectern of ours in view.
        if (mistedSeenThisFrame) PanelComposite.composeMisted()
        mistedSeenThisFrame = false
    }

    private fun isComposedByItsScreen(lectern: BlockPos): Boolean {
        val screen = Minecraft.getInstance().gui.screen() as? BookScreen ?: return false
        return screen.held == BookBeingRead.OnALectern(lectern) && screen.isShowingItsPanel
    }

    /** Every open book of ours near enough to [viewer] to be chosen. */
    private fun openBooksNear(level: ClientLevel, viewer: Vec3): List<NearbyLectern> {
        val reach = LecternPanelChoice.LET_GO_BEYOND_BLOCKS
        val nearCorner = ChunkPos.containing(BlockPos.containing(viewer.x - reach, viewer.y, viewer.z - reach))
        val farCorner = ChunkPos.containing(BlockPos.containing(viewer.x + reach, viewer.y, viewer.z + reach))
        val found = mutableListOf<NearbyLectern>()
        for (chunkX in nearCorner.x..farCorner.x) {
            for (chunkZ in nearCorner.z..farCorner.z) {
                val chunk = level.chunkSource.getChunk(chunkX, chunkZ, false) ?: continue
                chunk.blockEntities.values.mapNotNullTo(found) { nearbyIfOpen(it, viewer, reach) }
            }
        }
        return found
    }

    private fun nearbyIfOpen(entity: BlockEntity, viewer: Vec3, reach: Double): NearbyLectern? {
        val lectern = entity as? LecternBlockEntity ?: return null
        val holdsAnOpenBookOfOurs = LecternBooks.isOurs(lectern.book) && LecternOpening.isOpen(lectern.blockState)
        if (!holdsAnOpenBookOfOurs) return null
        val distance = viewer.distanceTo(Vec3.atCenterOf(lectern.blockPos))
        return if (distance <= reach) NearbyLectern(lectern.blockPos, distance) else null
    }
}
