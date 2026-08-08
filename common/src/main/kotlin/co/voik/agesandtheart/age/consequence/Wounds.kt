package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.sqrt

/**
 * Where the wounds are — so anything can ask **how near the nearest one is** without searching the world
 * for black blocks (design §5.1).
 *
 * **This is the one place the block-entity cost pays for itself.** Every wound already carries one for the
 * renderer, so it can announce itself on load and drop out on removal, and the index is maintained by two
 * events rather than by scanning. A corruption gradient asks this many times a frame, and a search would be
 * the whole reason not to have one.
 *
 * **Chunk-keyed**, so a query walks only the chunks within reach rather than every wound in the world — an
 * Age at the top of the register holds thousands and a linear scan would be hopeless.
 *
 * **Both sides keep one**, and they want it for different things: the client draws a gradient out of it and
 * the server decides where the Age is dangerous ([Hostility]). The block entity exists on both, so both are
 * maintained by the same two events.
 *
 * **Keyed by level, which is what makes it safe on a server.** A client stands in one world at a time and a
 * server holds every loaded Age at once, so a single index shared between them would have an Age's
 * coordinates answering questions asked about somewhere else. Weakly keyed, so a world that goes away takes
 * its wounds with it and no cleanup has to be remembered — which is precisely the cleanup that was not.
 *
 * **Sealing is read from the world, never cached here.** Boxing a wound in rewrites its block state through
 * `neighborChanged` and never touches its entity, so a flag kept in this index would go stale the moment
 * somebody finished the box — which is exactly when it has to be right.
 */
object Wounds {

    private val byLevel = WeakHashMap<Level, MutableMap<Long, MutableSet<BlockPos>>>()

    /** Called as a wound's entity loads. */
    fun arrived(level: Level, at: BlockPos) {
        val chunks = byLevel.getOrPut(level) { HashMap() }
        chunks.getOrPut(chunkHolding(at)) { HashSet() }.add(at.immutable())
    }

    /** And as it goes — a chunk unloading, or the block being replaced. */
    fun gone(level: Level, at: BlockPos) {
        val chunks = byLevel[level] ?: return
        val chunk = chunkHolding(at)
        val here = chunks[chunk] ?: return
        here.remove(at)
        if (here.isEmpty()) chunks.remove(chunk)
        if (chunks.isEmpty()) byLevel.remove(level)
    }

    /** Everything, for a client leaving a server outright. */
    fun forget() = byLevel.clear()

    /**
     * How corrupted [at] is, from nothing at all to fully — the number every gradient reads.
     *
     * One over the distance rather than a linear falloff, so the corruption is concentrated hard around
     * the wound and fades quickly: a wound should make *its own place* dreadful (§5.1) and not tint half
     * an Age. A **sealed** wound contributes nothing, which is what boxing one in buys.
     */
    fun corruptionAt(level: Level, at: Vec3): Double {
        val nearest = nearestSquared(level, at)
        if (nearest >= REACH * REACH) return NONE
        return corruptionAtRange(sqrt(nearest))
    }

    /** The falloff itself, so it can be reasoned about without a world to put a wound in. */
    fun corruptionAtRange(distance: Double): Double {
        if (distance >= REACH) return NONE
        val closeness = 1.0 - distance / REACH
        // Squared, so the last few blocks are where nearly all of it happens.
        return closeness * closeness
    }

    /**
     * Every unsealed wound within [REACH] of [at], nearest first.
     *
     * Allocates, unlike [corruptionAt], and is meant for the once-a-second questions the server asks rather
     * than the many-a-frame ones the fog asks.
     */
    fun openNear(level: Level, at: Vec3): List<BlockPos> {
        val found = mutableListOf<Pair<BlockPos, Double>>()
        eachOpenWound(level, at) { wound, away -> found.add(wound to away) }
        return found.sortedBy { it.second }.map { it.first }
    }

    private fun nearestSquared(level: Level, at: Vec3): Double {
        var nearest = Double.MAX_VALUE
        eachOpenWound(level, at) { _, away -> if (away < nearest) nearest = away }
        return nearest
    }

    /**
     * Every open wound in reach of [at], with how far away it is — the one walk both questions are built of.
     *
     * Inline and allocating nothing, because the fog asks this many times a frame and almost every position
     * in almost every Age is nowhere near a wound: the common case has to be a map lookup that misses.
     *
     * **Never trusts the index about what is there.** A position can outlive its block half a dozen ways — a
     * chunk unloads, somebody replaces it, a world changes underneath — and asking a `void_air` whether it
     * is sealed throws on the render thread, which is a crash rather than a wrong colour. The index says
     * where to *look*, and the block state says what is found.
     */
    private inline fun eachOpenWound(level: Level, at: Vec3, visit: (BlockPos, Double) -> Unit) {
        val chunks = byLevel[level] ?: return
        val chunkX = at.x.toInt() shr CHUNK_BITS
        val chunkZ = at.z.toInt() shr CHUNK_BITS
        for (x in chunkX - CHUNKS_IN_REACH..chunkX + CHUNKS_IN_REACH) {
            for (z in chunkZ - CHUNKS_IN_REACH..chunkZ + CHUNKS_IN_REACH) {
                for (wound in chunks[ChunkPos.pack(x, z)] ?: continue) {
                    val state = level.getBlockState(wound)
                    if (!state.`is`(AgeContent.WOUND_BLOCK)) continue
                    // The state rather than the index: a box finished a tick ago must count immediately.
                    if (state.getValue(WoundBlock.SEALED)) continue
                    val away = at.distanceToSqr(wound.x + HALF, wound.y + HALF, wound.z + HALF)
                    if (away < REACH * REACH) visit(wound, away)
                }
            }
        }
    }

    private fun chunkHolding(at: BlockPos) = ChunkPos.pack(at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)

    /** Not corrupted at all, which is everywhere in almost every Age. */
    const val NONE = 0.0

    /**
     * How far a wound's corruption carries, in blocks.
     *
     * Short on purpose. §5.1 asks for dread with *a source and a direction* that a player can navigate by,
     * and a reach long enough to overlap its neighbours would be an Age that is uniformly grim — which is
     * the ambient misery the gradient exists instead of.
     *
     * **Back to 24 after the second walk** (Jonah, 2026-08-08). It was widened to 32 on the first, which
     * was a step too far in both directions at once: the effect reads better contained than carried, and
     * a wound that tints a whole clearing stops being a thing you can walk away from.
     */
    const val REACH = 24.0

    private const val CHUNK_BITS = 4
    private const val HALF = 0.5

    /** Enough chunks either way to cover [REACH], so no wound inside it is missed. */
    private val CHUNKS_IN_REACH = (REACH.toInt() shr CHUNK_BITS) + 1
}
