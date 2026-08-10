package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.worldgen.fissure.Crack
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.WorldgenRandom

/**
 * An Age coming apart at the bottom (design §5.3) — **tears in the world's floor that go on opening**.
 *
 * The image is §5.3's and the source's: a fissure running the whole height of the world, so it is a shaft
 * down into a starfield seen from the air. What falls in is not killed but **put back in the overworld**,
 * which `StarFissureBlock` already does — the Age is lost, the inventory need not be, and that is what
 * makes the register affordable in goodwill.
 *
 * **A tear is a crack, not a circle.** It borrows [Crack] from the star fissure structure, so a collapse
 * tear is the same object a player already knows: about thirty blocks long, a few wide, running at any
 * angle, its centreline wandering so the sides are not mirrors, pinched to nothing at both ends. A radius
 * around a point reads as a hole somebody bored; this reads as something torn.
 *
 * **The blocks are the state, and nothing else is** (design §5.4, taken literally). A tear grows because
 * [co.voik.agesandtheart.worldgen.fissure.CollapsingFissureBlock] random-ticks and takes the column beside
 * it — the way grass, fire and sculk spread — so there is no frontier to track, no clock to derive from,
 * and nothing to reconcile when a chunk unloads. Generation cuts the first crack; after that the world
 * keeps its own score.
 */
object Collapse {

    /**
     * Cut whatever tears pass through this chunk — generation's one bulk pass.
     *
     * Only ever at generation, because a column is hundreds of writes and this is the one moment the chunk
     * is being written anyway. Afterwards a tear only ever takes **one column at a time** ([takeColumnBeside]),
     * which is what keeps a shaft opening under an ocean from being ten thousand fluid updates in a tick.
     */
    fun carveInto(level: LevelAccessor, chunk: ChunkAccess, worldSeed: Long, tears: Int) {
        if (tears <= NONE) return
        val here = chunk.pos
        val cracks = cracksNear(here, worldSeed, tears)
        if (cracks.isEmpty()) return
        val cursor = BlockPos.MutableBlockPos()
        for (x in here.minBlockX..here.maxBlockX) {
            for (z in here.minBlockZ..here.maxBlockZ) {
                if (cracks.none { (at, crack) -> crack.reaches(x - at.first, z - at.second) }) continue
                openColumn(chunk, cursor, x, z, level.minY)
            }
        }
    }

