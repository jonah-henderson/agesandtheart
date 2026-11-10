package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookEntity
import co.voik.agesandtheart.book.LecternBooks
import co.voik.agesandtheart.book.LecternOpening
import co.voik.agesandtheart.book.NearbyOpenBook
import co.voik.agesandtheart.book.OpenBookPanelChoice
import co.voik.agesandtheart.client.BookScreen
import co.voik.agesandtheart.client.CrystalViewerScreen
import co.voik.agesandtheart.client.LecternBookRenderer
import co.voik.agesandtheart.client.LinkTransit
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The book lying open — on a lectern, or fallen where someone linked from it — whose panel this client shows,
 * and the laying down of their pictures with no screen up (design §7.8.2).
 *
 * A client draws one panel ([LinkingPanel]), so of every open book of ours within reach the nearest resolves
 * and the rest wear the mist. **A book screen outranks them all**, a lectern's own book included: it takes the
 * panel up for the book it shows, and this stands aside for as long as the screen is open.
 *
 * Two beats, because a book lying open has no screen to lend it either. The client tick chooses the book and
 * ticks the panel, as `BookScreen.tick` would; the frame lays the pictures down ([PanelComposite]) — and only
 * those a book drawn that frame will show, since a panel nobody can see is a whole level render for nothing.
 */
object OpenBookPanels {

    /** Whether the shown book was extracted for this frame. Set during extraction, spent by [drawFrame]. */
    private var shownSeenThisFrame = false

    /** And whether any book wearing the mist was. */
    private var mistedSeenThisFrame = false

    /** The book a nearer one is taking the panel from the shown one for, and when the shown one began to fade. */
    private var handingOverTo: BookBeingRead? = null
    private var handOverStartedNanos = 0L

    /** The shown book's lectern once it is shut and swinging so, and the game time it was first seen shut. */
    private var swingingShut: BookBeingRead.OnALectern? = null
    private var shutAt = 0L

    /** The book lying open that is being shown, or null while the panel is a hand's or nobody's. */
    val shown: BookBeingRead?
        get() = when (val book = LinkingPanel.showingFor) {
            is BookBeingRead.OnALectern, is BookBeingRead.OnTheGround -> book
            is BookBeingRead.InHand, BookBeingRead.AtACrystalViewer, null -> null
        }

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        // An open book screen owns the panel and ticks it; the choice resumes from its lectern once it closes.
        // A crystal viewer's screen outranks them as a book's does, and so does a link in transit, which holds
        // the panel it was made from until it arrives.
        val screen = minecraft.gui.screen()
        if (screen is BookScreen || screen is CrystalViewerScreen || LinkTransit.isUnderway) return

