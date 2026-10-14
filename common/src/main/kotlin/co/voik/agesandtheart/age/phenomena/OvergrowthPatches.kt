package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.math.mix64
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.WorldgenRandom
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Large mounds of overgrowth laid into new ground by an Age's instability (design §5.0, tier 2): not
 * seeded blocks but a dome standing out of the ground, which the roots then spread from.
 *
 * A patch is a pure function of its centre and the world seed, and a chunk draws every patch whose centre
 * lies in it or a neighbour, writing only its own columns. So a patch crossing a chunk edge comes out whole
 * whichever of the chunks is generated first.
 */
object OvergrowthPatches {

    /** How many patch centres a chunk holds, per step bought. */
    private const val CHANCE_PER_STEP = 0.03

    /** A patch's reach from its centre, so about twenty-five blocks across. */
    private const val RADIUS = 12.5

    /** Out to this share of the radius a patch is solid; past it the edge thins out. */
    private const val SOLID_SHARE = 0.6

    /** How many blocks a mound stands above the surface at its centre. */
    private const val PEAK_HEIGHT = 9

    /** The blocks of ground a mound eats beneath where it stands, so it does not sit on loose turf. */
    private const val ROOTED_DEPTH = 1

    /** The size of a lump, in blocks. */
    private const val LUMP_SIZE = 5

    /** How much a lump stretches or pulls in the mound's reach, as shares of its radius. */
    private const val NARROWEST = 0.8
    private const val WIDEST = 1.2

    /** How much of its dome's height a column keeps at the least and the most lumpy. */
    private const val LUMP_LOW = 0.6
    private const val LUMP_HIGH = 1.25

    private const val LUMP_SALT = 0x2c1b3c6d4f7a8e91L
    private const val CENTRE_SALT = 0x0e7a6c1d5b3f9a24L
    private const val COLUMN_SALT = 0x5bd1e9955bd1e995L
    private const val COLUMN_Z_PRIME = -7046029254386353131L
    private const val CHUNK_WIDTH = 16
    private const val IN_CHUNK = 15
    private const val DOUBLE_UNIT = 1.0 / 9007199254740992.0

    /** The chance a chunk holds a patch's centre, when [steps] were bought. */
    fun chanceAt(steps: Int): Double = (steps * CHANCE_PER_STEP).coerceIn(0.0, 1.0)

    /** The patches of this Age that reach [chunk], laid into it. */
    fun layInto(level: WorldGenLevel, chunk: ChunkAccess, seed: Long, chance: Double) {
        if (chance <= 0.0) return
        for (dx in -1..1) {
            for (dz in -1..1) {
                val owner = ChunkPos(chunk.pos.x + dx, chunk.pos.z + dz)
                val centre = centreIn(owner, seed, chance) ?: continue
                lay(level, chunk, centre, seed)
            }
        }
    }

    /** Where the patch whose centre is in [owner] is centred, or null where that chunk has none. */
    fun centreIn(owner: ChunkPos, seed: Long, chance: Double): BlockPos? {
        val random = WorldgenRandom(LegacyRandomSource(seed))
        random.setLargeFeatureSeed(seed xor CENTRE_SALT, owner.x, owner.z)
        if (random.nextDouble() >= chance) return null
        return BlockPos(owner.minBlockX + random.nextInt(CHUNK_WIDTH), 0, owner.minBlockZ + random.nextInt(CHUNK_WIDTH))
    }

    /**
     * The mound's lumpiness at a column, from 0 to 1: smooth noise a few blocks across, so neighbouring columns
     * rise and fall together and the outline and the surface both wander rather than speckle.
     */
    fun lumpAt(seed: Long, x: Int, z: Int): Double {
        val cellX = Math.floorDiv(x, LUMP_SIZE)
        val cellZ = Math.floorDiv(z, LUMP_SIZE)
        val across = Math.floorMod(x, LUMP_SIZE).toDouble() / LUMP_SIZE
        val down = Math.floorMod(z, LUMP_SIZE).toDouble() / LUMP_SIZE
        val west = lerp(cornerAt(seed, cellX, cellZ), cornerAt(seed, cellX + 1, cellZ), smooth(across))
        val east = lerp(cornerAt(seed, cellX, cellZ + 1), cornerAt(seed, cellX + 1, cellZ + 1), smooth(across))
        return lerp(west, east, smooth(down))
    }

    private fun cornerAt(seed: Long, cellX: Int, cellZ: Int): Double = rollAt(seed xor LUMP_SALT, cellX, cellZ)

    private fun smooth(t: Double) = t * t * (3.0 - 2.0 * t)

    private fun lerp(from: Double, to: Double, by: Double) = from + (to - from) * by

    /**
     * How far from the centre a column counts as being, once the lumps have stretched the mound towards some
     * ways and pulled it in from others. Never more than [WIDEST] times the true distance, which is what keeps
     * a mound within a chunk of the one that holds its centre.
     */
    fun effectiveDistance(distance: Double, lump: Double): Double = distance / (NARROWEST + (WIDEST - NARROWEST) * lump)

    /** How likely a column [distance] blocks from a centre is to be eaten: surely at the core, never past the edge. */
    fun coverageAt(distance: Double): Double {
        val core = RADIUS * SOLID_SHARE
        return when {
            distance <= core -> 1.0
            distance >= RADIUS -> 0.0
            else -> 1.0 - (distance - core) / (RADIUS - core)
        }
    }

    /**
     * How many blocks a mound stands above the ground at a column: a dome by [distance], roughened by the
     * [lump] there, and never less than one.
     */
    fun heightAt(distance: Double, lump: Double): Int {
        val across = (distance / RADIUS).coerceIn(0.0, 1.0)
        val dome = 1.0 - across * across
        val rough = LUMP_LOW + (LUMP_HIGH - LUMP_LOW) * lump
        return (PEAK_HEIGHT * dome * rough).roundToInt().coerceAtLeast(1)
    }

    private fun lay(level: WorldGenLevel, chunk: ChunkAccess, centre: BlockPos, seed: Long) {
        for (x in chunk.pos.minBlockX..chunk.pos.maxBlockX) {
            for (z in chunk.pos.minBlockZ..chunk.pos.maxBlockZ) {
                val lump = lumpAt(seed, x, z)
                val distance = effectiveDistance(hypot((x - centre.x).toDouble(), (z - centre.z).toDouble()), lump)
                val coverage = coverageAt(distance)
                if (coverage <= 0.0 || rollAt(seed, x, z) >= coverage) continue
                raiseTheMound(level, chunk, x, z, heightAt(distance, lump))
            }
        }
    }

    private fun rollAt(seed: Long, x: Int, z: Int): Double =
        (mix64(seed xor COLUMN_SALT xor (x.toLong() * COLUMN_Z_PRIME) xor z.toLong()) ushr 11) * DOUBLE_UNIT

    private fun raiseTheMound(level: WorldGenLevel, chunk: ChunkAccess, x: Int, z: Int, height: Int) {
        val surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x and IN_CHUNK, z and IN_CHUNK)
        val isUnderWater = !chunk.getBlockState(BlockPos(x, surface - 1, z)).fluidState.isEmpty
        if (isUnderWater) return
        val top = minOf(surface + height, chunk.maxY)
        for (y in surface - ROOTED_DEPTH..<top) {
            val at = BlockPos(x, y, z)
            val state = chunk.getBlockState(at)
            val isOpen = state.isAir
            if (!isOpen && !Overgrowth.isEdible(level, at, state)) return
            chunk.setBlockState(at, Overgrowth.BLOCK.defaultBlockState(), Block.UPDATE_NONE)
        }
    }
}