    /**
     * One column, floor to daylight: the tear at the bottom and nothing above it.
     *
     * The fissure **replaces bedrock**, which is the point — a world whose floor has given way is a
     * different thing from a world with a deep hole in it. It is a *band* rather than a filled column
     * because that is what the structure already does and what the starfield reads best as, and everything
     * over it is cleared to the surface so the tear is visible from the air.
     */
    private fun openColumn(
        chunk: ChunkAccess,
        cursor: BlockPos.MutableBlockPos,
        x: Int,
        z: Int,
        floor: Int,
    ) {
        val surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x and IN_CHUNK, z and IN_CHUNK)
        val lowest = floor + KEPT_UNDERFOOT
        for (y in lowest..minOf(lowest + DEEP, surface)) {
            cursor.set(x, y, z)
            // Through the chunk rather than the level: a column is hundreds of writes and a chunk still
            // being built has no use for the neighbour and lighting bookkeeping `setBlock` carries.
            chunk.setBlockState(cursor, TEAR, Block.UPDATE_NONE)
        }
        for (y in lowest + DEEP + 1..surface) {
            cursor.set(x, y, z)
            if (chunk.getBlockState(cursor).isAir) continue
            chunk.setBlockState(cursor, AIR, Block.UPDATE_NONE)
        }
    }

    /**
     * Take the column at [at] into the tear — what a spreading fissure does when it ticks.
     *
     * One column, and never more: this runs at whatever `randomTickSpeed` is, from every block on the
     * frontier at once, so the aggregate is fast while any single event stays small. Declines a column that
     * is already ours, and declines to reach into a chunk that is not loaded — a tear should widen where
     * somebody is, not quietly load the world outward.
     */
    fun takeColumnBeside(level: net.minecraft.server.level.ServerLevel, at: BlockPos) {
        if (!level.isLoaded(at)) return
        if (level.getBlockState(at).`is`(AgeContent.COLLAPSING_FISSURE_BLOCK)) return
        val lowest = level.minY + KEPT_UNDERFOOT
        val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z)
        val cursor = BlockPos.MutableBlockPos()
        for (y in lowest..minOf(lowest + DEEP, surface)) {
            cursor.set(at.x, y, at.z)
            level.setBlock(cursor, TEAR, Block.UPDATE_ALL)
        }
        for (y in lowest + DEEP + 1..surface) {
            cursor.set(at.x, y, at.z)
            if (level.getBlockState(cursor).isAir) continue
            level.setBlock(cursor, AIR, Block.UPDATE_ALL)
        }
    }

    /** Every tear whose reach could touch this chunk, with where it is centred. */
    private fun cracksNear(here: ChunkPos, worldSeed: Long, tears: Int): List<Pair<Pair<Int, Int>, Crack>> {
        val found = mutableListOf<Pair<Pair<Int, Int>, Crack>>()
        val cellX = Math.floorDiv(here.minBlockX, CELL_BLOCKS)
        val cellZ = Math.floorDiv(here.minBlockZ, CELL_BLOCKS)
        for (aroundX in cellX - REACHES..cellX + REACHES) {
            for (aroundZ in cellZ - REACHES..cellZ + REACHES) {
                for (which in 0..<tears) {
                    val random = WorldgenRandom(LegacyRandomSource(0L))
                    random.setLargeFeatureSeed(worldSeed xor COLLAPSE_SALT xor which.toLong(), aroundX, aroundZ)
                    val at = aroundX * CELL_BLOCKS + random.nextInt(CELL_BLOCKS) to
                        aroundZ * CELL_BLOCKS + random.nextInt(CELL_BLOCKS)
                    found.add(at to Crack.of(random.nextLong()))
                }
            }
        }
        return found
    }

    /** Where the nearest tear to `(x, z)` was cut — so a walk can be told where to go. */
    fun nearestOriginTo(worldSeed: Long, x: Int, z: Int, tears: Int): Pair<Int, Int> {
        val here = ChunkPos(x shr CHUNK_BITS, z shr CHUNK_BITS)
        return cracksNear(here, worldSeed, tears.coerceAtLeast(1))
            .map { it.first }
            .minByOrNull { (originX, originZ) ->
                val awayX = (x - originX).toDouble()
                val awayZ = (z - originZ).toDouble()
                awayX * awayX + awayZ * awayZ
            } ?: (x to z)
    }

    /**
     * How many tears a cell holds at each step of [co.voik.agesandtheart.age.Manifestation.COLLAPSE].
     *
     * **The first step is already unmistakable**, which is the ruling (Jonah, 2026-08-09): serious
     * consequences have to be readable the moment you link in, so even the mildest collapse puts a
     * structure-sized tear inside a sightline. The steps above it crowd them.
     */
    fun tearsPerCellAt(steps: Int): Int = steps.coerceAtLeast(0)

    /** An Age that is holding together, which is nearly all of them. */
    const val NONE = 0

    /**
     * How far apart the tears are, in blocks — one to a cell of this side, per step bought.
     *
     * **One to a sightline.** A tear is thirty-odd blocks long, so a cell of this size means there is
     * always one in view and usually more than one: the register's whole job is to be unmistakable.
     */
    private const val CELL_BLOCKS = 96

    /** How deep the tear itself runs before it gives way to open shaft — the structure's own figure. */
    private const val DEEP = 24

    /**
     * How much of the world's own floor the tear does **not** take, in layers.
     *
     * One, and it is load-bearing rather than tidy (Jonah, 2026-08-09, walked: "it is possible to fall out
     * of the world and into the void and die without getting teleported"). A star fissure has no collision
     * — falling *through* it is how you use it — so it only ever worked because there was rock underneath
     * to stop you while the portal's beat ran. Taking the last layer as well left the way out with nothing
     * under it, and anything that fell in went past the bottom of the world.
     *
     * Nothing is given up visually: the layer is under twenty-four blocks of unlit starfield, so what a
     * player sees is still a floor that has given way.
     */
    private const val KEPT_UNDERFOOT = 1

    /** How many cells either way can reach into this chunk, given a crack's length and its wander. */
    private const val REACHES = 1

    /** So where an Age fails is decorrelated from everything else its seed drives. */
    private const val COLLAPSE_SALT = 0x0C0_11AB5EL

    private const val IN_CHUNK = 15
    private const val CHUNK_BITS = 4

    private val TEAR = AgeContent.COLLAPSING_FISSURE_BLOCK.defaultBlockState()
    private val AIR = Blocks.AIR.defaultBlockState()
}
