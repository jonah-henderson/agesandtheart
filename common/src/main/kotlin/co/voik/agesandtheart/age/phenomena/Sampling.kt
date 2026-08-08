package co.voik.agesandtheart.age.phenomena

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap

/**
 * How a phenomenon acts on a world it does not own: **a rule applied to a random sample of loaded space**
 * (design §5.4).
 *
 * Not our invention — it is how every environmental process Minecraft already has works. Grass spreads, ice
 * forms, fire creeps and snow settles by exactly this, none of them keeping anything between ticks, and
 * `ServerLevel.tickPrecipitation` is the snow and ice pass drawn from one position per chunk every
 * [BETWEEN_CHUNK_SAMPLES] ticks. That number is vanilla's, and it is the reference for what "cheap" costs.
 *
 * **Only the chunks somebody is standing near**, which is not an optimisation but the rule: an Age nobody is
 * in has nothing worth spending a tick on, and the check is what stops this scaling with how many Ages have
 * ever been written.
 */
object Sampling {

    /**
     * Offers [visit] a position in each loaded chunk near a player, this tick, at vanilla's own rate.
     *
     * The position is a *column* — an `(x, z)` with no meaningful height — because every caller so far wants
     * either the surface ([skyward]) or somewhere derived from it, and choosing a height here would be
     * choosing it for all of them.
     */
    fun sweep(level: ServerLevel, times: Int, visit: (LevelChunk, BlockPos) -> Unit) {
        for (player in level.players()) {
            if (player.isSpectator) continue
            val standing = player.chunkPosition()
            for (x in standing.x - CHUNKS_ABOUT..standing.x + CHUNKS_ABOUT) {
                for (z in standing.z - CHUNKS_ABOUT..standing.z + CHUNKS_ABOUT) {
                    val chunk = level.chunkSource.getChunkNow(x, z) ?: continue
                    repeat(times) {
                        if (level.random.nextInt(BETWEEN_CHUNK_SAMPLES) != 0) return@repeat
                        visit(chunk, level.getBlockRandomPos(x shl CHUNK_BITS, 0, z shl CHUNK_BITS, CHUNK_WIDTH))
                    }
                }
            }
        }
    }

    /**
     * The lowest position above the ground at [at]'s column — **and therefore the sky test**.
     *
     * `MOTION_BLOCKING` counts anything solid *or* holding fluid, so its height is by definition the first
     * empty place above everything: the block below it is the topmost thing there is, and a position at or
     * above it is one nothing covers. That single comparison is what tells an inferno which trees can catch,
     * a blizzard where its snow settles, magma rain who is under a roof, and a rising sea where its surface
     * is. **One primitive, not a trick each of them reinvents.**
     */
    fun skyward(level: LevelReader, at: BlockPos): BlockPos =
        at.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z))

    /** Whether nothing at all covers [at] — the same comparison, asked about a position rather than a column. */
    fun openToTheSky(level: LevelReader, at: BlockPos): Boolean =
        at.y >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z)

    /**
     * How often a chunk offers up a position, in ticks — **read off vanilla rather than chosen**.
     *
     * `ServerLevel.tickChunk` runs its precipitation pass on `nextInt(48) == 0`, so a chunk answers about
     * once every two and a half seconds. Matching it means a phenomenon costs what vanilla's own weather
     * costs, and that a rate written in a datapack can be reasoned about against something.
     */
    const val BETWEEN_CHUNK_SAMPLES = 48

    /**
     * How far from a player a chunk is worth sampling, in chunks.
     *
     * Short of an ordinary render distance on purpose: what happens where nobody can see it is spent for
     * nothing, and a phenomenon that acts to the edge of the loaded world scales with the server's view
     * distance rather than with the number of people in the Age.
     */
    private const val CHUNKS_ABOUT = 6

    private const val CHUNK_BITS = 4
    private const val CHUNK_WIDTH = 16
}
