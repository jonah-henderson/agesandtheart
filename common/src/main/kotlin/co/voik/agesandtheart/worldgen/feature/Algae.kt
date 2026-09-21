package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AlgaeBlock
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * The algae, sown across the surface of any sunless water it is dropped over (design §7.6).
 *
 * **A feature rather than a filter**, because the two facts it needs are not things a placement modifier
 * can ask. A lake in a chamber is nowhere near the heightmap, so nothing points at its surface: the
 * position has to be *found* by falling from wherever the scatter put it. And the surface it lands on must
 * be covered over, which vanilla can only express as a light level — and light has not been computed when
 * decoration runs.
 *
 * **So being covered is read off the heightmap instead**, which is exact at this stage and means the same
 * thing: something stands over this column, higher than the water's own face. `AlgaeBlock.canGrowAt` asks
 * the sky channel for the same fact once the world is running, and the two agree everywhere it matters —
 * an open ocean is covered by nothing, and a cavern lake by its roof.
 */
object Algae : Feature {

    /** Nothing to configure, so the codec is the object itself. Supplied lazily: an `object`'s instance
     *  field is assigned after its initialisers run, so naming it directly here would capture null. */
    val CODEC: MapCodec<Algae> = MapCodec.unit { Algae }

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean {
        val surface = waterSurfaceUnder(level, origin) ?: return false
        // Sown at whatever the hour is, so a chunk generated now matches the lake it joins rather than
        // arriving at full glow and fading to meet it.
        val sown = AgeContent.ALGAE_BLOCK.defaultBlockState()
            .setValue(AlgaeBlock.LIT, AlgaeBlock.isLitAtHour(level.level.defaultClockTime))

        var grew = 0
        for (attempt in 0..<SEEDS_PER_PATCH) {
            val across = random.nextInt(PATCH_REACH * 2 + 1) - PATCH_REACH
            val along = random.nextInt(PATCH_REACH * 2 + 1) - PATCH_REACH
            // Each seed finds the water's face for itself, so a patch follows a shelving shore rather than
            // hanging at one level across it.
            val at = waterSurfaceUnder(level, surface.offset(across, PATCH_RISE, along)) ?: continue
            level.setBlock(at, sown, UPDATE_FLAGS)
            grew++
        }
        return grew > 0
    }

    /**
     * The face of the first still water at or under [from], or null where there is none within reach.
     *
     * Still, because a waterfall has a surface at every block of its fall and algae growing down one would
     * read as a stripe painted on it. Flowing water is not a [Blocks.WATER] *source*, and asking for the
     * block rather than the fluid is what excludes it.
     */
    private fun waterSurfaceUnder(level: WorldGenLevel, from: BlockPos): BlockPos? {
        val cursor = BlockPos.MutableBlockPos().set(from)
        for (fallen in 0..<HOW_FAR_TO_FALL) {
            if (cursor.y <= level.minY) return null
            if (level.getBlockState(cursor).`is`(Blocks.WATER) && isTheFace(level, cursor)) {
                return cursor.immutable()
            }
            cursor.move(0, -1, 0)
        }
        return null
    }

    /** Whether this water block is the top one, standing on something, with rock somewhere over it. */
    private fun isTheFace(level: WorldGenLevel, at: BlockPos): Boolean {
        val nothingOnTopOfIt = level.getFluidState(at.above()).isEmpty
        val standsOnSomething = !level.getBlockState(at.below()).isAir
        return nothingOnTopOfIt && standsOnSomething && isCovered(level, at)
    }

    /**
     * Whether anything stands over this column higher than the water itself.
     *
     * **Higher than the block above it, not merely higher than it**, which is the whole of the test: a
     * heightmap counts water, so an open ocean's own face already reads as something standing there and a
     * looser comparison would call every sea on the overworld a sunless one.
     */
    private fun isCovered(level: WorldGenLevel, at: BlockPos): Boolean =
        level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, at.x, at.z) > at.y + 1

    /** How far a seed falls looking for water. Generous: the scatter drops it anywhere up the column. */
    private const val HOW_FAR_TO_FALL = 64

    /** How wide one patch is sown, and how many mats go into it before the spread takes over. */
    private const val PATCH_REACH = 6
    private const val SEEDS_PER_PATCH = 10

    /** Each seed starts a little above where the last surface was, so a rising shore is still found. */
    private const val PATCH_RISE = 2

    /** No neighbour updates: this is worldgen, and a chain of them across a chunk edge is a hang. */
    private const val UPDATE_FLAGS = 2
}
