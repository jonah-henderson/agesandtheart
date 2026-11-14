package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A feature made of blocks that come in two halves — a bed, a tall flower, a door — laid so that every one stands whole.
 *
 * A feature lays a block one position at a time, so of a bed it lays the foot alone. Placed through this, a foot is laid
 * with its head beside it on an open side and a lower half with its upper half above; where there is no room for both,
 * neither. A head or upper half is laid only where its other half already stands, so one copied without it (a feature
 * resized whole) is never left alone, and a half once laid is never laid over by anything else.
 */
data class LaidWhole(val feature: Holder<Feature>) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun getSubFeatures() = feature.value().getSubFeatures()

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean =
        feature.value().place(viewOf(level), generator, random, origin)

    /** Lays each half with its other, or neither; anything else as it was asked. */
    private class Laying(private val real: WorldGenLevel) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<Any?>?): Any? {
            val at = arguments?.getOrNull(0) as? BlockPos
            val state = arguments?.getOrNull(1) as? BlockState
            val flags = arguments?.getOrNull(2) as? Int
            if (method.name == SET_BLOCK && at != null && state != null && flags != null) {
                laid(at, state, flags)?.let { return it }
            }
            return try {
                method.invoke(real, *(arguments ?: emptyArray()))
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
        }

        /** Whether [state] was laid at [at], where it is a half or lands on one; null where it is neither, to lay as asked. */
        private fun laid(at: BlockPos, state: BlockState, flags: Int): Boolean? {
            val there = real.getBlockState(at)
            val laysOverAHalf = isAHalf(there) && !there.`is`(state.block)
            if (laysOverAHalf) return false
            return when {
                state.hasProperty(BlockStateProperties.BED_PART) -> bed(at, state, flags)
                state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) -> standing(at, state, flags)
                else -> null
            }
        }

        private fun bed(at: BlockPos, state: BlockState, flags: Int): Boolean {
            if (state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
                val foot = at.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).opposite)
                return real.getBlockState(foot).`is`(state.block) && real.setBlock(at, state, flags)
            }
            val facing = facingsFrom(at).firstOrNull { isOpen(at.relative(it)) } ?: return false
            val foot = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
            real.setBlock(at, foot, flags)
            return real.setBlock(at.relative(facing), foot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), flags)
        }

        private fun standing(at: BlockPos, state: BlockState, flags: Int): Boolean {
            if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                return real.getBlockState(at.below()).`is`(state.block) && real.setBlock(at, state, flags)
            }
            if (!isOpen(at.above())) return false
            real.setBlock(at, state, flags)
            return real.setBlock(at.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), flags)
        }

        /** Whether the other half may go at [at]: writable, and nothing there but what anything may replace. */
        private fun isOpen(at: BlockPos): Boolean {
            if (!real.ensureCanWrite(at)) return false
            val there = real.getBlockState(at)
            return !isAHalf(there) && (there.isAir || there.canBeReplaced())
        }

        /** The four sides, starting from one fixed by the place, so a chunk comes out the same every time. */
        private fun facingsFrom(at: BlockPos): List<Direction> {
            val first = Math.floorMod(at.asLong() xor (at.asLong() ushr Int.SIZE_BITS), HORIZONTALS.size)
            return HORIZONTALS.indices.map { HORIZONTALS[(first + it) % HORIZONTALS.size] }
        }
    }

    companion object {
        val CODEC: MapCodec<LaidWhole> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(Feature.CODEC.fieldOf("feature").forGetter(LaidWhole::feature)).apply(instance, ::LaidWhole)
        }

        /** Whether [block] comes in two halves, and so must be laid through [LaidWhole]. */
        fun comesInTwoHalves(block: Block): Boolean = isAHalf(block.defaultBlockState())

        /** The same, of the block a substance names, and false where it names none. */
        fun comesInTwoHalves(named: String): Boolean = Identifier.tryParse(named)
            ?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
            ?.let(::comesInTwoHalves) == true

        /** [feature], or within it where it is a [LaidWhole]: what the wrapping hides from whoever asks what it is. */
        fun within(feature: Feature): Feature = (feature as? LaidWhole)?.feature?.value() ?: feature

        private fun isAHalf(state: BlockState): Boolean =
            state.hasProperty(BlockStateProperties.BED_PART) || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)

        internal fun viewOf(level: WorldGenLevel): WorldGenLevel =
            Proxy.newProxyInstance(level.javaClass.classLoader, arrayOf(WorldGenLevel::class.java), Laying(level)) as WorldGenLevel

        private val HORIZONTALS = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

        private const val SET_BLOCK = "setBlock"
    }
}
