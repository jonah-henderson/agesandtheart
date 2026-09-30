package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.PaperTreeGrowth
import co.voik.agesandtheart.content.PaperTreeHealth
import co.voik.agesandtheart.content.PaperTreeShape
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
 * A paper tree where one would grow of itself: **in the intertidal band** (design §7.1.2), its heart set so
 * that **every root ends at mid tide** and water within reach of them, so they are wet at high and mid water
 * and dry at low — a tree that keeps itself, rather than one a tide drowns. Laid only in an Age whose window
 * is met — see `PaperTreeWindow`.
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
        val mid = Tide.midOf(generator) ?: return false
        val heart = origin.below()
        val rootsEndAtMidTide = heart.y - PaperTreeShape.ROOT_TIPS_UNDER_THE_HEART == mid
        if (!rootsEndAtMidTide || !waterWithinReach(level, heart, mid)) return false
        return PaperTreeGrowth.grow(level, heart, random.nextLong(), PaperTreeHealth.SETTLED, Block.UPDATE_CLIENTS)
    }

    /** Whether the sea at mid tide lies within a root's reach of [heart]. */
    private fun waterWithinReach(level: WorldGenLevel, heart: BlockPos, mid: Int): Boolean {
        for (dx in -ROOT_REACH..ROOT_REACH) {
            for (dz in -ROOT_REACH..ROOT_REACH) {
                val at = BlockPos(heart.x + dx, mid, heart.z + dz)
                if (level.getFluidState(at).`is`(FluidTags.WATER)) return true
            }
        }
        return false
    }

    /** About as far as the roots spread. */
    private const val ROOT_REACH = 3
}
