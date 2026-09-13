package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
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
     * Bring ground that was generated earlier up to where the sea now stands.
     *
     * **Sampled near the players, which is the shape every other phenomenon in the set takes.** The
     * alternative — walking every loaded chunk the moment the level advances — would put a visible hitch on
     * one tick in three hundred and flood ground nobody is looking at. This creeps instead, which also
     * *reads* as a flood rather than as a waterline teleporting upward.
     *
     * **A block is only laid where the water could have reached it**: with the sea's own water below it or
     * beside it. That is the whole of why §5.2 can promise that relief is structural — a sealed box has no
     * wet neighbour, so nothing here can put water inside one, and no rule about boxes had to be written.
     */
    fun flood(level: ServerLevel, standing: Int) {
        val sea = level.seaBlock() ?: return
        Sampling.sweep(level, COLUMNS_A_CHUNK) { _, at ->
            riseAt(level, sea, at.x, at.z, standing)
        }
    }

    /**
     * One column, one block.
     *
     * Searched downward from the standing surface rather than upward from the ground: what is wanted is the
     * *lowest* dry place the sea has already reached the edge of, which is the block that would fill next
     * if this were really water. [REACHES_DOWN] bounds it, the sea never being more than a few blocks ahead
     * of the world it is filling.
     */
    private fun riseAt(level: ServerLevel, sea: BlockState, x: Int, z: Int, standing: Int) {
        val cursor = BlockPos.MutableBlockPos()
        for (y in standing downTo standing - REACHES_DOWN) {
            cursor.set(x, y, z)
            if (!level.getBlockState(cursor).canBeReplaced(Fluids.WATER)) continue
            if (!theSeaTouches(level, cursor, sea)) continue
            level.setBlockAndUpdate(cursor, sea)
            return
        }
    }

    /** Whether this Age's own sea is already under or beside [at] — the reachability the promise rests on. */
    private fun theSeaTouches(level: ServerLevel, at: BlockPos, sea: BlockState): Boolean {
        if (level.getBlockState(at.below()).`is`(sea.block)) return true
        return Direction.Plane.HORIZONTAL.any { side -> level.getBlockState(at.relative(side)).`is`(sea.block) }
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
     */
    fun pool(level: ServerLevel, fury: Double) {
        if (!level.isRaining) return
        val standing = level.seaSurfaceY() ?: return
        Sampling.sweep(level, dropsPerChunk(fury)) { _, at ->
            val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z)
            // Below the waterline is the sea's business, and laying a source down there would put water
            // inside whatever the sea has not reached yet — which is exactly the sealed room the promise
            // above depends on.
            if (top <= standing) return@sweep
            val onto = BlockPos(at.x, top, at.z)
            if (!level.getBlockState(onto).canBeReplaced(Fluids.WATER)) return@sweep
            // Sky-lit, so a roof is a roof. `canSeeSky` is the same question the blizzard's drift asks.
            if (!level.canSeeSky(onto)) return@sweep
            level.setBlockAndUpdate(onto, Blocks.WATER.defaultBlockState())
        }
    }

    /**
     * How many columns a chunk gets a drop in per tick.
     *
     * **Deliberately small, and the reason is measured elsewhere**: flowing water at storm scale is one of
     * vanilla's heavier update paths, and §5.2 names this as the sandfall's "do not spawn falling entities
     * at storm scale" lesson wearing new clothes. One source block spreads for a long time after it lands,
     * so the rate that matters is how many are *in flight*, not how many are placed. UNWALKED, and this is
     * the number to benchmark before raising.
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

    /** How far under the surface a column is searched for the next block to fill. */
    private const val REACHES_DOWN = 4

    /** Columns sampled per chunk per tick, which is what decides how fast a risen line is made real. */
    private const val COLUMNS_A_CHUNK = 6

    private const val FURY_DRIVES = 3.0
}
