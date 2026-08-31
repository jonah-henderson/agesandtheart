package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
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

    /** Called as one is placed — by the Age tearing a fresh one, or by a block update carrying it. */
    fun arrived(level: Level, at: BlockPos) {
        val chunks = byLevel.getOrPut(level) { HashMap() }
        chunks.getOrPut(chunkHolding(at)) { HashSet() }.add(at.immutable())
    }

    /** And as it goes — the block being replaced, which only the Age itself can do. */
    fun gone(level: Level, at: BlockPos) {
        val chunks = byLevel[level] ?: return
        val chunk = chunkHolding(at)
        val here = chunks[chunk] ?: return
        here.remove(at)
        if (here.isEmpty()) chunks.remove(chunk)
        if (chunks.isEmpty()) byLevel.remove(level)
    }

    /**
     * Every wound a chunk holds, read as it loads — the index's whole supply.
     *
     * **The palette is what makes this affordable.** `maybeHas` answers from the section's palette rather
     * than its contents, so a section that has never held a wound costs one set lookup and no block reads,
     * which is every section in every ordinary world. Only a section that might have one is walked.
     *
     * Called from both loaders' chunk-load events, the same shape as `Happenings.tick` — there is no shared
     * entry point, and a service for one method would fragment `PlatformHelper` for a one-off (`CLAUDE.md`).
     */
    fun stocked(level: Level, chunk: ChunkAccess) {
        val here = chunk.pos
        val holds = { state: BlockState -> state.`is`(AgeContent.WOUND_BLOCK) }
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

    /** And as it goes, so an unloaded chunk's wounds stop answering questions about a place nobody is. */
    fun emptied(level: Level, at: ChunkPos) {
        val chunks = byLevel[level] ?: return
        chunks.remove(ChunkPos.pack(at.x, at.z))
        if (chunks.isEmpty()) byLevel.remove(level)
    }

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
     */
    fun noticed(level: Level, at: BlockPos, was: BlockState, now: BlockState) {
        val wasOne = was.`is`(AgeContent.WOUND_BLOCK)
        val isOne = now.`is`(AgeContent.WOUND_BLOCK)
        if (wasOne == isOne) return
        if (!isOne) {
            gone(level, at)
            return
        }
        arrived(level, at)
        noteTheMoment(level, at)
    }

    /**
     * When a wound was seen to arrive, for the few that were — **the difference between an Age worsening
     * in front of somebody and one found already worse.**
     *
     * Only [noticed] writes here, and that is the whole rule: a wound read out of a chunk as it loads was
     * always there as far as this client is concerned, and a chunk arriving should not make every hole in
     * it lunge open at once. One torn while somebody stood there is the case worth showing.
     *
     * **Pruned from the front rather than swept**, which a `LinkedHashMap` makes free: entries go in in
     * time order, so everything expired is at the head and the walk stops at the first one that is not.
     */
    private val opening = WeakHashMap<Level, LinkedHashMap<BlockPos, Long>>()

    private fun noteTheMoment(level: Level, at: BlockPos) {
        val here = opening.getOrPut(level) { LinkedHashMap() }
        val now = System.currentTimeMillis()
        val stale = here.entries.iterator()
        while (stale.hasNext()) {
            if (now - stale.next().value < OPENS_OVER) break
            stale.remove()
        }
        here[at.immutable()] = now
    }

    /**
     * What is still tearing itself open in [level], or null where nothing is — which is nearly always.
     *
     * Handed out whole rather than asked per wound, because the renderer asks for every wound in sight on
     * every frame and a lookup apiece would be thousands of them a second for an answer that is usually
     * "nothing at all".
     */
    fun openingIn(level: Level): Map<BlockPos, Long>? = opening[level]

    /** How long a wound takes to tear itself open, in milliseconds — brief, and unmistakably an event. */
    const val OPENS_OVER = 700L

    /** Everything, for a client leaving a server outright. */
    fun forget() = byLevel.clear()

    /**
     * How many this chunk holds, off the index rather than off its blocks.
     *
     * **The reason this exists is a measured one.** [stocked] already walks a chunk as it loads, and the
     * worsening pass needs the same number a moment later — asking the chunk again means scanning every
     * section that holds a wound, four thousand blocks apiece, on every chunk load. That is fine at four
     * wounds to a chunk and is minutes of generation at twelve, which is a thing an Age reaches two days
     * into the mildest worsening.
     */
    fun countIn(level: Level, at: ChunkPos): Int = byLevel[level]?.get(ChunkPos.pack(at.x, at.z))?.size ?: 0

    /**
     * Every wound within [reach] of [at], **sealed or not** — what the renderer draws.
     *
     * Deliberately not [eachOpenWound]: boxing a wound in contains what it does to the world around it and
     * changes nothing about the tear, so a sealed one is still there and still drawn. Somebody who opens
     * their own box has to find what they buried.
     */
    fun eachNear(level: Level, at: Vec3, reach: Double, visit: (BlockPos) -> Unit) {
        val chunks = byLevel[level] ?: return
        val chunkX = at.x.toInt() shr CHUNK_BITS
        val chunkZ = at.z.toInt() shr CHUNK_BITS
        val about = (reach.toInt() shr CHUNK_BITS) + 1
        for (x in chunkX - about..chunkX + about) {
            for (z in chunkZ - about..chunkZ + about) {
                for (wound in chunks[ChunkPos.pack(x, z)] ?: continue) {
                    if (at.distanceToSqr(wound.x + HALF, wound.y + HALF, wound.z + HALF) < reach * reach) {
                        visit(wound)
                    }
                }
            }
        }
    }


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

    /** A chunk section's edge, and a chunk's width — the same sixteen. */
    private const val SECTION = 16

    /** Enough chunks either way to cover [REACH], so no wound inside it is missed. */
    private val CHUNKS_IN_REACH = (REACH.toInt() shr CHUNK_BITS) + 1
}
