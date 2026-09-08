package co.voik.agesandtheart.client.light

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.Services
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.Collections
import java.util.WeakHashMap

/**
 * Blocks that give their light a colour, and where they are (see `notes/coloured-light-research.md`).
 *
 * **Minecraft has no coloured light and this does not add one.** The light engine carries two scalar
 * channels and the lightmap is a sixteen-by-sixteen texture indexed by them, with no position in it — so a
 * surface's *brightness* stays vanilla's entirely. What this does is tint the colour a lit surface is drawn
 * in, at the one place per-position colour can enter a chunk mesh, which together with the block's own
 * `lightLevel` reads as coloured light. Neither half works alone: the tint only ever darkens, and the
 * brightness that makes it read comes from the light engine.
 *
 * **A tool rather than a feature.** Anything that wants to colour the light it casts registers here and is
 * done — the rime crystals are the first customer and nothing about this knows that. A wound bleeding its
 * violet into the rock around it, a lava tube's orange, an Age whose whole ground glows: all of them are
 * this plus a call.
 */
object TintedLights {

    /** What a block does to the colour of the light it casts, or nothing where it casts none. */
    private val casts = mutableMapOf<Block, (BlockState) -> Int?>()

    /**
     * Declares that [block] tints its light.
     *
     * Called at client start and never after, so the map is immutable by the time any mesher worker reads
     * it — which is the whole of the thread-safety story on this side.
     */
    fun cast(block: Block, colour: (BlockState) -> Int?) {
        if (!theMesherIsVanillas()) return
        casts[block] = colour
    }

    /**
     * Whether the chunk mesher this hangs on is still the one we hooked.
     *
     * **Sodium and its forks replace the mesher outright**, so `ModelBlockRenderer.putQuadWithTint` — the
     * one place per-position colour can enter terrain — is never called and this feature simply is not
     * there. Nothing breaks and nothing crashes; it goes quiet, which is the worst way for a feature to be
     * absent because it looks like a bug in ours. So it is refused up front and said once in the log.
     *
     * **Iris and Oculus are named for the log's sake rather than the test's**: both require one of the
     * others to run, so the mesher check already covers them, but somebody reading the line wants to see
     * the mod they actually installed.
     *
     * Asked once. The answer cannot change while the game is running, and this is called per registration.
     */
    private fun theMesherIsVanillas(): Boolean {
        val known = replaced
        if (known != null) return !known
        val by = MESHER_REPLACEMENTS.firstOrNull { Services.PLATFORM.isModLoaded(it) }
        replaced = by != null
        if (by != null) {
            Constants.LOG.info(
                "Coloured light is off: {} replaces the chunk mesher, so the seam it needs is never called.",
                by,
            )
        }
        return by == null
    }

    private var replaced: Boolean? = null

    /**
     * The renderers that take the mesher over.
     *
     * Ordered so the log names the thing a player would recognise first where more than one is present.
     */
    private val MESHER_REPLACEMENTS = listOf("iris", "oculus", "sodium", "embeddium", "rubidium", "nvidium")

    fun castsAnything(): Boolean = casts.isNotEmpty()

    fun colourOf(state: BlockState): Int? = casts[state.block]?.invoke(state)

    /**
     * Where they are, by chunk — **the index `Wounds` built and for the same reason**.
     *
     * The mesher runs on worker threads against an immutable snapshot of the world, so it cannot go asking
     * the level; and searching the snapshot per block would be tens of thousands of reads per section. An
     * index answers in one map lookup, is filled off the palette as chunks arrive, and is the pattern this
     * pack already trusts.
     */
    private val byLevel = WeakHashMap<Level, MutableMap<Long, MutableMap<BlockPos, Int>>>()

    /**
     * Reads [chunk] into the index, dismissing each section off its palette first.
     *
     * A section holding nothing that tints is settled without a single position being read, which is what
     * makes this affordable to run on every chunk that loads.
     */
    fun stocked(level: Level, chunk: ChunkAccess) {
        if (casts.isEmpty()) return
        val found = mutableMapOf<BlockPos, Int>()
        val holds = { state: BlockState -> state.block in casts }
        for (index in chunk.sections.indices) {
            val section = chunk.sections[index]
            if (section.hasOnlyAir() || !section.maybeHas(holds)) continue
            val bottom = chunk.getSectionYFromSectionIndex(index) * SECTION
            for (x in 0..<SECTION) for (y in 0..<SECTION) for (z in 0..<SECTION) {
                val state = section.getBlockState(x, y, z)
                val colour = colourOf(state) ?: continue
                found[BlockPos(chunk.pos.minBlockX + x, bottom + y, chunk.pos.minBlockZ + z)] = colour
            }
        }
        val chunks = byLevel.getOrPut(level) { Collections.synchronizedMap(mutableMapOf()) }
        if (found.isEmpty()) chunks.remove(ChunkPos.pack(chunk.pos.x, chunk.pos.z)) else chunks[ChunkPos.pack(chunk.pos.x, chunk.pos.z)] = found
    }

