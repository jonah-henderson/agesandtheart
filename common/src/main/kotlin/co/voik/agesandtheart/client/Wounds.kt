package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.WoundBlock
import net.minecraft.core.BlockPos
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Where the wounds are, as the client knows it — so anything can ask **how near the nearest one is** without
 * searching the world for black blocks (design §5.1).
 *
 * **This is the one place the block-entity cost pays for itself.** Every wound already carries one for the
 * renderer, so it can announce itself on load and drop out on removal, and the index is maintained by two
 * events rather than by scanning. A corruption gradient asks this many times a frame, and a search would be
 * the whole reason not to have one.
 *
 * **Chunk-keyed**, so a query walks only the chunks within reach rather than every wound in the world — an
 * Age at the top of the register holds thousands and a linear scan would be hopeless.
 *
 * **Sealing is read from the world, never cached here.** Boxing a wound in rewrites its block state through
 * `neighborChanged` and never touches its entity, so a flag kept in this index would go stale the moment
 * somebody finished the box — which is exactly when it has to be right.
 */
object Wounds {

    private val byChunk = HashMap<Long, MutableSet<BlockPos>>()

    /**
     * Which world these positions belong to, so leaving one cannot carry its wounds into the next.
     *
     * **A crash, walked 2026-08-07.** This index outlived a dimension change: the coordinates of an Age's
     * wounds were still in it on arriving at the Spire, where those blocks are `void_air`, and asking one
     * whether it was sealed threw on the render thread. There was a `forget` for exactly this and nothing
     * ever called it — so the level is *watched* here rather than trusted to a caller, and forgetting is
     * something the index does to itself.
     */
    private var belongsTo: Level? = null

    /** Called as a wound's entity loads. */
    fun arrived(level: Level, at: BlockPos) {
        if (level !== belongsTo) {
            byChunk.clear()
            belongsTo = level
        }
        byChunk.getOrPut(ChunkPos.pack(at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)) { HashSet() }.add(at.immutable())
    }

    /** And as it goes — a chunk unloading, or the block being replaced. */
    fun gone(at: BlockPos) {
        val key = ChunkPos.pack(at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)
        val here = byChunk[key] ?: return
        here.remove(at)
        if (here.isEmpty()) byChunk.remove(key)
    }

    /** Everything, for a client leaving a server outright. */
    fun forget() {
        byChunk.clear()
        belongsTo = null
    }

    /**
     * How corrupted [at] is, from nothing at all to fully — the number every gradient reads.
     *
     * One over the distance rather than a linear falloff, so the corruption is concentrated hard around
     * the wound and fades quickly: a wound should make *its own place* dreadful (§5.1) and not tint half
     * an Age. A **sealed** wound contributes nothing, which is what boxing one in buys.
     */
    fun corruptionAt(level: BlockGetter, at: Vec3): Double {
        val nearest = nearestSquared(level, at)
        if (nearest >= REACH * REACH) return NONE
        val distance = sqrt(nearest)
        val closeness = 1.0 - distance / REACH
        // Squared, so the last few blocks are where nearly all of it happens.
        return closeness * closeness
    }

    private fun nearestSquared(level: BlockGetter, at: Vec3): Double {
        val chunkX = at.x.toInt() shr CHUNK_BITS
        val chunkZ = at.z.toInt() shr CHUNK_BITS
        var nearest = Double.MAX_VALUE
        for (x in chunkX - CHUNKS_IN_REACH..chunkX + CHUNKS_IN_REACH) {
            for (z in chunkZ - CHUNKS_IN_REACH..chunkZ + CHUNKS_IN_REACH) {
                val here = byChunk[ChunkPos.pack(x, z)] ?: continue
                for (wound in here) {
                    val state = level.getBlockState(wound)
                    // **Never trust the index about what is there.** A position can outlive its block — a
                    // chunk unloads, somebody replaces it, a world changes underneath — and asking a
                    // `void_air` whether it is sealed throws on the render thread, which is a crash rather
                    // than a wrong colour. The index says where to *look*, never what is found.
                    if (!state.`is`(AgeContent.WOUND_BLOCK)) continue
                    // The state rather than the index: a box finished a tick ago must count immediately.
                    if (state.getValue(WoundBlock.SEALED)) continue
                    val away = at.distanceToSqr(wound.x + HALF, wound.y + HALF, wound.z + HALF)
                    if (away < nearest) nearest = away
                }
            }
        }
        return nearest
    }

    /** Not corrupted at all, which is everywhere in almost every Age. */
    const val NONE = 0.0

    /**
     * How far a wound's corruption carries, in blocks.
     *
     * Short on purpose. §5.1 asks for dread with *a source and a direction* that a player can navigate by,
     * and a reach long enough to overlap its neighbours would be an Age that is uniformly grim — which is
     * the ambient misery the gradient exists instead of.
     *
     * **Widened from 24 after the walk** (Jonah, 2026-08-07): the effect was right and wanted a little
     * more of itself. Widening rather than steepening is what buys *both* asks at once — every distance
     * inside the old reach is now more corrupted than it was, and the corruption carries further.
     */
    const val REACH = 32.0

    private const val CHUNK_BITS = 4
    private const val HALF = 0.5

    /** Enough chunks either way to cover [REACH], so no wound inside it is missed. */
    private val CHUNKS_IN_REACH = (REACH.toInt() shr CHUNK_BITS) + 1
}
