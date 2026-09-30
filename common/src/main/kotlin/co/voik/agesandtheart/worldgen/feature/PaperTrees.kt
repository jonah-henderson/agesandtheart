package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.PaperTreeGrowth
import co.voik.agesandtheart.content.PaperTreeHealth
import co.voik.agesandtheart.age.phenomena.Tide
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * One paper tree, rooted in the ground under [place]'s origin — what `/place feature agesandtheart:paper_tree`
 * grows, anywhere, whatever the Age.
 *
 * **Shipped as a configured feature and never as a placed one**, as the deep-sea vent is: every placed
 * feature is a word a writer can say, and the tree is earned by its window ([PaperTreeGrove]), not named.
 */
object PaperTree : Feature {

    val CODEC: MapCodec<PaperTree> = MapCodec.unit { PaperTree }

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean =
        PaperTreeGrowth.grow(level, origin.below(), random.nextLong(), PaperTreeHealth.SETTLED, Block.UPDATE_ALL)
}

/**
 * A paper tree where one would grow of itself: **in the intertidal band** (design §7.1.2), its heart in the
 * ground level with the sea or a block above it and water within reach of its roots, so they are wet at high
 * tide and dry at low. Laid only in an Age whose window is met — see `PaperTreeWindow`.
 */
object PaperTreeGrove : Feature {

    val CODEC: MapCodec<PaperTreeGrove> = MapCodec.unit { PaperTreeGrove }

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean {
        val sea = Tide.seaOf(generator)?.top ?: return false
        val heart = origin.below()
        val isAtTheWaterline = heart.y == sea || heart.y == sea + 1
        if (!isAtTheWaterline || !waterWithinReach(level, heart, sea)) return false
        return PaperTreeGrowth.grow(level, heart, random.nextLong(), PaperTreeHealth.SETTLED, Block.UPDATE_CLIENTS)
    }

    /** Whether the sea's surface lies within a root's reach of [heart]. */
    private fun waterWithinReach(level: WorldGenLevel, heart: BlockPos, sea: Int): Boolean {
        for (dx in -ROOT_REACH..ROOT_REACH) {
            for (dz in -ROOT_REACH..ROOT_REACH) {
                val at = BlockPos(heart.x + dx, sea, heart.z + dz)
                if (level.getFluidState(at).`is`(FluidTags.WATER)) return true
            }
        }
        return false
    }

    /** About as far as the roots spread. */
    private const val ROOT_REACH = 3
}
