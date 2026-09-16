package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.AgeSavedData
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
 * **It rises by a known amount**, which settles the fairness problem before it arises. A sea climbing
 * without a known end is the punishment register at its purest — you build at seventy, come back three
 * sessions later, and the water is at seventy-two. So an Age opens at the sea its book names and climbs
 * [RISES_BY] blocks above it: the end is as knowable as the start, and how far the water will come is one
 * number rather than a horizon.
 *
 * **Written first, risen after.** An Age nothing has begun to drown generates at exactly the sea it was
 * written with, which is what the rest of it is built against: standing the sea short of the written one
 * to climb back to it left vanilla's ocean monuments, and its ocean decoration generally, at a level the
 * water had walked away from.
 *
 * **It resolves.** Once the rise is complete the phenomenon is over and what is left is a drowned Age — a
 * standing condition rather than a permanent tax.
 *
 * **Three parts, and only the first of them is stored.**
 *
 * - **The sea's own level**, which is the counter: [AgeSavedData.presenceIn] ticks, turned into blocks by
 *   [risenAt], handed to the generator by [stand]. This is §5.4's one licensed number, and what buys it
 *   is that the sea surface has to be *globally coherent* — a chunk generated three thousand blocks out
 *   must flood to the same line as the one under your feet, and no sampler can promise that.
 * - **The rise made real**, [raise], which is the block work. New chunks come out of the generator already
 *   at the right height; everything made earlier is brought up to it a layer at a time, at one position per
 *   column, over every chunk anybody can see.
 * - **The pooling**, [pool], which needs no number at all — the water is the state. It is what keeps a
 *   ceiling from being an immunity: any writer who reads their own book knows a safe altitude, and rain
 *   that stands where it falls reaches them there. **PARKED 2026-09-13** (Jonah) while the rise is being
 *   refined — it is whole and unreferenced, and `Happenings` says where the call goes back.
 */
object Deluge {

    /**
     * How far above its written level a drowning Age's sea climbs.
     *
     * High enough that the shape of play changes as it rises — shoreline, footpaths and cave mouths go
     * under within the arc rather than all at the end — and low enough that the Age is recognisably the one
     * that was written throughout. **A default until the index buys it**: how far a drowning Age is carried
     * is instability's to say, and this is the figure that says it until then.
     */
    const val RISES_BY = 24

    /**
     * How long somebody has to be in the Age for the sea to gain one block, in ticks.
     *
     * **Flat rather than accelerating**, for the reason the ceiling exists: a writer should be able to say
     * how long they have. At this figure the whole of [RISES_BY] is about two hours of *being in the Age* —
     * not of the world existing, and not of the server running. UNWALKED.
     */
    const val TICKS_PER_BLOCK = 6000L

