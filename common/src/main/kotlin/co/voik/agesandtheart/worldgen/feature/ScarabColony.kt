package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.age.reward.ScarabHabitat
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.Scarab
import co.voik.agesandtheart.content.ScarabNestBlockEntity
import co.voik.agesandtheart.content.ScarabPillarBlockEntity
import co.voik.agesandtheart.math.mix64
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.tags.BiomeTags
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * **A colony the Age already has** (design §7.1.2, Jonah 2026-09-30): the natural habitat, found rather than
 * waited for. The hard part of a colony is writing an Age that would hold one; once written, finding one in it
 * should be easy, so about [ONE_PATCH_IN] mud patches under the jungle with sand in reach has one.
 *
 * A colony is [FEWEST_PILLARS] to [MOST_PILLARS] pillars on the patch, spaced as a scarab spaces its claims,
 * some finished and some still rising, each with its chamber, and **each nest's owner asleep inside it** as
 * data — so nothing is spawned here, and the colony comes out at the first daylight after the chunk loads.
 *
 * **Once per pit**, however many chunks it spans: the pit is walked from wherever a chunk's scan meets its
 * mud, and only the chunk holding its anchor — its lowest column by x, then z — rolls for it, off the anchor's
 * own position, so the same pit always rolls the same way, and claims across the whole of it. Laid only in an Age a colony would live in, which
 * `ScarabHabitat.colonyLayer` decides.
 */
object ScarabColony : Feature {

    val CODEC: MapCodec<ScarabColony> = MapCodec.unit { ScarabColony }

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean {
        val chunkX = origin.x shr CHUNK_SHIFT
        val chunkZ = origin.z shr CHUNK_SHIFT
        val start = firstPitIn(level, chunkX, chunkZ) ?: return false
        val patch = patchFrom(level, start, chunkX, chunkZ)
        val anchor = patch.minWith(compareBy<BlockPos>({ it.x }, { it.z }))
        if (anchor.x shr CHUNK_SHIFT != chunkX || anchor.z shr CHUNK_SHIFT != chunkZ) return false
        val rolls = RandomSource.create(mix64(level.seed xor anchor.asLong()))
        if (rolls.nextInt(ONE_PATCH_IN) != 0) return false
        val mud = patch.filter { level.getBlockState(it).`is`(Blocks.MUD) }
        fun isFreeToClaim(at: BlockPos) = !besideANest(level, at) && sandNear(level, at, chunkX, chunkZ)
        val claimed = claimsOn(mud, rolls, ::isFreeToClaim)
        for (mud in claimed) raise(level, mud, rolls)
        return claimed.isNotEmpty()
    }

    /**
     * The first column of a pit a chunk's own columns hold, mud or sand, or null. Sand as well as mud, since the
     * chunk holding a pit's anchor may hold only its sand, and would otherwise never roll for it.
     */
    private fun firstPitIn(level: WorldGenLevel, chunkX: Int, chunkZ: Int): BlockPos? {
        for (x in 0..<CHUNK_WIDTH) {
            for (z in 0..<CHUNK_WIDTH) {
                pitAt(level, (chunkX shl CHUNK_SHIFT) + x, (chunkZ shl CHUNK_SHIFT) + z)?.let { return it }
            }
        }
        return null
    }

