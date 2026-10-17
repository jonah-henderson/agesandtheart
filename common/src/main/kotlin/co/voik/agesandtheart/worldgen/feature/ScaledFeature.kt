package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.material.FluidState
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.function.Predicate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A feature whose record says nothing about how big it is, resized **whole**: it is placed into a draft that
 * remembers every block it lays, and the draft is then laid out [scale] times as big about the feature's
 * origin, a block of the draft becoming a cube of blocks or a cube of the draft one block.
 *
 * The feature reads the world as it stands, with its own blocks over it, so it places itself as it would at its
 * ordinary size; only what it lays down is scaled. What would land outside the chunks worldgen may still write
 * is left out, so a colossal one standing near a chunk's edge is cut off there.
 */
data class ScaledFeature(val feature: Holder<Feature>, val scale: Double) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        val draft = Draft(level)
        if (!feature.value().place(draft.level, generator, random, origin)) return false
        draft.layOut(origin, scale)
        return true
    }

    /** What a feature laid, kept rather than written, and the world it reads seen through it. */
    private class Draft(private val real: WorldGenLevel) {

        private val laid = HashMap<BlockPos, BlockState>()

        val level: WorldGenLevel = Proxy.newProxyInstance(
            WorldGenLevel::class.java.classLoader,
            arrayOf(WorldGenLevel::class.java),
        ) { _, method, arguments -> answer(method, arguments ?: emptyArray()) } as WorldGenLevel

        private fun stateAt(at: BlockPos): BlockState = laid[at] ?: real.getBlockState(at)

        @Suppress("UNCHECKED_CAST")
        private fun answer(method: Method, arguments: Array<Any?>): Any? = when (method.name) {
            "setBlock" -> true.also { laid[(arguments[0] as BlockPos).immutable()] = arguments[1] as BlockState }
            "removeBlock", "destroyBlock" -> true.also { laid[(arguments[0] as BlockPos).immutable()] = AIR }
            "getBlockState" -> stateAt(arguments[0] as BlockPos)
            "getFluidState" -> laid[arguments[0] as BlockPos]?.fluidState ?: real.getFluidState(arguments[0] as BlockPos)
            "isStateAtPosition" -> (arguments[1] as Predicate<BlockState>).test(stateAt(arguments[0] as BlockPos))
            "isFluidAtPosition" -> (arguments[1] as Predicate<FluidState>).test(stateAt(arguments[0] as BlockPos).fluidState)
            "isEmptyBlock" -> stateAt(arguments[0] as BlockPos).isAir
            else -> try {
                method.invoke(real, *arguments)
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }

        /**
         * Writes the draft at [scale] about [origin]: each block of the scaled box takes the draft's block
         * nearest under it, so nothing is stretched out of order and a shrunk feature keeps its silhouette.
         */
        fun layOut(origin: BlockPos, scale: Double) {
            if (laid.isEmpty()) return
            val offsets = laid.keys.map { it.subtract(origin) }
            fun span(along: (BlockPos) -> Int): IntRange {
                val least = floor(offsets.minOf(along) * scale).toInt()
                val most = ceil((offsets.maxOf(along) + 1) * scale).toInt() - 1
                return least..most
            }
            val cursor = BlockPos.MutableBlockPos()
            for (x in span(BlockPos::getX)) for (y in span(BlockPos::getY)) for (z in span(BlockPos::getZ)) {
                val drawn = laid[origin.offset(underneath(x, scale), underneath(y, scale), underneath(z, scale))] ?: continue
                cursor.set(origin.x + x, origin.y + y, origin.z + z)
                if (canWrite(cursor)) real.setBlock(cursor, drawn, Block.UPDATE_CLIENTS)
            }
        }

        private fun underneath(scaled: Int, scale: Double): Int = floor(scaled / scale).toInt()

        private fun canWrite(at: BlockPos): Boolean {
            if (real.isOutsideBuildHeight(at)) return false
            val region = real as? WorldGenRegion ?: return real.hasChunkAt(at)
            val center = region.center
            val withinX = abs(SectionPos.blockToSectionCoord(at.x) - center.x) <= WRITABLE_CHUNKS_AWAY
            val withinZ = abs(SectionPos.blockToSectionCoord(at.z) - center.z) <= WRITABLE_CHUNKS_AWAY
            return withinX && withinZ
        }
    }

    companion object {
        val CODEC: MapCodec<ScaledFeature> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Feature.CODEC.fieldOf("feature").forGetter(ScaledFeature::feature),
                Codec.DOUBLE.fieldOf("scale").forGetter(ScaledFeature::scale),
            ).apply(instance, ::ScaledFeature)
        }

        /** How many chunks from the one being decorated a feature may still write in. */
        private const val WRITABLE_CHUNKS_AWAY = 1

        private val AIR = Blocks.AIR.defaultBlockState()
    }
}
