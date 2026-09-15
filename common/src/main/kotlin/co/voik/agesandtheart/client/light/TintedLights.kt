package co.voik.agesandtheart.client.light

import co.voik.agesandtheart.ChunkBlockIndex
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
import net.minecraft.world.phys.Vec3

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
        if (!weCanReachTheMesher()) return
        casts[block] = colour
    }

    /**
     * Whether the chunk mesher in use is one there is a seam in.
     *
     * Two are: vanilla's, through `ModelBlockRenderer.putQuadWithTint`, and Fabric's Indigo, through a
     * quad transform pushed onto the emitter it is handed. **The ones listed below replace the mesher
     * outright** and neither seam is called, so the feature simply is not there. Nothing breaks and
     * nothing crashes; it goes quiet, which is the worst way for a feature to be absent because it looks
     * like a bug in ours. So it is refused up front and said once in the log.
     *
     * **Iris and Oculus are named for the log's sake rather than the test's**: both require one of the
     * others to run, so the mesher check already covers them, but somebody reading the line wants to see
     * the mod they actually installed.
     *
     * Asked once. The answer cannot change while the game is running, and this is called per registration.
     */
    private fun weCanReachTheMesher(): Boolean {
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
     * **Indigo is deliberately not among them**, though it does replace the mesher and ships inside Fabric
     * API, so it is present in every Fabric installation there will ever be. It listed here from the day
     * the omission was found until the day the Fabric seam was built, and that was the honest state: the
     * vanilla Mixin applied, found its target and was never once called on that loader.
     *
     * Ordered so the log names the thing a player would recognise first where more than one is present.
     */
    private val MESHER_REPLACEMENTS = listOf("iris", "oculus", "sodium", "embeddium", "rubidium", "nvidium")

    /**
     * Whether any block that casts a tint is **standing in a world** — the painter's early-out.
     *
     * False almost always, and a remembered flag rather than a walk of the index so that asking it stays
     * free.
     */
    fun anythingIsPlaced(): Boolean = anythingPlaced

    /**
     * Whether the index holds anything at all — recomputed whenever it changes rather than counted up and
     * down, because a count has to be right at four call sites and a recount only has to be run at them.
     * Every one of those is a chunk arriving, a chunk going, or a block changing, so it is never hot.
     */
    @Volatile
    private var anythingPlaced = false

    private fun restock() {
        version++
        val nowHolding = index.holdsAnything()
        // **Said once at each crossing, and it earns the line.** Whether anything is indexed at all is the
        // first question when no tint appears, and it is otherwise invisible from inside the game: a
        // crystal renders its own colour through an unrelated seam, so the feature looks alive when the
        // index is empty and nothing is being painted.
        if (nowHolding != anythingPlaced) {
            Constants.LOG.info(
                if (nowHolding) "Coloured light: something is casting, and quads near it are being tinted"
                else "Coloured light: nothing is casting anywhere loaded, so the painter is idle",
            )
            // Starting over here rather than at the world boundary is what makes the reading an
            // experiment: walk away until the log says the painter is idle, and the next approach
            // reports from nothing again.
            if (!nowHolding) TintedLightPainter.forgetTheBrightest()
        }
        anythingPlaced = nowHolding
    }

    fun colourOf(state: BlockState): Int? = casts[state.block]?.invoke(state)

    /**
     * Where they are and what colour each casts, by chunk, because the mesher runs on worker threads against
     * a snapshot of the world and cannot go asking the level.
     *
     * **Concurrent throughout, and weakly keyed.** It is written from the client thread as chunks arrive and
     * blocks change, and read from **every mesher worker** through `TintedLightPainter` with no lock. Weak,
     * because levels other than the one the client stands in feed it too — the linking panel's preview
     * levels load their chunks through the same event — and are let go with the level rather than held
     * until the client leaves the server.
     */
    private val index = ChunkBlockIndex(::colourOf, readAcrossThreads = true)

    /**
     * Bumped whenever the index moves, so a reader that remembers an answer can tell that it has gone
     * stale — see `TintedLightPainter`'s vertex cache, which lives on mesher threads and would otherwise
     * repaint a section from the colours that stood there before the change that asked for the repaint.
     */
    @Volatile
    var version: Int = 0
        private set

    /**
     * Reads [chunk] into the index, dismissing each section off its palette first.
     *
     * A section holding nothing that tints is settled without a single position being read, which is what
     * makes this affordable to run on every chunk that loads.
     */
    fun stocked(level: Level, chunk: ChunkAccess) {
        if (casts.isEmpty()) return
        index.stocked(level, chunk)
        restock()
    }

    fun emptied(level: Level, at: ChunkPos) {
        index.emptied(level, at)
        restock()
    }

    fun forget() {
        index.forget()
        restock()
    }

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
        if (after == null) index.gone(level, at) else index.arrived(level, at, after)
        restock()
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
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        // From the middle of [at] to the middle of each source, which is the same distance as corner to corner.
        index.eachValueWithin(level, Vec3.atCenterOf(at), REACH) { _, colour, away ->
            val near = 1.0 - Math.sqrt(away) / REACH
            val share = near * near
            red += ARGB.red(colour) * share
            green += ARGB.green(colour) * share
            blue += ARGB.blue(colour) * share
            weight += share
        }
        if (weight <= NOTHING) return null
        // **Light adds, and the peak is what is normalised away.** Dividing by the weight was a weighted
        // *average*, which is how paint mixes: two complementary hues average to grey, so a red and a cyan
        // crystal cancelled each other into white light with no colour left in it. Light does not work
        // that way — red and blue make magenta, red and green make yellow, and red and cyan genuinely do
        // make white. So the contributions are summed, and the sum is scaled until its brightest channel
        // is full.
        //
        // Scaling by the peak rather than clamping is what keeps the obvious case right: two red crystals
        // sum to twice red, which clamped would be a *duller* red than one of them and normalised is the
        // same red. What survives is the **hue**; how bright it lands is the light engine's, and how
        // strongly it is taken is the painter's.
        val peak = maxOf(red, green, blue)
        if (peak <= NOTHING) return null
        return ARGB.color(
            atFullSaturation(red, peak),
            atFullSaturation(green, peak),
            atFullSaturation(blue, peak),
        )
    }

    /** One channel of a summed colour, against its brightest — see the reading in [reaching]. */
    private fun atFullSaturation(channel: Double, peak: Double): Int =
        (channel / peak * FULL_CHANNEL).toInt().coerceIn(NONE, FULL_CHANNEL)

    /** How far a tint carries, in blocks. Shorter than the light itself, so the colour fades first. */
    const val REACH = 10.0

    private const val NOTHING = 0.0
    private const val FULL_CHANNEL = 255
    private const val NONE = 0
}
