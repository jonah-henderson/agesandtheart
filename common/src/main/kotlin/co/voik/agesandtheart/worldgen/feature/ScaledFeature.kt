package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A feature whose record says nothing about how big it is, resized **whole**: it is placed into a [FeatureDraft],
 * and the draft is then laid out [scale] times as big about the feature's origin, a block of the draft becoming a
 * cube of blocks or a cube of the draft one block.
 *
 * Only what it lays down is scaled: it places itself as it would at its ordinary size. **It grows up rather than
 * out**: no wider than a chunk, however big it was asked to be, while its height takes the whole factor. What would
 * land outside the chunks worldgen may still write is left out.
 */
data class ScaledFeature(val feature: Holder<Feature>, val scale: Double) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        val draft = FeatureDraft(level)
        if (!feature.value().place(draft.level, generator, random, origin)) return false
        layOut(draft, origin)
        return true
    }

    /**
     * Writes [draft] at [scale] about [origin]: each block of the scaled box takes the draft's block nearest under
     * it, so nothing is stretched out of order and a shrunk feature keeps its silhouette.
     */
    private fun layOut(draft: FeatureDraft, origin: BlockPos) {
        val laid = draft.laid
        if (laid.isEmpty()) return
        val offsets = laid.keys.map { it.subtract(origin) }
        fun across(along: (BlockPos) -> Int) = offsets.maxOf(along) - offsets.minOf(along) + 1
        val widest = maxOf(across(BlockPos::getX), across(BlockPos::getZ))
        val sideways = if (scale > 1.0) scale.coerceAtMost(maxOf(1.0, MOST_ACROSS.toDouble() / widest)) else scale
        fun span(along: (BlockPos) -> Int, by: Double): IntRange {
            val least = floor(offsets.minOf(along) * by).toInt()
            val most = ceil((offsets.maxOf(along) + 1) * by).toInt() - 1
            return least..most
        }
        val cursor = BlockPos.MutableBlockPos()
        for (x in span(BlockPos::getX, sideways)) for (y in span(BlockPos::getY, scale)) for (z in span(BlockPos::getZ, sideways)) {
            val drawn = laid[origin.offset(underneath(x, sideways), underneath(y, scale), underneath(z, sideways))] ?: continue
            cursor.set(origin.x + x, origin.y + y, origin.z + z)
            if (draft.canWrite(cursor)) draft.real.setBlock(cursor, drawn, Block.UPDATE_CLIENTS)
        }
    }

    private fun underneath(scaled: Int, by: Double): Int = floor(scaled / by).toInt()

    companion object {
        /** The widest a feature resized whole is laid out: one chunk. */
        private const val MOST_ACROSS = 16

        val CODEC: MapCodec<ScaledFeature> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Feature.CODEC.fieldOf("feature").forGetter(ScaledFeature::feature),
                Codec.DOUBLE.fieldOf("scale").forGetter(ScaledFeature::scale),
            ).apply(instance, ::ScaledFeature)
        }
    }
}
