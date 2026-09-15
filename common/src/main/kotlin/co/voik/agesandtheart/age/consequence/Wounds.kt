package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.ChunkBlockIndex
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Where the wounds are — so anything can ask **how near the nearest one is** without searching the world
 * for black blocks (design §5.1).
 *
 * **Fed by a chunk scan, because a wound is a plain block.** It carried a block entity once, purely so the
 * renderer had something to hang on, and that made every wound an object in memory and a record in chunk
 * NBT — a ceiling on how many an Age could hold, which is fatal for a register whose whole point is that
 * the number climbs while nobody is choosing it (§5.2.1). So the entity is gone and the index is built by
 * reading each chunk as it loads. **The palette makes that nearly free**: `LevelChunkSection.maybeHas`
 * answers off the section's palette, so a section that has never held a wound is dismissed without a single
 * block being looked at, which is every section in almost every world.
 *
 * **Chunk-keyed**, so a query walks only the chunks within reach rather than every wound in the world — an
 * Age at the top of the register holds thousands and a linear scan would be hopeless.
 *
 * **Both sides keep one**, and they want it for different things: the client draws a gradient out of it and
 * the server decides where the Age is dangerous ([Hostility]). Both are maintained by the same two events.
 *
 * **Keyed by level, which is what makes it safe on a server.** A client stands in one world at a time and a
 * server holds every loaded Age at once, so a single index shared between them would have an Age's
 * coordinates answering questions asked about somewhere else. Weakly keyed, so a world that goes away takes
 * its wounds with it and no cleanup has to be remembered — which is precisely the cleanup that was not.
 *
 * **Sealing is read from the world, never cached here.** Boxing a wound in rewrites its block state through
 * `neighborChanged`, so a flag kept in this index would go stale the moment
 * somebody finished the box — which is exactly when it has to be right.
 */
object Wounds {

    private val index = ChunkBlockIndex.matching { state -> state.`is`(AgeContent.WOUND_BLOCK) }

    /** Called as one is placed — by the Age tearing a fresh one, or by a block update carrying it. */
    fun arrived(level: Level, at: BlockPos) = index.arrived(level, at, Unit)

    /** And as it goes — the block being replaced, which only the Age itself can do. */
    fun gone(level: Level, at: BlockPos) = index.gone(level, at)

    /**
     * Every wound a chunk holds, read as it loads — the index's whole supply.
     *
     * Called on both sides, from `CommonSetup.chunkLoaded` and `ClientSetup.chunkLoaded`.
     */
    fun stocked(level: Level, chunk: ChunkAccess) = index.stocked(level, chunk)

    /** And as it goes, so an unloaded chunk's wounds stop answering questions about a place nobody is. */
    fun emptied(level: Level, at: ChunkPos) = index.emptied(level, at)

    /**
     * A block changing where a client can see it — **the only way the client hears of a wound torn after
     * its chunk arrived.**
     *
     * [arrived] is called from `WoundBlock.onPlace`, and `LevelChunk.setBlockState` skips `onPlace`
     * entirely on the client (verified against the 26.1.2 jar), so the client index would otherwise be
     * whatever [stocked] read at chunk load and never move. A wound the Age tore while somebody stood
     * there would then be a block the client holds and the renderer never draws, until they walked far
     * enough away to unload the chunk and came back.
     *
     * The seam is `ClientLevel.setBlocksDirty`, which vanilla calls for every state change that actually
     * changed something, before and regardless of the update flags. Sealing moves a wound from one state
     * to another and is deliberately not a move in the index: it is the same wound in the same place, and
     * whether it is sealed is read from the world.
     *
     * Returns whether a wound arrived, which is the moment `WoundField` shows it tearing open.
     */
    fun noticed(level: Level, at: BlockPos, was: BlockState, now: BlockState): Boolean {
        val wasOne = was.`is`(AgeContent.WOUND_BLOCK)
        val isOne = now.`is`(AgeContent.WOUND_BLOCK)
        if (wasOne == isOne) return false
        if (!isOne) {
            gone(level, at)
            return false
        }
        arrived(level, at)
        return true
    }

    /** Everything, for a client leaving a server outright. */
    fun forget() = index.forget()

    /**
     * How many this chunk holds, off the index rather than off its blocks.
     *
     * **The reason this exists is a measured one.** [stocked] already walks a chunk as it loads, and the
     * worsening pass needs the same number a moment later — asking the chunk again means scanning every
     * section that holds a wound, four thousand blocks apiece, on every chunk load. That is fine at four
     * wounds to a chunk and is minutes of generation at twelve, which is a thing an Age reaches two days
     * into the mildest worsening.
     */
    fun countIn(level: Level, at: ChunkPos): Int = index.countIn(level, at)

    /**
     * Every wound within [reach] of [at], **sealed or not** — what the renderer draws.
     *
     * Deliberately not [eachOpenWound]: boxing a wound in contains what it does to the world around it and
     * changes nothing about the tear, so a sealed one is still there and still drawn. Somebody who opens
     * their own box has to find what they buried.
     */
    fun eachNear(level: Level, at: Vec3, reach: Double, visit: (BlockPos) -> Unit) =
        index.eachWithin(level, at, reach) { wound, _ -> visit(wound) }

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
    private inline fun eachOpenWound(level: Level, at: Vec3, visit: (BlockPos, Double) -> Unit) =
        index.eachWithin(level, at, REACH) { wound, away ->
            val state = level.getBlockState(wound)
            val isStillAWound = state.`is`(AgeContent.WOUND_BLOCK)
            // The state rather than the index: a box finished a tick ago must count immediately.
            if (isStillAWound && !state.getValue(WoundBlock.SEALED)) visit(wound, away)
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
     * **Back to 24 after the second walk** (Jonah, 2026-08-08). It was widened to 32 on the first, which
     * was a step too far in both directions at once: the effect reads better contained than carried, and
     * a wound that tints a whole clearing stops being a thing you can walk away from.
     */
    const val REACH = 24.0
}
