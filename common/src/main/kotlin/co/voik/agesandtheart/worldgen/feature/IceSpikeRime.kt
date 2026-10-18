package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * An ice spike that grows a small cluster of rime crystals round its top as it is raised, if it is one of the tall
 * ones, so a frozen Age with no high ground still has a climb to them, and one with cliffs ([RimeCrystal]) has more.
 *
 * Wraps vanilla's spike rather than looking for one afterwards: the spike stands on the column it was placed at,
 * so its top is found by walking down that column, where a scan of the finished chunk cannot tell a spike from an
 * iceberg or a frozen peak.
 */
data class IceSpikeRime(val spike: Holder<Feature>) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        if (!spike.value().place(level, generator, random, origin)) return false
        val top = topOf(level, origin) ?: return true
        val isTall = top.y - origin.y >= TALL_SPIKE * resizedBy
        if (isTall) growAround(level, top, random)
        return true
    }

    /** How much bigger than vanilla's the spike is, where a book resized it whole. */
    private val resizedBy: Double get() = (spike.value() as? ScaledFeature)?.scale ?: 1.0

    /** The highest block on [origin]'s column within reach of a spike, or null where nothing stands over it. */
    private fun topOf(level: WorldGenLevel, origin: BlockPos): BlockPos? =
        (origin.y + (TALLEST_SPIKE * resizedBy).toInt() downTo origin.y + 1)
            .map { BlockPos(origin.x, it, origin.z) }
            .firstOrNull { !level.getBlockState(it).isAir }

    /**
     * A few crystals on the spike within [TOP_BAND] of [top] — on its sides, on it and under it — fewer than a cliff
     * face grows, so high ground still pays best.
     */
    private fun growAround(level: WorldGenLevel, top: BlockPos, random: RandomSource) {
        val spikeBlock = level.getBlockState(top).block
        val open = mutableListOf<RimeCrystal.Standing>()
        for (y in top.y - TOP_BAND + 1..top.y) {
            for (x in top.x - REACH..top.x + REACH) for (z in top.z - REACH..top.z + REACH) {
                val ice = BlockPos(x, y, z)
                if (!level.getBlockState(ice).`is`(spikeBlock)) continue
                for (way in Direction.entries) {
                    val beside = ice.relative(way)
                    val isNewOpenAir = level.getBlockState(beside).isAir && open.none { it.at == beside }
                    if (isNewOpenAir) open += RimeCrystal.Standing(beside, way)
                }
            }
        }
        repeat(FEWEST + random.nextInt(MOST - FEWEST + 1)) {
            if (open.isEmpty()) return
            RimeCrystal.grow(level, open.removeAt(random.nextInt(open.size)))
        }
    }

    companion object {
        val CODEC: MapCodec<IceSpikeRime> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(Feature.CODEC.fieldOf("spike").forGetter(IceSpikeRime::spike)).apply(instance, ::IceSpikeRime)
        }

        /** Above the tallest spike vanilla raises. */
        private const val TALLEST_SPIKE = 64

        /**
         * How far over where it was placed a spike's top must stand to be a tall one. Vanilla raises a short spike
         * at most thirteen and one in sixty at least seventeen.
         */
        private const val TALL_SPIKE = 16

        /** How far down from its top a spike's crystals grow, and how far out from its centre. */
        private const val TOP_BAND = 4
        private const val REACH = 2

        private const val FEWEST = 1
        private const val MOST = 3
    }
}