    /**
     * **The whole pit [start] lies in** — every column of it, mud and sand alike, side by side. A mud-and-sand
     * pit lays its two in specks, so the mud alone is a scatter of patches, each of which would roll for a
     * colony of its own. Followed as far as the region generating this chunk may read and no further.
     */
    private fun patchFrom(level: WorldGenLevel, start: BlockPos, chunkX: Int, chunkZ: Int): List<BlockPos> {
        val found = linkedMapOf(start.x to start.z to start)
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty() && found.size < LARGEST_PATCH) {
            val at = frontier.removeFirst()
            for ((dx, dz) in SIDES) {
                val key = (at.x + dx) to (at.z + dz)
                if (key in found || !isReadable(key.first, key.second, chunkX, chunkZ)) continue
                val pit = pitAt(level, key.first, key.second) ?: continue
                found[key] = pit
                frontier.addLast(pit)
            }
        }
        return found.values.toList()
    }

    /**
     * The ground of a pit at [x], [z] — mud or sand, under the jungle and out of the water — or null.
     *
     * **The ground under whatever grows on it**, not the top block: 26.3 grows grass and ferns on mud, so a
     * pit is carpeted by the time this runs, and a colony claims the mud under the carpet.
     */
    private fun pitAt(level: WorldGenLevel, x: Int, z: Int): BlockPos? {
        val at = groundAt(level, x, z) ?: return null
        val state = level.getBlockState(at)
        val isOfAPit = state.`is`(Blocks.MUD) || state.`is`(BlockTags.SAND)
        val isDry = level.getFluidState(at.above()).isEmpty
        return if (isOfAPit && isDry && isUnderTheJungle(level, at)) at else null
    }

    /**
     * Whether [at] lies in a jungle, asked of the biome at exactly that spot. **Not `getBiome`**, which blends
     * in its neighbours and so reads a little past the column — at the edge of what the region generating
     * a chunk may read, into a chunk it may not, which crashed generation.
     */
    private fun isUnderTheJungle(level: WorldGenLevel, at: BlockPos): Boolean =
        level.getNoiseBiome(QuartPos.fromBlock(at.x), QuartPos.fromBlock(at.y), QuartPos.fromBlock(at.z))
            .`is`(BiomeTags.IS_JUNGLE)

    /**
     * Up to a colony's worth of columns on [patch] a scarab would claim — [isFree], and a clear block between
     * every two, diagonals included, as `ScarabHabitat.freeSiteAt` asks.
     */
    private fun claimsOn(patch: List<BlockPos>, random: RandomSource, isFree: (BlockPos) -> Boolean): List<BlockPos> {
        val wanted = random.nextIntBetweenInclusive(FEWEST_PILLARS, MOST_PILLARS)
        val claimed = mutableListOf<BlockPos>()
        for (mud in patch.shuffled(java.util.Random(random.nextLong()))) {
            if (claimed.size >= wanted) break
            val isClear = claimed.none { maxOf(Math.abs(it.x - mud.x), Math.abs(it.z - mud.z)) <= CLEAR_BETWEEN }
            if (isClear && isFree(mud)) claimed += mud
        }
        return claimed
    }

    /**
     * One pillar: its foot, its courses up to its nest at least and some all the way, and the nest's owner
     * asleep in it.
     */
    private fun raise(level: WorldGenLevel, mud: BlockPos, random: RandomSource) {
        // The grass or fern the jungle grew on the mud gives way, as the column is claimed.
        level.setBlock(mud.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
        level.setBlock(mud, AgeContent.SCARAB_PILLAR_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS)
        val plan = level.getBlockEntity(mud) as? ScarabPillarBlockEntity ?: return
        plan.plan(random)
        val isFinished = random.nextFloat() < FINISHED
        val height = if (isFinished) plan.goal else random.nextIntBetweenInclusive(plan.nestAt, plan.goal - 1)
        for (course in 1..height) level.setBlock(mud.above(course), plan.courseAt(course), Block.UPDATE_CLIENTS)
        // Above the pillar, where it goes on, is left open whatever grew over it.
        level.setBlock(mud.above(height + 1), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
        val nestAt = mud.above(plan.nestAt)
        val nest = level.getBlockEntity(nestAt) as? ScarabNestBlockEntity ?: return
        val scarab = AgeContent.SCARAB.create(level.level, EntitySpawnReason.STRUCTURE) ?: return
        scarab.settleIn(nestAt)
        nest.grownWith(scarab)
    }

    /**
     * Whether a pillar already stands at or beside [mud] — a pit wider than one chunk's reach is walked a
     * little differently from each side of it, and may be rolled for twice.
     */
    private fun besideANest(level: WorldGenLevel, mud: BlockPos): Boolean =
        BlockPos.betweenClosed(mud.offset(-CLEAR_BETWEEN, 0, -CLEAR_BETWEEN), mud.offset(CLEAR_BETWEEN, 0, CLEAR_BETWEEN))
            .any { level.getBlockState(it).`is`(AgeContent.SCARAB_PILLAR_BLOCK) }

    /**
     * The topmost solid block of a column, under any plant, leaf or water on it, or null where there is none
     * near the top. **Walked down to rather than read off a height map**: the pits are dug without moving
     * the generation's height maps, so those stand at the ground the pit was dug out of.
     */
    private fun groundAt(level: WorldGenLevel, x: Int, z: Int): BlockPos? {
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z)
        val cursor = BlockPos.MutableBlockPos(x, top, z)
        repeat(DEEPEST_PIT) {
            if (cursor.y <= level.minY) return null
            val state = level.getBlockState(cursor)
            if (state.blocksMotionIgnoringLeaves()) return cursor.immutable()
            cursor.move(0, -1, 0)
        }
        return null
    }

    /** Sand a colony could carry within reach of [mud], looked for only where the region may read. */
    private fun sandNear(level: WorldGenLevel, mud: BlockPos, chunkX: Int, chunkZ: Int): Boolean {
        val reach = ScarabHabitat.SAND_REACH
        for (x in -reach..reach) {
            for (z in -reach..reach) {
                if (!isReadable(mud.x + x, mud.z + z, chunkX, chunkZ)) continue
                val ground = groundAt(level, mud.x + x, mud.z + z) ?: continue
                if (level.getBlockState(ground).`is`(BlockTags.SAND)) return true
            }
        }
        return false
    }

    /** Whether the region generating chunk [chunkX], [chunkZ] may read column [x], [z] — that chunk or one beside it. */
    private fun isReadable(x: Int, z: Int, chunkX: Int, chunkZ: Int): Boolean =
        Math.abs((x shr CHUNK_SHIFT) - chunkX) <= 1 && Math.abs((z shr CHUNK_SHIFT) - chunkZ) <= 1

    /** About one patch in four holds a colony (Jonah, 2026-09-30). */
    private const val ONE_PATCH_IN = 4

    /** A colony's pillars, as many as the patch leaves room for (Jonah, 2026-09-30). */
    private const val FEWEST_PILLARS = 4
    private const val MOST_PILLARS = 6

    /** How many pillars are already as tall as they will get; the rest are still being built. */
    private const val FINISHED = 0.5f

    /** Two claims are a clear block apart, diagonals included — a gap of one between their columns. */
    private const val CLEAR_BETWEEN = 1

    private const val LARGEST_PATCH = 256

    /** Solid ground: something a mob stands on, and not a tree's crown over it. */
    @Suppress("DEPRECATION")
    private fun net.minecraft.world.level.block.state.BlockState.blocksMotionIgnoringLeaves(): Boolean =
        isSolid && !`is`(BlockTags.LEAVES)

    /** How far under the height map the ground of a pit may lie: the deepest pit, and a tree's crown over it. */
    private const val DEEPEST_PIT = 24

    private const val CHUNK_SHIFT = 4
    private const val CHUNK_WIDTH = 16

    private val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