        val showing = shown
        // A shown book being shut keeps the panel, and so its last picture, until it has swung shut; only then may
        // another open book near take it up, its Age fading in as the first did.
        if (isStillSwingingShut(level, showing)) return
        val nearby = openBooksNear(level, player.position())
        val wanted = OpenBookPanelChoice.choose(showing, nearby)
        // A shown book still lying open fades into the mist before a nearer one takes its panel.
        val showingIsStillOpen = showing != null && nearby.any { it.book == showing }
        when {
            wanted == showing -> handingOverTo = null
            wanted == null -> {
                handingOverTo = null
                LinkingPanel.release()
            }
            showingIsStillOpen && !hasFadedFor(wanted) -> Unit
            else -> {
                handingOverTo = null
                LinkingPanel.ask(wanted)
            }
        }
        if (shown != null) LinkingPanel.tick()
    }

    /** Whether the shown book has faded for long enough to give its panel to [wanted], starting the fade if not begun. */
    private fun hasFadedFor(wanted: BookBeingRead): Boolean {
        if (handingOverTo != wanted) {
            handingOverTo = wanted
            handOverStartedNanos = System.nanoTime()
            return false
        }
        return System.nanoTime() - handOverStartedNanos >= HAND_OVER_NANOS
    }

    /** How far into the mist the shown book has faded as it hands its panel over, `0..1`. */
    private fun handOverMist(): Float {
        handingOverTo ?: return 0.0f
        return ((System.nanoTime() - handOverStartedNanos).toFloat() / HAND_OVER_NANOS).coerceIn(0.0f, 1.0f)
    }

    private fun isStillSwingingShut(level: ClientLevel, showing: BookBeingRead?): Boolean {
        val onALectern = showing as? BookBeingRead.OnALectern
        val lectern = onALectern?.let { level.getBlockEntity(it.pos) } as? LecternBlockEntity
        val isShutWithOurBook = lectern != null &&
            LecternBooks.isOurs(lectern.book) && !LecternOpening.isOpen(lectern.blockState)
        if (onALectern == null || !isShutWithOurBook) {
            swingingShut = null
            return false
        }
        if (swingingShut != onALectern) {
            swingingShut = onALectern
            shutAt = level.gameTime
        }
        return level.gameTime - shutAt < LecternBookRenderer.SWING_TICKS
    }

    /** Called by a book's renderer as it extracts the book being shown. */
    fun sawTheShownBook() {
        shownSeenThisFrame = true
    }

    /** Called by a book's renderer as it extracts any other open book of ours. */
    fun sawAMistedBook() {
        mistedSeenThisFrame = true
    }

    /**
     * Lays down the pictures this frame's open books will show, before the GUI is extracted (`GameRendererMixin`).
     *
     * The live one is skipped where a lectern's own book screen is open at its panel, which composes the same
     * picture this frame and the lectern shows that.
     */
    @JvmStatic
    fun drawFrame(delta: DeltaTracker) {
        val book = shown
        val shownIsSeen = shownSeenThisFrame
        shownSeenThisFrame = false
        if (book != null && shownIsSeen && !isComposedByItsScreen(book)) {
            PanelComposite.composeLive(LinkingPanel.preview, delta, fadesIn = true, fadingOut = handOverMist())
        }
        // After the live picture, whose Age may itself have had a book of ours in view.
        if (mistedSeenThisFrame) PanelComposite.composeMisted()
        mistedSeenThisFrame = false
    }

    private fun isComposedByItsScreen(book: BookBeingRead): Boolean {
        if (LinkTransit.isUnderway) return true
        val screen = Minecraft.getInstance().gui.screen() as? BookScreen ?: return false
        return screen.held == book && screen.isShowingItsPanel
    }

    /** Three quarters of a second, for the shown book to fade into the mist before a nearer one takes its panel. */
    private const val HAND_OVER_NANOS = 750_000_000L

    /** Every open book of ours near enough to [viewer] to be chosen. */
    private fun openBooksNear(level: ClientLevel, viewer: Vec3): List<NearbyOpenBook> =
        openLecternsNear(level, viewer) + fallenBooksNear(level, viewer)

    private fun openLecternsNear(level: ClientLevel, viewer: Vec3): List<NearbyOpenBook> {
        val reach = OpenBookPanelChoice.LET_GO_BEYOND_BLOCKS
        val nearCorner = ChunkPos.containing(BlockPos.containing(viewer.x - reach, viewer.y, viewer.z - reach))
        val farCorner = ChunkPos.containing(BlockPos.containing(viewer.x + reach, viewer.y, viewer.z + reach))
        val found = mutableListOf<NearbyOpenBook>()
        for (chunkX in nearCorner.x..farCorner.x) {
            for (chunkZ in nearCorner.z..farCorner.z) {
                val chunk = level.chunkSource.getChunk(chunkX, chunkZ, false) ?: continue
                chunk.blockEntities.values.mapNotNullTo(found) { nearbyIfOpen(it, viewer, reach) }
            }
        }
        return found
    }

    private fun nearbyIfOpen(entity: BlockEntity, viewer: Vec3, reach: Double): NearbyOpenBook? {
        val lectern = entity as? LecternBlockEntity ?: return null
        val holdsAnOpenBookOfOurs = LecternBooks.isOurs(lectern.book) && LecternOpening.isOpen(lectern.blockState)
        if (!holdsAnOpenBookOfOurs) return null
        val distance = viewer.distanceTo(Vec3.atCenterOf(lectern.blockPos))
        return if (distance <= reach) NearbyOpenBook(BookBeingRead.OnALectern(lectern.blockPos), distance) else null
    }

    private fun fallenBooksNear(level: ClientLevel, viewer: Vec3): List<NearbyOpenBook> {
        val reach = OpenBookPanelChoice.LET_GO_BEYOND_BLOCKS
        val fallen = level.getEntitiesOfClass(BookEntity::class.java, AABB.ofSize(viewer, reach * 2, reach * 2, reach * 2)) {
            LecternBooks.isOurs(it.book)
        }
        return fallen
            .map { NearbyOpenBook(BookBeingRead.OnTheGround(it.id), viewer.distanceTo(it.position())) }
            .filter { it.distance <= reach }
    }
}
