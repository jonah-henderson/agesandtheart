package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.PanelView
import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.panel.PanelMapChunkPayload
import co.voik.agesandtheart.book.panel.PanelMapPayload
import co.voik.agesandtheart.book.panel.PanelMapRequest
import co.voik.agesandtheart.client.sendToServer
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderType

/**
 * Map panels (`AgeConfig.panelMode` MAP), one for every open book in reach and every book a screen is showing, all
 * at once. A map costs the server one read and the client one small texture, so where [LinkingPanel] shows one
 * live Age, every book here shows its own map, misted only while its Age's ground is still being made.
 *
 * Each map takes one of [SLOTS] slots, which name its texture and its finished picture to the render types that
 * sample them ([PanelTexture.MAPS], [PanelTexture.MAP_PICTURES]); a book in reach with no slot free wears the mist.
 */
object BookMaps {

    const val SLOTS = 16

    /** One book's map: what it was asked as, and what has come back. */
    private class Shown(val book: BookBeingRead, val slot: Int, val ask: Int) {
        var map: PanelMap? = null
        var askedAtNanos = System.nanoTime()
        var asks = 1
        var lastWantedNanos = System.nanoTime()
        var isSeenThisFrame = false
        /** Whether the picture shows the map as it now stands, so a whole map is laid down once and left. */
        var isPictureCurrent = false
        /** Whether this book's picture has been laid down at all, before which its slot holds another's or nothing. */
        var hasPicture = false
    }

    private val shown = mutableMapOf<BookBeingRead, Shown>()

    /** The books a screen is showing, which want a map wherever they are. */
    private val pinned = mutableSetOf<BookBeingRead>()

    /** Each slot's finished picture, made on first use and kept for the life of the client. */
    private val pictures = arrayOfNulls<RenderTarget>(SLOTS)

    private var asksMade = 0

    /** Whether panels are maps on this server — the server's setting, which reaches the client with the rest of it. */
    val isOn: Boolean
        get() = runCatching { AgeConfig.panelMode.get() == PanelView.MAP }.getOrDefault(false)

    /** Wants [book]'s map for as long as a screen shows it. */
    fun pin(book: BookBeingRead) {
        pinned += book
    }

    fun unpin(book: BookBeingRead) {
        pinned -= book
    }

    /** [book]'s map, or null while it has none yet. */
    fun mapOf(book: BookBeingRead?): PanelMap? = book?.let { shown[it]?.map }

    /** Whether [book]'s map has not begun to arrive, though it may yet. */
    fun isWaiting(book: BookBeingRead?): Boolean {
        val entry = book?.let { shown[it] } ?: return book != null
        val map = entry.map ?: return entry.asks <= MOST_ASKS
        return !map.load.hasAnything
    }

    /**
     * The client tick: asks after the map of every book that has come to want one, lets go of those that have not
     * been wanted for [GRACE_NANOS], and asks again where an answer has not come.
     */
    fun tick(minecraft: Minecraft) {
        if (!isOn) return forget()
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val now = System.nanoTime()
        val wanted = pinned + OpenBookPanels.openBooksNear(level, player.position()).map { it.book }
        for (book in wanted) shown[book]?.let { it.lastWantedNanos = now } ?: startAsking(book)
        val unwanted = shown.values.filter { now - it.lastWantedNanos > GRACE_NANOS }
        unwanted.forEach(::drop)
        shown.values.forEach { askAgainIfOwed(it, now) }
    }

    private fun startAsking(book: BookBeingRead) {
        val taken = shown.values.map { it.slot }.toSet()
        val slot = (0..<SLOTS).firstOrNull { it !in taken } ?: return
        val entry = Shown(book, slot, ++asksMade)
        shown[book] = entry
        sendToServer(PanelMapRequest(book, entry.ask, emptyList()))
    }

    /** Asks the whole map again where nothing came back, or the chunks still missing where the stream went quiet. */
    private fun askAgainIfOwed(entry: Shown, now: Long) {
        val map = entry.map
        if (map == null) {
            val isOwed = now - entry.askedAtNanos > BEFORE_ASKING_AGAIN_NANOS && entry.asks <= MOST_ASKS
            if (!isOwed) return
            entry.askedAtNanos = now
            entry.asks++
            sendToServer(PanelMapRequest(entry.book, entry.ask, emptyList()))
            return
        }
        map.load.toChase()?.let { missing -> sendToServer(PanelMapRequest(entry.book, entry.ask, missing)) }
    }

    fun accept(payload: PanelMapPayload) {
        val entry = shown[payload.book]?.takeIf { it.ask == payload.ask } ?: return
        entry.map?.close()
        entry.map = PanelMap(payload.around, payload.chunksComing, entry.slot)
        entry.isPictureCurrent = false
    }

    fun accept(payload: PanelMapChunkPayload) {
        val entry = shown[payload.book]?.takeIf { it.ask == payload.ask } ?: return
        entry.map?.accept(payload)
        entry.isPictureCurrent = false
    }

    /**
     * The picture [book] wears on its page, or null where it has no slot — noted as seen, so its picture is laid
     * down this frame ([drawFrame]).
     */
    fun pictureOnAPage(book: BookBeingRead): RenderType? {
        val entry = shown[book] ?: return null
        entry.isSeenThisFrame = true
        return if (entry.hasPicture) PanelRenderTypes.mapPictures[entry.slot] else null
    }

    /**
     * Lays down the picture of every map a book drew this frame, where it has changed — or every frame while it is
     * still misted, since the mist moves.
     */
    fun drawFrame() {
        for (entry in shown.values) {
            if (!entry.isSeenThisFrame) continue
            entry.isSeenThisFrame = false
            val isWhole = (entry.map?.load?.wholeness ?: 0.0f) >= 1.0f
            if (entry.isPictureCurrent && isWhole) continue
            val target = pictures[entry.slot] ?: PanelComposite.newMapTarget(entry.slot).also { pictures[entry.slot] = it }
            PanelComposite.composeMap(entry.map, target)
            entry.isPictureCurrent = true
            entry.hasPicture = true
        }
    }

    fun mapView(slot: Int): GpuTextureView? = shown.values.firstOrNull { it.slot == slot }?.map?.view

    fun pictureView(slot: Int): GpuTextureView? = pictures[slot]?.colorTextureView

    private fun drop(entry: Shown) {
        entry.map?.close()
        shown.remove(entry.book)
    }

    /** Lets every map go, for a disconnect or a server whose panels are not maps. */
    fun forget() {
        shown.values.toList().forEach(::drop)
        pinned.clear()
    }

    /** Two seconds, so a lectern's book swinging shut keeps its map until it is shut. */
    private const val GRACE_NANOS = 2_000_000_000L

    /** Five seconds: a map asks for one read of chunks already made, unless its Age is still being made. */
    private const val BEFORE_ASKING_AGAIN_NANOS = 5_000_000_000L

    private const val MOST_ASKS = 3
}
