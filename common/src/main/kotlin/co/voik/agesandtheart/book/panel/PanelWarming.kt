package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.WarmAgesWhen
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.book.BookAge
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

/**
 * Generates the ring a panel will want, before anybody is waiting on it.
 *
 * **An Age costs tens of seconds the first time it is looked at and a tenth of a second every time after**,
 * because chunks are generated once and then read from disk. Nothing here makes that first generation
 * quicker; it moves it to a moment when nobody is watching a black rectangle.
 *
 * The same ring the panel streams, because the point is that the panel finds it already made.
 */
object PanelWarming {

    /** Ages whose ring has been made ready this run. */
    private val warmed = mutableSetOf<Identifier>()

    /** And those on the way to it, so a book ticking in an inventory does not queue itself every tick. */
    private val underway = mutableSetOf<Identifier>()

    /**
     * Waiting their turn.
     *
     * One at a time: warming is a whole Age's worth of terrain, and several at once would compete for the
     * same chunk workers a player standing in a world is using.
     */
    private val waiting = ArrayDeque<Identifier>()

    private var busy = false

    /**
     * Warms the Age [stack] has just been bound to, which is the earliest moment there is one.
     *
     * Called where a book's Age becomes decided: the desk that binds it, and the first tick a found book
     * writes itself. Until then a book describes no world and there is nothing to make ready.
     */
    fun whenBound(server: MinecraftServer, stack: ItemStack) {
        if (AgeConfig.warmAgesWhen.get() != WarmAgesWhen.BOUND) return
        warm(server, stack)
    }

    /**
     * Considers warming a book already in somebody's keeping.
     *
     * Runs on the book's inventory tick, so the ordinary answer is the first `return` here. It serves two
     * purposes and neither is the common path: it is how `HELD` notices a book reaching a hand, and it is
     * the catch-up for a bound book whose binding nobody was watching — traded, looted before the setting
     * was on, carried in from another world.
     */
    fun consider(server: MinecraftServer, stack: ItemStack, inHand: Boolean) {
        when (AgeConfig.warmAgesWhen.get()) {
            WarmAgesWhen.OPENED -> return
            WarmAgesWhen.HELD -> if (!inHand) return
            WarmAgesWhen.BOUND -> Unit
        }
        warm(server, stack)
    }

    private fun warm(server: MinecraftServer, stack: ItemStack) {
        // A book with no words is not bound and describes nothing yet.
        if (!stack.has(AgeContent.BOOK_WORDS)) return

        // Cheap on every tick after the first: an Age already seen to is a set lookup and nothing else.
        val known = stack.get(AgeContent.AGE_ID)
        if (known != null && (known in warmed || known in underway)) return

        // Minting stamps the id onto the book, so the check above answers on every later tick.
        val level = BookAge.of(server, stack) ?: return
        val id = level.dimension().identifier()
        if (id in warmed || id in underway) return

        underway.add(id)
        waiting.add(id)
        beginTheNext(server)
    }

    /** Forgotten with the Age, so a name written again is warmed again rather than assumed ready. */
    fun forget(id: Identifier) {
        warmed.remove(id)
        underway.remove(id)
        waiting.remove(id)
    }

    private fun beginTheNext(server: MinecraftServer) {
        if (busy) return
        val id = waiting.poll() ?: return
        val level = Ages.open(server, id)
        busy = true
        generateTheRing(server, level, id)
    }

    /**
     * Asks for every chunk of [level]'s ring, and says so when the last one lands.
     *
     * Dispatched off the server thread because `getChunkFuture` only *returns* a future when it is called
     * from somewhere else; on the server thread it blocks until the chunk is made. Nothing here waits on
     * the result either, so no pooled thread is held for the minute this can take.
     */
    private fun generateTheRing(server: MinecraftServer, level: ServerLevel, id: Identifier) {
        val began = System.nanoTime()
        val centre = PanelRing.centreOf(Ages.arrivalIn(level))
        Constants.LOG.info("Warming {} — {} chunks around {}", id, PanelRing.COUNT, centre)
        Util.backgroundExecutor().execute {
            val ring = PanelRing.around(centre).map {
                level.chunkSource.getChunkFuture(it.x, it.z, ChunkStatus.FULL, true)
            }
            CompletableFuture.allOf(*ring.toTypedArray()).whenCompleteAsync({ _, _ ->
                underway.remove(id)
                warmed.add(id)
                busy = false
                Constants.LOG.info("Warmed {} in {}ms", id, (System.nanoTime() - began) / 1_000_000)
                beginTheNext(server)
            }, server)
        }
    }
}
