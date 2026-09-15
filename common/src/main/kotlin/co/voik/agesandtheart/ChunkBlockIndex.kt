package co.voik.agesandtheart

import com.google.common.collect.MapMaker
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Every block [valueOf] answers for, with its answer, by level and by chunk — so asking what is near a place
 * walks only the chunks within reach rather than every such block in the world.
 *
 * [stocked] reads a chunk as it loads and [emptied] clears it as one unloads; [arrived] and [gone] follow a
 * single block. `LevelChunkSection.maybeHas` answers from a section's palette, so [stocked] dismisses a
 * section that has never held a match without reading one block of it.
 *
 * Weakly keyed by level, so a server holding several keeps their positions apart and a level that goes
 * away takes its entries with it; a level's key is also dropped when its last block goes. Single-threaded
 * unless [readAcrossThreads], which makes every map in it concurrent, for an index written on one thread
 * and read on others with no lock. [stocked] builds a chunk's entry whole before publishing it either way,
 * so a reader never sees a chunk half-read.
 */
class ChunkBlockIndex<V : Any>(
    private val valueOf: (BlockState) -> V?,
    private val readAcrossThreads: Boolean = false,
) {

    private val holds: (BlockState) -> Boolean = { state -> valueOf(state) != null }

    @PublishedApi
    internal val byLevel: MutableMap<Level, MutableMap<Long, MutableMap<BlockPos, V>>> =
        if (readAcrossThreads) MapMaker().weakKeys().makeMap() else WeakHashMap()

    private fun <K : Any, T : Any> newMap(): MutableMap<K, T> =
        if (readAcrossThreads) ConcurrentHashMap() else HashMap()

    fun arrived(level: Level, at: BlockPos, value: V) {
        val chunks = byLevel.getOrPut(level) { newMap() }
        chunks.getOrPut(chunkHolding(at)) { newMap() }[at.immutable()] = value
    }

    fun gone(level: Level, at: BlockPos) {
        val chunks = byLevel[level] ?: return
        val chunk = chunkHolding(at)
        val here = chunks[chunk] ?: return
        here.remove(at)
        if (here.isEmpty()) chunks.remove(chunk)
        if (chunks.isEmpty()) byLevel.remove(level)
    }

    /** Replaces whatever the index held for [chunk] with what the chunk holds now. */
    fun stocked(level: Level, chunk: ChunkAccess) {
        val here = chunk.pos
        val found = newMap<BlockPos, V>()
        for ((index, section) in chunk.sections.withIndex()) {
            if (section.hasOnlyAir() || !section.maybeHas(holds)) continue
            val bottom = (chunk.minSectionY + index) shl CHUNK_BITS
            for (x in 0..<SECTION) for (y in 0..<SECTION) for (z in 0..<SECTION) {
                val value = valueOf(section.getBlockState(x, y, z)) ?: continue
                found[BlockPos(here.minBlockX + x, bottom + y, here.minBlockZ + z)] = value
            }
        }
        val key = ChunkPos.pack(here.x, here.z)
        if (found.isEmpty()) forgetChunk(level, key) else byLevel.getOrPut(level) { newMap() }[key] = found
    }

    fun emptied(level: Level, at: ChunkPos) = forgetChunk(level, ChunkPos.pack(at.x, at.z))

    private fun forgetChunk(level: Level, key: Long) {
        val chunks = byLevel[level] ?: return
        chunks.remove(key)
        if (chunks.isEmpty()) byLevel.remove(level)
    }

    fun forget() = byLevel.clear()

    /** Whether any level holds anything. */
    fun holdsAnything(): Boolean = byLevel.isNotEmpty()

    fun countIn(level: Level, at: ChunkPos): Int = byLevel[level]?.get(ChunkPos.pack(at.x, at.z))?.size ?: 0

    /**
     * Every indexed block whose middle is within [reach] of [at], with its squared distance from [at].
     *
     * Inline and allocating nothing, because callers ask it many times a frame.
     */
    inline fun eachWithin(level: Level, at: Vec3, reach: Double, visit: (BlockPos, Double) -> Unit) =
        eachValueWithin(level, at, reach) { block, _, away -> visit(block, away) }

    /** [eachWithin], with the value each block was indexed with. */
    inline fun eachValueWithin(level: Level, at: Vec3, reach: Double, visit: (BlockPos, V, Double) -> Unit) {
        val chunks = byLevel[level] ?: return
        val chunkX = at.x.toInt() shr CHUNK_BITS
        val chunkZ = at.z.toInt() shr CHUNK_BITS
        val about = (reach.toInt() shr CHUNK_BITS) + 1
        for (x in chunkX - about..chunkX + about) {
            for (z in chunkZ - about..chunkZ + about) {
                for ((block, value) in chunks[ChunkPos.pack(x, z)] ?: continue) {
                    val away = at.distanceToSqr(block.x + HALF, block.y + HALF, block.z + HALF)
                    if (away < reach * reach) visit(block, value, away)
                }
            }
        }
    }

    private fun chunkHolding(at: BlockPos) = ChunkPos.pack(at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)

    companion object {
        /** An index of positions alone, for blocks that either match or do not. */
        fun matching(holds: (BlockState) -> Boolean): ChunkBlockIndex<Unit> =
            ChunkBlockIndex({ state -> if (holds(state)) Unit else null })

        @PublishedApi
        internal const val CHUNK_BITS = 4

        /** From a block's corner to its middle. */
        @PublishedApi
        internal const val HALF = 0.5

        /** A chunk section's edge, and a chunk's width. */
        private const val SECTION = 16
    }
}
