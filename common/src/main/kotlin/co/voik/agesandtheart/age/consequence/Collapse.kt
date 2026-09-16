package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.fissure.Crack
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
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
 * [co.voik.agesandtheart.worldgen.fissure.CollapsingFissureBlock] books its own next turn and takes the
 * column beside it, so there is no frontier to track and nothing to reconcile when a chunk unloads —
 * vanilla persists a scheduled tick with the chunk that carries it. Generation cuts the first crack; after
 * that the world keeps its own score.
 *
 * **The rate is the Age's rather than the server's**, which is the one thing this does not borrow from
 * grass: `randomTickSpeed` is a single number for every world at once, so an Age bought all the way to the
 * floor of coherence came apart at exactly the pace of one barely over the threshold. See [nextTurnIn].
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
        val opened = mutableListOf<BlockPos>()
        val floor = level.minY
        for (x in here.minBlockX..here.maxBlockX) {
            for (z in here.minBlockZ..here.maxBlockZ) {
                if (cracks.none { (at, crack) -> crack.reaches(x - at.first, z - at.second) }) continue
                openColumn(chunk, cursor, x, z, floor)
                opened += BlockPos(x, topOfTheTear(chunk, x, z, floor), z)
            }
        }
        bookTheFirstTurn(chunk, opened)
    }

    /**
     * Ask for a turn for every column this chunk just cut, which nothing else will do for it.
     *
     * **A block written at generation gets no placement event.** `onPlace` is what books a column the tear
     * later spreads into, and it never fires for one the generator wrote — so without this a tear cut at
     * generation sat there for ever and only the columns it had already taken could ever take another. The
     * lava tubes had the identical hole at their own entrance.
     *
     * **Marked for post-processing rather than scheduled outright**, which is the idiom the chunk fill
     * already uses for a perched fluid: a `ScheduledTick` wants an absolute game time and generation has
     * no business knowing one, where a marked position is given its first tick on load and lands in
     * `onPlace` — the same door every other tear column comes through.
     */
    private fun bookTheFirstTurn(chunk: ChunkAccess, opened: List<BlockPos>) {
        val cursor = BlockPos.MutableBlockPos()
        for (at in opened) chunk.markPosForPostprocessing(cursor.set(at))
    }

    /** The topmost block of the band this column just had cut, which is the only one that spreads. */
    private fun topOfTheTear(chunk: ChunkAccess, x: Int, z: Int, floor: Int): Int {
        val surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x and IN_CHUNK, z and IN_CHUNK)
        return minOf(floor + KEPT_UNDERFOOT, surface)
    }

    /**
     * One column, floor to daylight: the tear at the bottom and nothing above it.
     *
     * The fissure **replaces bedrock**, which is the point — a world whose floor has given way is a
     * different thing from a world with a deep hole in it. **One layer of it**, because that is all a tear
     * has ever needed to be: `StarFissureFall` takes whoever steps in from the moment the ground under it
     * stops holding them. Everything over it is cleared to the surface so the tear is visible from the air.
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
        if (lowest <= surface) {
            cursor.set(x, lowest, z)
            // Through the chunk rather than the level: a chunk still being built has no use for the
            // neighbour and lighting bookkeeping `setBlock` carries.
            chunk.setBlockState(cursor, TEAR, Block.UPDATE_NONE)
            // **And the block entity by hand, which is the whole of why half a tear was invisible.**
            // `ProtoChunk.setBlockState` sets the state, the lighting and the heightmaps and stops — it
            // never makes a block entity, where `LevelChunk.setBlockState` does. A fissure is drawn by a
            // block entity *renderer*, so every column written while the chunk was still being generated
            // had nothing to draw it and you looked straight through the tear to the rock at the bottom,
            // while the columns the tear later spread into through the level rendered perfectly (Jonah,
            // 2026-09-09, walked).
            standTheStarsUp(chunk, cursor)
        }
        for (y in lowest + 1..surface) {
            cursor.set(x, y, z)
            if (chunk.getBlockState(cursor).isAir) continue
            chunk.setBlockState(cursor, AIR, Block.UPDATE_NONE)
        }
    }

    /**
     * Take the column at [at] into the tear — what a spreading fissure does when it ticks.
     *
     * One column, and never more: every block on the frontier books its own turn, so the aggregate is fast
     * while any single event stays small. Declines a column that is already ours, and declines to reach
     * into a chunk that is not loaded — a tear should widen where somebody is, not quietly load the world
     * outward.
     */
    fun takeColumnBeside(level: ServerLevel, at: BlockPos) {
        if (!level.isLoaded(at)) return
        if (level.getBlockState(at).`is`(AgeContent.COLLAPSING_FISSURE_BLOCK)) return
        val lowest = level.minY + KEPT_UNDERFOOT
        val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z)
        val cursor = BlockPos.MutableBlockPos()
        if (lowest <= surface) {
            cursor.set(at.x, lowest, at.z)
            level.setBlock(cursor, TEAR, Block.UPDATE_ALL)
        }
        for (y in lowest + 1..surface) {
            cursor.set(at.x, y, at.z)
            if (level.getBlockState(cursor).isAir) continue
            level.setBlock(cursor, AIR, Block.UPDATE_ALL)
        }
    }

    /**
     * The block entity a fissure is drawn from, made where the chunk will not make one itself.
     *
     * A copy of the position, not the cursor: a block entity keeps the position it was handed, and this
     * one is walked down a whole column.
     */
    private fun standTheStarsUp(chunk: ChunkAccess, at: BlockPos.MutableBlockPos) {
        val block = TEAR.block as? EntityBlock ?: return
        block.newBlockEntity(at.immutable(), TEAR)?.let(chunk::setBlockEntity)
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

    /**
     * How much of the world's own floor the tear does **not** take, in layers.
     *
     * One, and it is what the tear stands on rather than what saves anybody: `StarFissureFall` carries a
     * player through whatever is under a tear, so the old hazard this guarded against — falling out of the
     * bottom of the world before the way out could fire — cannot happen any more. What the layer still
     * buys is that the world has a floor at all where a tear has taken its skin.
     */
    private const val KEPT_UNDERFOOT = 1

    /** How many cells either way can reach into this chunk, given a crack's length and its wander. */
    private const val REACHES = 1

    /** So where an Age fails is decorrelated from everything else its seed drives. */
    private const val COLLAPSE_SALT = 0x0C0_11AB5EL

    /**
     * Book this block's next turn at a drawn delay, or leave it unbooked where one is already waiting.
     *
     * **Drawn rather than fixed**, because a tear whose columns go at an even beat reads as machinery
     * rather than as ground giving way — the same argument the hadalfish's circle wins on. The draw is
     * uniform over a window whose *mean* is what carries the rate.
     *
     * **The mean falls as the Age is more torn**, which is the whole reason this exists: vanilla's
     * `randomTickSpeed` is one number for the entire server, so an Age bought to the floor of coherence
     * spread at exactly the pace of one barely over the threshold. Read off the generator's own
     * [Consequence] rather than the saved recipe, because that is the number generation already cut the
     * first tears from and the two must not be free to disagree.
     *
     * Vanilla persists scheduled ticks with the chunk, so nothing here is state of ours — §5.4 holds.
     */
    fun keepTearing(level: ServerLevel, at: BlockPos) {
        val block = level.getBlockState(at).block
        if (level.blockTicks.hasScheduledTick(at, block)) return
        level.scheduleTick(at, block, nextTurnIn(level, level.random))
    }

    /** The same draw without a level to read it from, so the cadence can be checked rather than walked. */
    fun nextTurnIn(tears: Int, random: RandomSource): Int {
        val torn = tears.coerceIn(NONE, TORN_ENOUGH).toDouble() / TORN_ENOUGH
        val mean = SLOWEST_TURN - (SLOWEST_TURN - QUICKEST_TURN) * torn
        return (mean * (ONE_HALF + random.nextDouble())).toInt().coerceAtLeast(QUICKEST_TURN)
    }

    private fun nextTurnIn(level: ServerLevel, random: RandomSource): Int =
        nextTurnIn((level.chunkSource.generator as? AgeChunkGenerator)?.consequence?.collapseTears ?: NONE, random)

    /**
     * How long between a column going and the next one beside it, at the ends of the range.
     *
     * The slow end is about a minute, which is a floor that gives way while you are standing on it rather
     * than while you are away; the quick end is a few seconds, which is an Age visibly coming apart. A
     * tear's edge is many blocks long and each books its own turn, so the *tear* widens far faster than
     * either figure — these are the cadence of one column, not of the register.
     */
    private const val SLOWEST_TURN = 1200
    private const val QUICKEST_TURN = 60

    /** The tearing this treats as all the way gone, so the mean has somewhere to bottom out. */
    private const val TORN_ENOUGH = 6

    /** The drawn window is the mean either side of itself, which is uneven without being unrecognisable. */
    private const val ONE_HALF = 0.5

    private const val IN_CHUNK = 15
    private const val CHUNK_BITS = 4

    /**
     * **`by lazy`, so the cadence can be checked without a world.** `AgeContent` cannot initialise offline
     * at all, and an eager reference here made merely *naming* this object a bootstrap — which put
     * [nextTurnIn], which is arithmetic, out of reach of an ordinary spec.
     */
    private val TEAR by lazy { AgeContent.COLLAPSING_FISSURE_BLOCK.defaultBlockState() }
    private val AIR by lazy { Blocks.AIR.defaultBlockState() }
}
