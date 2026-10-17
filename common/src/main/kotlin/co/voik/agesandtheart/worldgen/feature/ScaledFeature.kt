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
 * Only what it lays down is scaled: it places itself as it would at its ordinary size. What would land outside
 * the chunks worldgen may still write is left out, so a colossal one standing near a chunk's edge is cut off.
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
        fun span(along: (BlockPos) -> Int): IntRange {
            val least = floor(offsets.minOf(along) * scale).toInt()
            val most = ceil((offsets.maxOf(along) + 1) * scale).toInt() - 1
            return least..most
        }
        val cursor = BlockPos.MutableBlockPos()
        for (x in span(BlockPos::getX)) for (y in span(BlockPos::getY)) for (z in span(BlockPos::getZ)) {
            val drawn = laid[origin.offset(underneath(x), underneath(y), underneath(z))] ?: continue
            cursor.set(origin.x + x, origin.y + y, origin.z + z)
            if (draft.canWrite(cursor)) draft.real.setBlock(cursor, drawn, Block.UPDATE_CLIENTS)
        }
    }

    private fun underneath(scaled: Int): Int = floor(scaled / scale).toInt()

    companion object {
        val CODEC: MapCodec<ScaledFeature> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Feature.CODEC.fieldOf("feature").forGetter(ScaledFeature::feature),
                Codec.DOUBLE.fieldOf("scale").forGetter(ScaledFeature::scale),
            ).apply(instance, ::ScaledFeature)
        }
    }
}
