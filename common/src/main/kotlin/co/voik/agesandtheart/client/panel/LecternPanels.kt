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
 * The lectern whose panel this client shows, and the rendering of it with no screen up (design §7.8.2).
 *
 * A client draws one panel ([LinkingPanel]), so of every open book of ours within reach the nearest
 * resolves and the rest wear the mist. A book screen with a panel of its own outranks them all: its `ask`
 * takes the panel over, and this stands aside until the screen gives it back.
 *
 * Two beats, because a lectern has no screen to lend it either. The client tick chooses the lectern and
 * ticks the panel, as `BookScreen.tick` would; the frame renders it — and only when the lectern was drawn
 * that frame, since a panel nobody can see is a whole level render for nothing.
 */
object LecternPanels {

    /** Whether the shown lectern was extracted for this frame. Set during extraction, spent by [drawFrame]. */
    private var seenThisFrame = false

    /** The preview whose picture [PanelTarget] holds now, so a new book never shows the last one's Age. */
    private var pictured: PreviewLevel? = null

    /** The lectern being shown, or null while the panel is a hand's or nobody's. */
    val shown: BlockPos? get() = (LinkingPanel.showingFor as? BookBeingRead.OnALectern)?.pos

    /** Whether [PanelTarget] holds the shown lectern's own Age, which is when its book may draw the picture. */
    val hasAPicture: Boolean get() = pictured != null && pictured === LinkingPanel.preview

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        // A hand's panel belongs to its screen, which ticks it and gives it back when it closes.
        if (LinkingPanel.showingFor is BookBeingRead.InHand) return

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
        seenThisFrame = true
    }

    /**
     * Renders the shown lectern's panel for this frame, before the GUI is extracted (`GameRendererMixin`).
     *
     * Skipped for the one viewer who cannot see it — whoever has that book's own screen up — whose lectern
     * goes on showing the last picture, under the screen.
     */
    @JvmStatic
    fun drawFrame(delta: DeltaTracker) {
        val seen = seenThisFrame
        seenThisFrame = false
        val lectern = shown ?: return
        if (!seen || isReadingItsScreen(lectern)) return
        val preview = LinkingPanel.preview ?: return
        if (PanelRenderer.draw(preview, delta)) pictured = preview
    }

    private fun isReadingItsScreen(lectern: BlockPos): Boolean {
        val screen = Minecraft.getInstance().screen as? BookScreen ?: return false
        return screen.held == BookBeingRead.OnALectern(lectern)
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
