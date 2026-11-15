package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A feature made of a pool of blocks — a tag a book named, or a pattern's own when it named none — laid of one of them
 * each time it is placed: one pile all hay and the next all pumpkins, one heap of beds all cyan. Each block of the pool
 * the feature lays is laid as the one drawn, keeping what it can of its state (a bed's facing, a log's axis).
 *
 * Drawn by where it is placed and the world's seed rather than from the feature's own random, so the shape it would
 * have laid is unchanged and a chunk comes out the same every time.
 */
data class OneOfAPool(val feature: Holder<Feature>, val pool: List<Block>, val weights: List<Double> = emptyList()) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun getSubFeatures() = feature.value().getSubFeatures()

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean =
        feature.value().place(viewOf(level, pool.toSet(), drawnAt(origin, level.seed)), generator, random, origin)

    /** The block of the pool a placement at [origin] in a world of [seed] is laid of. */
    internal fun drawnAt(origin: BlockPos, seed: Long): Block {
        val roll = (mixed(origin.asLong() xor seed xor pool.hashCode().toLong()) ushr FRACTION_SHIFT).toDouble() / FRACTION_RANGE
        return pool[WeightedDraw.indexAt(roll, weights, pool.size)]
    }

    /** Lays each block of the pool as [drawn]; anything else as it was asked. */
    private class Laying(private val real: WorldGenLevel, private val pool: Set<Block>, private val drawn: Block) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<Any?>?): Any? {
            val state = arguments?.getOrNull(1) as? BlockState
            val isLayingAnother = method.name == SET_BLOCK && state != null && state.block in pool && !state.`is`(drawn)
            val asked = if (isLayingAnother) {
                arguments.copyOf().also { it[1] = drawn.withPropertiesOf(requireNotNull(state)) }
            } else {
                arguments ?: emptyArray()
            }
            return try {
                method.invoke(real, *asked)
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
        }
    }

    companion object {
        val CODEC: MapCodec<OneOfAPool> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Feature.CODEC.fieldOf("feature").forGetter(OneOfAPool::feature),
                BuiltInRegistries.BLOCK.byNameCodec().listOf().fieldOf("pool").forGetter(OneOfAPool::pool),
                Codec.DOUBLE.listOf().optionalFieldOf("weights", emptyList()).forGetter(OneOfAPool::weights),
            ).apply(instance, ::OneOfAPool)
        }

        internal fun viewOf(level: WorldGenLevel, pool: Set<Block>, drawn: Block): WorldGenLevel =
            Proxy.newProxyInstance(level.javaClass.classLoader, arrayOf(WorldGenLevel::class.java), Laying(level, pool, drawn)) as WorldGenLevel

        // SplitMix64's finaliser, so neighbouring places draw apart.
        private fun mixed(value: Long): Long {
            var mixing = (value xor (value ushr SHIFT_ONE)) * SPREAD_ONE
            mixing = (mixing xor (mixing ushr SHIFT_TWO)) * SPREAD_TWO
            return mixing xor (mixing ushr SHIFT_THREE)
        }

        private const val SET_BLOCK = "setBlock"

        // The top 53 bits of the mix read as a fraction.
        private const val FRACTION_SHIFT = 11
        private const val FRACTION_RANGE = (1L shl 53).toDouble()
        private const val SHIFT_ONE = 30
        private const val SHIFT_TWO = 27
        private const val SHIFT_THREE = 31
        private const val SPREAD_ONE = -0x40a7b892e31b1a47L
        private const val SPREAD_TWO = -0x6b2fb644ecceee15L
    }
}