    /** How far above its written level this Age's sea stands, after [ticks] of somebody being in it. */
    fun risenAt(ticks: Long): Int =
        (ticks / TICKS_PER_BLOCK).coerceIn(0L, RISES_BY.toLong()).toInt()

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
        generator.standAbove(if (drowning) risenAt(ticks) else NOT_DROWNING)
    }

    /**
     * Bring the sea up — **and the whole of it is one position per column** (Jonah's rule, 2026-09-12).
     *
     * At every block of the sea's own fluid that is *outside*:
     *
     * - a **flowing** block becomes a source, and
     * - a **source** with room over it grows a source into that room.
     *
     * Neither ever reaches above where the sea stands, which is what keeps a waterfall on a hillside a
     * waterfall and a puddle on a roof a puddle.
     *
     * **"Outside" means it is the top of its column, and that is the realisation the second attempt turned
     * on.** A block with nothing above it in its column *is* the block the heightmap names — so there is
     * exactly one candidate per column, never a range of levels to walk. The version before this swept
     * every level under the sea in every chunk and needed a cheap pre-probe to afford it; this reads one
     * height and at most one block state per column and needs no probe at all, because there is nothing
     * left to make cheaper.
     *
     * **A pond thirty blocks down is reached by the same rule as the open ocean**, which is what the
     * version before this got wrong. It skipped any chunk whose *highest* water already stood at the line,
     * so a low pool in a coastal chunk was never touched while the same pool inland filled. There is no
     * "is this chunk behind" question any more — every column is asked, every pass, and the ones with
     * nothing to do cost a heightmap read.
     *
     * **A column comes all the way up on the pass that reaches it.** The sea gains a block every
     * [TICKS_PER_BLOCK], so ordinarily that is the one block the column is behind; what it buys is the
     * chunk nobody has been near, which arrives at the line in one visit rather than over as many passes as
     * it is behind. A block a pass had the water climbing in strips — the near columns a block, then the
     * ring beyond them a block — where a flood is a surface arriving.
     */
    fun raise(level: ServerLevel) {
        val sea = level.seaBlock() ?: return
        val standing = level.seaSurfaceY() ?: return
        val inView = inViewNearestFirst(level)
        if (inView.isEmpty()) return
        // **Where a pass starts is derived from the clock rather than remembered**, so nothing has to hold a
        // cursor. The list is sorted near to far, so each cycle through it sweeps outward from the player —
        // which is the animation, and it repeats without anything scheduling it.
        val from = ((level.gameTime * CHUNKS_A_TICK) % inView.size).toInt()
        for (step in 0..<CHUNKS_A_TICK) {
            val packed = inView[(from + step) % inView.size]
            val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: continue
            raiseIn(level, chunk, standing, sea)
        }
    }

    /** One chunk: the two rules, at the top of each of its two hundred and fifty-six columns. */
    private fun raiseIn(level: ServerLevel, chunk: LevelChunk, standing: Int, sea: BlockState) {
        val seaFluid = sea.fluidState.type
        // **Both halves of the fluid count**, which is the whole of the flowing rule: a spill is
        // `flowing_water` and its `FluidState` is not of the source's type.
        val flowingSea = (seaFluid as? FlowingFluid)?.flowing ?: seaFluid
        val cursor = BlockPos.MutableBlockPos()
        val originX = chunk.pos.minBlockX
        val originZ = chunk.pos.minBlockZ
        for (offsetX in 0..<CHUNK_WIDTH) {
            for (offsetZ in 0..<CHUNK_WIDTH) {
                // `getHeight` answers the first empty space, so the top of the column is one under it.
                val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, offsetX, offsetZ) - 1
                if (top > standing) continue
                val x = originX + offsetX
                val z = originZ + offsetZ
                cursor.set(x, top, z)
                val here = chunk.getBlockState(cursor).fluidState
                if (here.type != seaFluid && here.type != flowingSea) continue
                if (!here.isSource) {
                    // Immutable: `setBlockAndUpdate` hands the position to neighbour updates, and a cursor
                    // that moves under them is the classic way to poison a tick queue.
                    level.setBlockAndUpdate(BlockPos(x, top, z), sea)
                    continue
                }
                // Up to the line in one visit, stopping under whatever roofs the column: every block of it
                // passes the same test the one below it did, so a sealed room stays dry however far the sea
                // outside it has come.
                var risen = top
                while (risen < standing) {
                    cursor.set(x, risen + 1, z)
                    if (!chunk.getBlockState(cursor).canBeReplaced(seaFluid)) break
                    level.setBlockAndUpdate(BlockPos(x, risen + 1, z), sea)
                    risen++
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
        val standing = Sampling.watchers(level).map { it.chunkPosition() }
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
    private fun ServerLevel.seaSurfaceY(): Int? =
        (chunkSource.generator as? AgeChunkGenerator)?.seaFill?.surfaceY

    /** An Age with nothing drowning it stands at what it was written with. */
    private const val NOT_DROWNING = 0

    /** Sixteen, and named because a chunk's own offsets are what the sweep walks. */
    private const val CHUNK_WIDTH = 16

    /**
     * How many chunks a tick gets a pass over.
     *
     * **This is the animation and it is the number to walk against.** At a view distance of twelve the
     * visible region is some six hundred chunks, so thirty-two a tick cycles the whole of it in about a
     * second — which is how long a layer takes to travel from under your feet out to the horizon. Too fast
     * and the layer pops; too slow and the far water visibly lags the near.
     *
     * **Nothing is rationed but this.** A pass costs one heightmap read per column and at most one block
     * state, so there is no cheaper question to ask first and no budget for the near chunks to eat before
     * the far ones are reached — which is the trap the two versions before this both fell into, at two
     * hundred blocks and then at four chunks.
     */
    private const val CHUNKS_A_TICK = 32

    private const val FURY_DRIVES = 3.0
}
