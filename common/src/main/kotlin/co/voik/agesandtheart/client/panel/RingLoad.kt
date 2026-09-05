package co.voik.agesandtheart.client.panel

import co.voik.agesandtheart.book.panel.PanelRing
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos

/**
 * How much of one panel's ring has arrived, and when to ask about the rest.
 *
 * A transfer rather than a world, which is why it is not part of [PreviewLevel]: what is owed, what has
 * come, and whether enough time has passed to say so.
 */
class RingLoad(private val arrival: BlockPos, private val expected: Int) {

    /** Positions rather than a count, so a hole in the picture can be named and re-requested. */
    private val arrived = mutableSetOf<Long>()

    private var chases = 0

    /** Started now rather than at zero, or the first frame is already older than the interval. */
    private var lastChasedAt = System.nanoTime()

    /** When the stream last made progress, which is what decides whether it has stopped. */
    private var lastArrivalAt = System.nanoTime()

    /** How far along the load is, `0..1` — what the fade reads. */
    val wholeness: Float
        get() = if (expected <= 0) 1.0f else (arrived.size.toFloat() / expected).coerceIn(0.0f, 1.0f)

    /** Whether anything has come, which is when there is first something to draw. */
    val hasAnything: Boolean get() = arrived.isNotEmpty()

    /** How many times this load has asked again. */
    val asksSoFar: Int get() = chases

    fun took(x: Int, z: Int) {
        arrived.add(ChunkPos.pack(x, z))
        lastArrivalAt = System.nanoTime()
    }

    /**
     * What to ask the server for again, or null while there is nothing to ask about.
     *
     * Asked only when the stream has gone *quiet*, not merely when it is unfinished. A whole ring takes
     * seconds to generate and arrives throughout, so chasing on elapsed time alone re-requested every
     * chunk mid-stream and had the server generate the lot a second time.
     */
    fun toChase(): List<ChunkPos>? {
        if (wholeness >= 1.0f || chases >= MOST_CHASES) return null
        val now = System.nanoTime()
        if (now - lastArrivalAt < GONE_QUIET_NANOS) return null
        if (now - lastChasedAt < GONE_QUIET_NANOS) return null
        val missing = missing()
        if (missing.isEmpty()) return null
        lastChasedAt = now
        chases++
        return missing
    }

    /** Derived rather than remembered: the ring is a pure function of the arrival, which both sides know. */
    private fun missing(): List<ChunkPos> =
        PanelRing.around(PanelRing.centreOf(arrival))
            .filterNot { ChunkPos.pack(it.x, it.z) in arrived }

    companion object {
        /**
         * How long the stream may say nothing before it is taken to have stopped.
         *
         * Longer than a whole wave of the ring takes to generate on a cold Age, or a slow one is mistaken
         * for a broken one and the server is asked to make the lot a second time — which is what happened
         * at six seconds.
         */
        private const val GONE_QUIET_NANOS = 12_000_000_000L

        const val MOST_CHASES = 4
    }
}