    fun emptied(level: Level, at: ChunkPos) {
        byLevel[level]?.remove(ChunkPos.pack(at.x, at.z))
    }

    fun forget() = byLevel.clear()

    /**
     * One block changed — kept in step by hand, because a chunk is only read whole when it arrives.
     *
     * Returns whether the index moved, so the caller only pays for a section rebuild when something a
     * player can see actually changed.
     */
    fun noticed(level: Level, at: BlockPos, was: BlockState, now: BlockState): Boolean {
        val before = colourOf(was)
        val after = colourOf(now)
        if (before == after) return false
        val chunks = byLevel.getOrPut(level) { Collections.synchronizedMap(mutableMapOf()) }
        val key = ChunkPos.pack(at.x shr CHUNK_BITS, at.z shr CHUNK_BITS)
        val holding = chunks.getOrPut(key) { mutableMapOf() }
        if (after == null) holding.remove(at) else holding[at] = after
        if (holding.isEmpty()) chunks.remove(key)
        rebuildAround(at)
        return true
    }

    /**
     * Marks everything this source could have been colouring for a rebuild.
     *
     * **The colour lives in the mesh, so nothing changes until the mesh is built again** — and vanilla only
     * rebuilds the section the block is *in*. A crystal lighting ten blocks reaches into its neighbours, so
     * they have to be told too.
     *
     * This is the same cost a torch already pays: placing one relights and rebuilds everything in range,
     * every time, and nobody notices. Doing it here rather than in the Mixin keeps that Mixin to one line
     * and puts the reach beside the reach it has to agree with.
     */
    private fun rebuildAround(at: BlockPos) {
        val reach = REACH.toInt()
        Minecraft.getInstance().levelRenderer.setBlocksDirty(
            at.x - reach, at.y - reach, at.z - reach,
            at.x + reach, at.y + reach, at.z + reach,
        )
    }

    /**
     * The colour reaching [at], or nothing — every tinting block in range, weighted by how near it is.
     *
     * **Blended rather than nearest**, so two crystals of different colours standing together give the
     * mixture a player would expect rather than a hard line down the middle of the room.
     */
    fun reaching(level: Level, at: BlockPos): Int? {
        val chunks = byLevel[level] ?: return null
        if (chunks.isEmpty()) return null
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        val fromChunk = at.x shr CHUNK_BITS
        val fromZ = at.z shr CHUNK_BITS
        for (chunkX in fromChunk - CHUNKS_IN_REACH..fromChunk + CHUNKS_IN_REACH) {
            for (chunkZ in fromZ - CHUNKS_IN_REACH..fromZ + CHUNKS_IN_REACH) {
                val holding = chunks[ChunkPos.pack(chunkX, chunkZ)] ?: continue
                synchronized(holding) {
                    for ((where, colour) in holding) {
                        val away = where.distSqr(at)
                        if (away > REACH * REACH) continue
                        val near = 1.0 - Math.sqrt(away) / REACH
                        val share = near * near
                        red += ARGB.red(colour) * share
                        green += ARGB.green(colour) * share
                        blue += ARGB.blue(colour) * share
                        weight += share
                    }
                }
            }
        }
        if (weight <= NOTHING) return null
        // Pulled back toward white by how weak the strongest contribution was, so a crystal far off tints
        // faintly rather than painting the far wall its full colour.
        val strength = weight.coerceAtMost(FULLY)
        return ARGB.color(
            mixed(red / weight, strength),
            mixed(green / weight, strength),
            mixed(blue / weight, strength),
        )
    }

    private fun mixed(channel: Double, strength: Double): Int =
        (WHITE + (channel - WHITE) * strength).toInt().coerceIn(NONE, WHITE.toInt())

    /** How far a tint carries, in blocks. Shorter than the light itself, so the colour fades first. */
    const val REACH = 10.0

    private const val SECTION = 16
    private const val CHUNK_BITS = 4
    private val CHUNKS_IN_REACH = (REACH.toInt() shr CHUNK_BITS) + 1
    private const val NOTHING = 0.0
    private const val FULLY = 1.0
    private const val WHITE = 255.0
    private const val NONE = 0
}
