package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluids

/**
 * The rising sea — design §5.2's deluge, and the second gate on the deep-ocean material of §7.1.2.
 *
 * **The water comes up, and it comes up under torrential rain.** The phenomenon is a downpour that will not
 * stop, and the sea arriving is what the downpour does.
 *
 * **It rises toward the level the recipe already names**, which settles the fairness problem before it
 * arises. A sea climbing without a known end is the punishment register at its purest — you build at
 * seventy, come back three sessions later, and the water is at seventy-two. So the Age *arrives* at what
 * was written rather than starting there: it opens [FALLS_BY] blocks short and climbs, the final state is
 * exactly what a writer asked for, and the desk's reading already answers "how high will it get".
 *
 * **It resolves.** Once the written level is reached the phenomenon is over and what is left is a drowned
 * Age — a standing condition rather than a permanent tax.
 *
 * **Three parts, and only the first of them is stored.**
 *
 * - **The sea's own level**, which is the counter: [AgeSavedData.presenceIn] ticks, turned into blocks by
 *   [shortnessAt], handed to the generator by [stand]. This is §5.4's one licensed number, and what buys it
 *   is that the sea surface has to be *globally coherent* — a chunk generated three thousand blocks out
 *   must flood to the same line as the one under your feet, and no sampler can promise that.
 * - **The catch-up**, [flood], which is a rule over sampled blocks like every other phenomenon in the set.
 *   New chunks come out of the generator already at the right height; ground that was made earlier has to
 *   be brought up to it, and that is block work near the players who can see it.
 * - **The pooling**, [pool], which needs no number at all — the water is the state. It is what keeps a
 *   ceiling from being an immunity: any writer who reads their own book knows a safe altitude, and rain
 *   that stands where it falls reaches them there.
 */
object Deluge {

    /**
     * How far under its written level a drowning Age's sea begins.
     *
     * Deep enough that the shape of play changes as it climbs — shoreline, footpaths and cave mouths go
     * under within the arc rather than all at the end — and shallow enough that the Age is recognisably
     * the one that was written throughout. **UNWALKED; this is the dial that decides what the arc feels
     * like**, and it wants walking against a real coastline rather than reasoning about.
     */
    const val FALLS_BY = 24

    /**
     * How long somebody has to be in the Age for the sea to gain one block, in ticks.
     *
     * **Flat rather than accelerating**, for the reason the ceiling exists: a writer should be able to say
     * how long they have. At this figure the whole of [FALLS_BY] is about two hours of *being in the Age* —
     * not of the world existing, and not of the server running. UNWALKED.
     */
    const val TICKS_PER_BLOCK = 6000L

    /** How far under its written level this Age's sea stands, after [ticks] of somebody being in it. */
    fun shortnessAt(ticks: Long): Int =
        (FALLS_BY - (ticks / TICKS_PER_BLOCK)).coerceIn(0L, FALLS_BY.toLong()).toInt()

    /** Whether the sea here has finished climbing — which is what "the phenomenon has resolved" means. */
    fun hasResolved(ticks: Long): Boolean = shortnessAt(ticks) == 0

    /**
     * Tell [level]'s generator where its sea stands.
     *
     * **Done for every Age on every tick, including the ones nobody is in.** The *counter* only advances
     * while somebody is present, but the generator must be right whenever a chunk is made — and a chunk
     * can be made in an empty Age by a forceload, a teleport arriving, or a neighbouring player's view. It
     * is an integer compare when nothing has changed.
     */
    fun stand(level: ServerLevel, drowning: Boolean, ticks: Long) {
        val generator = level.chunkSource.generator as? AgeChunkGenerator ?: return
        generator.standShortBy(if (drowning) shortnessAt(ticks) else NOT_DROWNING)
    }

