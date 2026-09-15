package co.voik.agesandtheart

import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * Every block matching [holds], by level and by chunk — so asking what is near a place walks only the
 * chunks within reach rather than every such block in the world.
 *
 * [stocked] fills it as a chunk loads and [emptied] clears it as one unloads; [arrived] and [gone] follow a
 * single block. `LevelChunkSection.maybeHas` answers from a section's palette, so [stocked] dismisses a
 * section that has never held a match without reading one block of it.
 *
 * Weakly keyed by level, so a server holding several keeps their positions apart and a level that goes
 * away takes its entries with it. Not safe across threads: each owner uses it from one thread.
 */
class ChunkBlockIndex(private val holds: (BlockState) -> Boolean) {

    @PublishedApi
    internal val byLevel = WeakHashMap<Level, MutableMap<Long, MutableSet<BlockPos>>>()

    fun arrived(level: Level, at: BlockPos) {
        val chunks = byLevel.getOrPut(level) { HashMap() }
        chunks.getOrPut(chunkHolding(at)) { HashSet() }.add(at.immutable())
    }

    fun gone(level: Level, at: BlockPos) {
        val chunks = byLevel[level] ?: return
        val chunk = chunkHolding(at)
        val here = chunks[chunk] ?: return
        here.remove(at)
        if (here.isEmpty()) chunks.remove(chunk)
        if (chunks.isEmpty()) byLevel.remove(level)
    }

    fun stocked(level: Level, chunk: ChunkAccess) {
        val here = chunk.pos
        val cursor = BlockPos.MutableBlockPos()
        for ((index, section) in chunk.sections.withIndex()) {
            if (section.hasOnlyAir() || !section.maybeHas(holds)) continue
            val bottom = (chunk.minSectionY + index) shl CHUNK_BITS
            for (x in 0..<SECTION) for (y in 0..<SECTION) for (z in 0..<SECTION) {
                if (!holds(section.getBlockState(x, y, z))) continue
                cursor.set(here.minBlockX + x, bottom + y, here.minBlockZ + z)
                arrived(level, cursor)
            }
        }
    }

    fun emptied(level: Level, at: ChunkPos) {
        val chunks = byLevel[level] ?: return
        chunks.remove(ChunkPos.pack(at.x, at.z))
        if (chunks.isEmpty()) byLevel.remove(level)
    }

    fun forget() = byLevel.clear()

    fun countIn(level: Level, at: ChunkPos): Int = byLevel[level]?.get(ChunkPos.pack(at.x, at.z))?.size ?: 0

    /**
     * Every indexed block whose middle is within [reach] of [at], with its squared distance from [at].
     *
     * Inline and allocating nothing, because callers ask it many times a frame.
     */
    inline fun eachWithin(level: Level, at: Vec3, reach: Double, visit: (BlockPos, Double) -> Unit) {
        val chunks = byLevel[level] ?: return
        val chunkX = at.x.toInt() shr CHUNK_BITS
        val chunkZ = at.z.toInt() shr CHUNK_BITS
        val about = (reach.toInt() shr CHUNK_BITS) + 1
        for (x in chunkX - about..chunkX + about) {
            for (z in chunkZ - about..chunkZ + about) {
                for (block in chunks[ChunkPos.pack(x, z)] ?: continue) {
                    val away = at.distanceToSqr(block.x + HALF, block.y + HALF, block.z + HALF)
                    if (away < reach * reach) visit(block, away)
                }
            }
        }
    }

    private fun chunkHolding(at: BlockPos) = ChunkPos.pack(at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)

    companion object {
        @PublishedApi
        internal const val CHUNK_BITS = 4

        /** From a block's corner to its middle. */
        @PublishedApi
        internal const val HALF = 0.5

        /** A chunk section's edge, and a chunk's width. */
        private const val SECTION = 16
    }
}
