package co.voik.agesandtheart.worldgen.field

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * The columns a costly field has worked out, shared by every worker thread — behind each thread's own [ColumnMemo].
 *
 * A chunk's generation asks for its columns from several stages on several threads, and its neighbours ask for the
 * same columns again: the aquifer looks eight blocks out to judge how open a cave is, and features, structure starts
 * and biome lookups each read columns of their own. A thread's memo cannot see another's, so every one of those
 * worked the column out afresh. [derive] must answer a column the same way every time, which is what makes sharing
 * its answers exact.
 *
 * One store for every field that uses it, bounded by [MOST_CHUNKS] chunks of columns across all of them, so however
 * many Ages are open the memory stays within it; the chunks used longest ago go first. Each field is told apart by
 * an identity of its own, never by equality, so two Ages can never read each other's columns. Columns are kept
 * packed, a few dozen bytes each.
 */
internal class SharedColumns(private val derive: (Int, Int) -> Spans) {

    /** This field's own, for as long as it lives. */
    private val owner = NEXT_OWNER.incrementAndGet()

    fun at(worldX: Int, worldZ: Int): Spans {
        val key = ChunkKey(owner, worldX shr CHUNK_SHIFT, worldZ shr CHUNK_SHIFT)
        val chunk = CHUNKS.computeIfAbsent(key) { ChunkOfColumns() }
        chunk.lastUsed = CLOCK.incrementAndGet()
        val slot = ((worldX and CHUNK_MASK) shl CHUNK_SHIFT) or (worldZ and CHUNK_MASK)
        chunk.columns.get(slot)?.let { return Spans.unpacked(it) }
        val derived = derive(worldX, worldZ)
        chunk.columns.set(slot, derived.packed())
        if (CHUNKS.size > MOST_CHUNKS) forgetTheOldest()
        return derived
    }

    private data class ChunkKey(val owner: Long, val chunkX: Int, val chunkZ: Int)

    private class ChunkOfColumns {
        val columns = AtomicReferenceArray<IntArray?>(COLUMNS_IN_A_CHUNK)

        @Volatile
        var lastUsed = 0L
    }

    private companion object {
        val CHUNKS = ConcurrentHashMap<ChunkKey, ChunkOfColumns>()
        val CLOCK = AtomicLong()
        val NEXT_OWNER = AtomicLong()

        /**
         * Under ten megabytes. Generation works close to where it last worked, so a store this size keeps what is
         * asked again: on a fresh `gentle` Age at radius 10, 256 chunks already gave the whole gain (72 ms a chunk to
         * 49, against 48 at 4096), and this leaves room for several players' fronts at once.
         */
        const val MOST_CHUNKS = 512

        /** How much of the store goes when it is full, so a full store is not trimmed on every insertion. */
        const val FORGET_SHARE = 4

        const val CHUNK_SHIFT = 4
        const val CHUNK_MASK = (1 shl CHUNK_SHIFT) - 1
        const val COLUMNS_IN_A_CHUNK = 1 shl (CHUNK_SHIFT * 2)

        /** One thread trims at a time; the others carry on, a little over the bound until it is done. */
        private val trimming = AtomicBoolean(false)

        fun forgetTheOldest() {
            if (!trimming.compareAndSet(false, true)) return
            try {
                if (CHUNKS.size <= MOST_CHUNKS) return
                val byAge = CHUNKS.entries.map { it.key to it.value.lastUsed }.sortedBy { it.second }
                for ((key, _) in byAge.take(byAge.size / FORGET_SHARE)) CHUNKS.remove(key)
            } finally {
                trimming.set(false)
            }
        }
    }
}