    /**
     * Bring every chunk anybody can see up to where the sea now stands — **the rise itself, and the only
     * part of it that touches blocks.**
     *
     * **The first cut of this was sampled and it did not sell** (Jonah, walked 2026-09-12). `Sampling.sweep`
     * reaches six chunks around a player and fires about an eighth of a column per chunk per tick, so what
     * a player actually saw was a few dozen loose blocks a second scattered over forty thousand columns —
     * blotches that occasionally joined, columns stacking on each other at odd heights, and a hard **wall
     * of water two hundred blocks out** where the sampled region ended and the chunks he had walked through
     * an hour ago stayed at the level they were when he left them.
     *
     * **The lesson is that a sea surface is flat by definition, so every intermediate state is a state a
     * sea cannot be in.** Making the fill smoother was the wrong instinct; what it wanted was for each
     * layer to be *complete*, with the slowness between layers rather than inside one.
     *
     * So the unit of work is a **chunk**, not a column, and the chunks come from what is *visible* rather
     * than from a six-chunk bubble. A chunk is either at the standing level or is brought to it, and the
     * pass moves outward from the players — which is what makes it read as water rushing in rather than
     * appearing all at once.
     *
     * **Nothing is stored.** [waterTopIn] asks the chunk where its own sea is, so a chunk that has been
     * unloaded for an hour is caught up the first time anybody can see it again, with no watermark to keep
     * and no ledger beside the recipe. The guarantee is *correct before you can see it*, which is the same
     * one the generator already makes for chunks that do not exist yet.
     */
    fun raise(level: ServerLevel, standing: Int) {
        val sea = level.seaBlock() ?: return
        var sweeps = SWEEPS_A_TICK
        for (packed in inViewNearestFirst(level)) {
            if (sweeps <= 0) return
            val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: continue
            if (!mightBeBehind(chunk, standing, sea)) continue
            val top = waterTopIn(chunk, standing, sea)
            if (top >= standing) continue
            sweepUp(level, chunk, (top - SPILL_REACH).coerceAtLeast(level.minY), standing, sea)
            sweeps--
        }
    }

