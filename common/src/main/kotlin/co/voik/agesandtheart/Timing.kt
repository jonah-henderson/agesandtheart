package co.voik.agesandtheart

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How long each named step took, gathered until something asks for the table.
 *
 * **Aggregated rather than logged as it happens**, because most of what is worth timing runs every frame:
 * a line per call is nine thousand lines a minute and tells you less than one line of totals. A step that
 * runs once still reads correctly — one call, and its own time.
 *
 * **Nothing here is on by default.** [watching] is set while something wants the numbers, so the ordinary
 * game pays two field reads and no allocation.
 *
 * Written to from the server thread, the render thread and the chunk workers at once, so the counters are
 * atomic and the map is concurrent. It is a diagnostic, not a profiler: it measures wall time around a
 * block and makes no attempt to separate waiting from working, which for the things being asked about here
 * — chunk generation, meshing, a render submission — is the number that matters anyway.
 */
object Timing {

    /**
     * Whether anything is gathering.
     *
     * **On, because this is a diagnostic in the middle of a diagnosis.** It costs a field read and an
     * atomic add per measured step, all of which are around things that take milliseconds. It should be
     * off — or gone — once the panel's costs are understood.
     */
    @Volatile
    var watching: Boolean = true

    private class Tally {
        val calls = AtomicLong()
        val nanos = AtomicLong()
        val worst = AtomicLong()
    }

    private val tallies = ConcurrentHashMap<String, Tally>()

    /** Runs [block], counting how long it took against [name]. */
    inline fun <T> of(name: String, block: () -> T): T {
        if (!watching) return block()
        val began = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - began)
        }
    }

    /** For a span that cannot be wrapped — one that begins in one callback and ends in another. */
    fun record(name: String, nanos: Long) {
        if (!watching) return
        val tally = tallies.computeIfAbsent(name) { Tally() }
        tally.calls.incrementAndGet()
        tally.nanos.addAndGet(nanos)
        tally.worst.accumulateAndGet(nanos, ::maxOf)
    }

    /**
     * Discards whatever was gathered, for a caller that wants to time one thing cleanly.
     *
     * **Not needed to begin**, since gathering is always on: the interesting span usually starts before
     * anything realises it has — a book's words are learned before the screen that will time the panel
     * exists — and a `start` at the panel's own beginning would throw that away.
     */
    fun forget() {
        tallies.clear()
    }

    /**
     * Says what was measured, worst total first, and forgets it.
     *
     * Sorted by total rather than by mean, because the question being asked is where the seconds went and
     * a slow thing done once outranks a fast thing done three hundred times only if it actually cost more.
     */
    fun report(what: String) {
        if (!watching) return
        val rows = tallies.entries
            .map { (name, tally) -> Row(name, tally.calls.get(), tally.nanos.get(), tally.worst.get()) }
            .sortedByDescending { it.nanos }
        if (rows.isEmpty()) {
            Constants.LOG.info("Timing [{}]: nothing was measured", what)
            return
        }
        val widest = rows.maxOf { it.name.length }
        Constants.LOG.info("Timing [{}] — worst total first:", what)
        for (row in rows) {
            Constants.LOG.info(
                "  {}  {} calls, {} total, {} mean, {} worst",
                row.name.padEnd(widest),
                row.calls.toString().padStart(5),
                millis(row.nanos).padStart(9),
                millis(row.nanos / row.calls.coerceAtLeast(1)).padStart(8),
                millis(row.worst).padStart(9),
            )
        }
        tallies.clear()
    }

    private data class Row(val name: String, val calls: Long, val nanos: Long, val worst: Long)

    private fun millis(nanos: Long): String = "%.2fms".format(nanos / NANOS_PER_MILLI)

    private const val NANOS_PER_MILLI = 1_000_000.0
}