    /**
     * A cheap look at one column in sixteen: is any of this chunk's sea under where the sea now stands?
     *
     * **It exists because the obvious arrangement moved the wall instead of removing it.** The first cut of
     * [raise] spent a budget of *probes* as well as of sweeps, walking the visible chunks nearest-first —
     * and a chunk that is already up to date still costs a probe. So the nearest sixty-four chunks ate the
     * budget every tick and nothing beyond them was ever looked at. Same fault as the sampled version, four
     * chunks out instead of two hundred blocks.
     *
     * The fix is that deciding *whether* a chunk is behind has to be cheap enough to ask of everything in
     * view, every tick. Sixteen columns is sixteen array reads, so the whole visible region costs about
     * what one full chunk scan used to; only the chunks this says yes to pay for the real one.
     */
    private fun mightBeBehind(chunk: LevelChunk, standing: Int, sea: BlockState): Boolean {
        val seaFluid = sea.fluidState.type
        val cursor = BlockPos.MutableBlockPos()
        val originX = chunk.pos.minBlockX
        val originZ = chunk.pos.minBlockZ
        for (offsetX in 0..<CHUNK_WIDTH step PROBE_STRIDE) {
            for (offsetZ in 0..<CHUNK_WIDTH step PROBE_STRIDE) {
                val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, offsetX, offsetZ) - 1
                if (top >= standing) continue
                cursor.set(originX + offsetX, top, originZ + offsetZ)
                if (chunk.getBlockState(cursor).fluidState.isSourceOfType(seaFluid)) return true
            }
        }
        return false
    }

    /**
     * Where this chunk's own sea stands, which is how far behind it is without anything having recorded it.
     *
     * **The heightmap answers it for nothing.** `MOTION_BLOCKING` counts fluid, so the top of a column is
     * the top of its water where there is water — one array read per column, and the highest of them that
     * is actually the sea's own fluid is where this chunk's sea has got to.
     *
     * A chunk with no sea in it at all answers [standing], which reads as "nothing to do" — correct for
     * high ground, and it is what keeps an inland chunk from being swept every tick forever.
     */
    private fun waterTopIn(chunk: LevelChunk, standing: Int, sea: BlockState): Int {
        val seaFluid = sea.fluidState.type
        var found = Int.MIN_VALUE
        val cursor = BlockPos.MutableBlockPos()
        val originX = chunk.pos.minBlockX
        val originZ = chunk.pos.minBlockZ
        for (offsetX in 0..<CHUNK_WIDTH) {
            for (offsetZ in 0..<CHUNK_WIDTH) {
                val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, offsetX, offsetZ) - 1
                if (top > standing || top <= found) continue
                cursor.set(originX + offsetX, top, originZ + offsetZ)
                if (!chunk.getBlockState(cursor).fluidState.isSourceOfType(seaFluid)) continue
                found = top
            }
        }
        return if (found == Int.MIN_VALUE) standing else found
    }

    /**
     * Jonah's rule, 2026-09-12, and it is the whole of the rise.
     *
     * Sweeping **upward** through the levels under the standing sea, at every block of the sea's own fluid
     * that is *outside* — nothing above it in its column:
     *
     * - a **source** with room over it grows a source into that room, and
     * - a **flowing** block becomes a source.
     *
     * **Upward is what makes it fill rather than creep.** A block placed at one level is the source the
     * next level up grows from in the same pass, so a chunk ten blocks behind comes up in one visit and a
     * chunk one block behind gains exactly one layer. One rule, both jobs, and no second code path for the
     * catch-up.
     *
     * **Vanilla's flow is left switched on, deliberately** — it was the best part of the first attempt
     * (Jonah). Sources placed here spill over lips, run down cliffs and pour into cave mouths, vanilla's
     * own source conversion turns the spill solid where two sources meet, and the next sweep promotes
     * whatever is left flowing in the open. What fills a cave is therefore vanilla's water doing what
     * vanilla's water does, which is why it reads as flooding rather than as blocks being placed.
     *
     * **"Outside" is the heightmap and not `canSeeSky`**, which matters in two ways: it costs an array read
     * where the light engine costs a lookup and a propagation, and it does not care what the light is
     * doing. A roof stops the growth, which is what keeps a sealed room dry; water under an overhang is
     * left to the flow.
     *
     * [from] reaches [SPILL_REACH] below the chunk's own water so that a spill already running down into
     * dry ground is taken in rather than waited on.
     */
    private fun sweepUp(level: ServerLevel, chunk: LevelChunk, from: Int, standing: Int, sea: BlockState) {
        val seaFluid = sea.fluidState.type
        // **Both halves of the fluid count as the sea here**, which is the whole of the flowing rule: a
        // spill is `flowing_water` and a `FluidState` of it is not the source's type.
        val flowingSea = (seaFluid as? FlowingFluid)?.flowing ?: seaFluid
        val cursor = BlockPos.MutableBlockPos()
        val originX = chunk.pos.minBlockX
        val originZ = chunk.pos.minBlockZ
        for (y in from..<standing) {
            for (offsetX in 0..<CHUNK_WIDTH) {
                for (offsetZ in 0..<CHUNK_WIDTH) {
                    val x = originX + offsetX
                    val z = originZ + offsetZ
                    // Outside: nothing in this column stands over this block. `getHeight` answers the
                    // first empty space, so the topmost solid or fluid is one under it.
                    if (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, offsetX, offsetZ) - 1 > y) continue
                    cursor.set(x, y, z)
                    val standingHere = chunk.getBlockState(cursor).fluidState
                    if (standingHere.type != seaFluid && standingHere.type != flowingSea) continue
                    if (!standingHere.isSource) {
                        // Immutable: `setBlockAndUpdate` hands the position on to neighbour updates and
                        // block entities, and a cursor that moves under them is the classic way to poison
                        // a tick queue.
                        level.setBlockAndUpdate(BlockPos(x, y, z), sea)
                        continue
                    }
                    val above = BlockPos(x, y + 1, z)
                    // Read from the chunk and written through the level: the lookup is ours to make cheap,
                    // the update is vanilla's to make happen. Both positions are this chunk's own column.
                    if (!chunk.getBlockState(above).canBeReplaced(sea.fluidState.type)) continue
                    level.setBlockAndUpdate(above, sea)
                }
            }
        }
    }

    /**
     * Every chunk anybody in this Age can see, closest first.
     *
     * **The view distance rather than a bubble of our own**, because the guarantee worth making is about
     * what can be *seen*: a chunk beyond it may be as stale as it likes and nobody can tell. `getChunkNow`
     * returning null is what filters the rest, so this never loads anything.
     *
     * Closest first is the animation: the near chunks come up on the first tick of a step and the far ones
     * over the second or so after, which reads as the water rushing outward from where you stand.
     */
    private fun inViewNearestFirst(level: ServerLevel): List<Long> {
        val reach = level.server.playerList.viewDistance
        val standing = level.players().filter { !it.isSpectator }.map { it.chunkPosition() }
        if (standing.isEmpty()) return emptyList()
        // Packed, and gathered into a set before it is sorted: two players a hundred blocks apart share
        // most of what they can see, and a `ChunkPos` apiece per tick is a few hundred objects a second
        // for a list that is thrown away again.
        val seen = LongOpenHashSet()
        for (eye in standing) {
            for (x in eye.x - reach..eye.x + reach) {
                for (z in eye.z - reach..eye.z + reach) {
                    seen.add(ChunkPos.pack(x, z))
                }
            }
        }
        return seen.toLongArray().sortedBy { packed -> standing.minOf { it.distanceSquared(packed) } }
    }

    /**
     * Rain that stands where it falls — the half of the deluge that has nothing to do with the sea.
     *
     * **A ceiling the writer chose is also an immunity**, and this is what answers it: anybody who read
     * their own book knows a safe altitude and would otherwise be untouchable there forever. So water is
     * placed on sky-lit ground *above* the waterline, and vanilla's own fluid physics does the rest — open
     * ground sheds it, hollows keep it, a walled courtyard fills, and a sealed room with one hole in the
     * roof fills completely. No basin detection, no frontier, and it caps itself structurally: a full bowl
     * spills and stops.
     *
     * **Its counterplay is a roof**, which is the blizzard's bargain in a different costume — the thing
     * that protects your ground protects you.
     *
     * Sampled near the players where the rise is swept over everything in view, and the difference is the
     * point: a sea is one surface and must be coherent everywhere, where a puddle is a local accident and
     * nobody can tell that the one in the next valley never happened.
     */
    fun pool(level: ServerLevel, fury: Double) {
        if (!level.isRaining) return
        val standing = level.seaSurfaceY() ?: return
        Sampling.sweep(level, dropsPerChunk(fury)) { _, at ->
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z)
            // Below the waterline is the sea's business, and a source laid down there would put water
            // inside whatever the sea has not reached yet — the sealed room the promise above depends on.
            if (top <= standing) return@sweep
            val onto = BlockPos(at.x, top, at.z)
            if (!level.getBlockState(onto).canBeReplaced(Fluids.WATER)) return@sweep
            if (!level.canSeeSky(onto)) return@sweep
            level.setBlockAndUpdate(onto, Blocks.WATER.defaultBlockState())
        }
    }

    /**
     * How many columns a chunk gets a drop in per tick.
     *
     * **Deliberately small, and the reason is measured elsewhere**: flowing water at storm scale is one of
     * vanilla's heavier update paths, and §5.2 names this as the sandfall's "do not spawn falling entities
     * at storm scale" lesson wearing new clothes. One source spreads for a long time after it lands, so
     * the rate that matters is how many are *in flight* rather than how many are placed. UNWALKED.
     */
    private fun dropsPerChunk(fury: Double): Int = (1.0 + fury * FURY_DRIVES).toInt().coerceAtLeast(1)

    /** This Age's own sea material, or null where it has no sea to raise. */
    private fun ServerLevel.seaBlock(): BlockState? {
        val generator = chunkSource.generator as? AgeChunkGenerator ?: return null
        if (generator.seaFill.surfaceY == null) return null
        return generator.seaFill.representative
    }

    /** Where this Age's sea currently stands, or null where it has none. */
    fun ServerLevel.seaSurfaceY(): Int? =
        (chunkSource.generator as? AgeChunkGenerator)?.seaFill?.surfaceY

    /** An Age with nothing drowning it stands at what it was written with. */
    private const val NOT_DROWNING = 0

    /** Sixteen, and named because a chunk's own offsets are what the sweep walks. */
    private const val CHUNK_WIDTH = 16

    /**
     * How many chunks are *asked* where their sea is per tick, and how many are swept.
     *
     * Only the sweeps are rationed. Finding out *whether* a chunk is behind is [mightBeBehind]'s sixteen
     * reads and is asked of everything in view, because a budget spent on that question is a budget the
     * near chunks eat before the far ones are ever reached — see the note there.
     *
     * At a view distance of twelve the visible region is some six hundred chunks, so a step takes about two
     * seconds to travel the whole of it. **That duration IS the animation** and is the number to walk
     * against — too fast and the layer pops, too slow and the far water visibly lags the near.
     */
    private const val SWEEPS_A_TICK = 16

    /** One column in sixteen, which is what [mightBeBehind] can afford to ask of everything in view. */
    private const val PROBE_STRIDE = 4

    /**
     * How far under a chunk's own water a sweep starts, so that a spill already running into dry ground is
     * taken in rather than left for the next step to find.
     */
    private const val SPILL_REACH = 6

    private const val FURY_DRIVES = 3.0
}
