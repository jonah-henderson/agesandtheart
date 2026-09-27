package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **A heap of loose blocks** — vanilla's block pile, with a size it can be asked for.
 *
 * At [scale] one it is the pile a village leaves by its farms: two or three blocks across and two high,
 * thinning to strays at its edge. Larger, it becomes a mound whose columns fall away from the middle;
 * smaller, a block or two. `BlockPileFeature` hard-codes its radius and height, which is the whole reason
 * this exists.
 */
data class Heap(val stateProvider: Holder<BlockStateProvider>, val scale: Double = ORDINARY) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean {
        if (origin.y < level.minY + CLEARANCE) return false
        val laid = heap(
            origin = origin,
            scale = scale,
            random = random,
            isOpen = level::isEmptyBlock,
            holdsUp = { below -> level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) },
        ) { position ->
            level.setBlock(position, stateProvider.value().getState(level, random, position), PLACED_BY_WORLDGEN)
        }
        return laid > 0
    }

    companion object {
        const val ORDINARY = 1.0

        /** Vanilla's pile is two or three blocks from its middle, and two high. */
        private const val LEAST_RADIUS = 2
        private const val RADIUS_SPREAD = 2
        private const val ORDINARY_HEIGHT = 2

        /** The farthest a heap reaches from its origin, which keeps it within the chunk next door. */
        private const val MOST_RADIUS = 12

        /** Past this fraction of the radius the heap thins, as vanilla's does, rather than ending on a wall. */
        private const val THINNING_FROM = 0.6

        /** How far down or up a column looks for ground, beyond the heap's own height. */
        private const val SEARCH_MARGIN = 3

        /** Vanilla's refusal to heap near the floor of the world. */
        private const val CLEARANCE = 5

        /** `Block.UPDATE_CLIENTS`: worldgen places without neighbour updates. */
        private const val PLACED_BY_WORLDGEN = 2

        val CODEC: MapCodec<Heap> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockStateProvider.CODEC.fieldOf("state_provider").forGetter(Heap::stateProvider),
                Codec.DOUBLE.optionalFieldOf("scale", ORDINARY).forGetter(Heap::scale),
            ).apply(instance, ::Heap)
        }

        /**
         * The heap itself, of anything that can say what is open and what holds a block up — so its shape
         * can be checked without a world. Returns how many blocks it laid.
         *
         * Each column finds its own ground, so a heap on a slope follows it; each stacks only on what it
         * laid or on sturdy ground, so a heap of flowers stays one flower deep.
         */
        fun heap(
            origin: BlockPos,
            scale: Double,
            random: RandomSource,
            isOpen: (BlockPos) -> Boolean,
            holdsUp: (BlockPos) -> Boolean,
            lay: (BlockPos) -> Unit,
        ): Int {
            val radiusX = radiusOf(scale, random)
            val radiusZ = radiusOf(scale, random)
            val height = (ORDINARY_HEIGHT * scale).roundToInt().coerceAtLeast(1)
            var laid = 0
            for (dx in -radiusX..radiusX) {
                for (dz in -radiusZ..radiusZ) {
                    val across = sqrt(square(dx / (radiusX + 0.5)) + square(dz / (radiusZ + 0.5)))
                    if (across > 1.0) continue
                    val thinned = across > THINNING_FROM &&
                        random.nextDouble() < (across - THINNING_FROM) / (1.0 - THINNING_FROM)
                    if (thinned) continue
                    val tall = ceil(height * (1.0 - across * across) * (0.6 + 0.4 * random.nextDouble())).toInt()
                    val base = groundOf(origin.offset(dx, 0, dz), height, isOpen, holdsUp) ?: continue
                    for (up in 0..<tall.coerceAtLeast(1)) {
                        val position = base.above(up)
                        val supported = up > 0 || holdsUp(position.below())
                        if (!isOpen(position) || !supported) break
                        lay(position)
                        laid++
                        if (!holdsUp(position)) break
                    }
                }
            }
            return laid
        }

        private fun radiusOf(scale: Double, random: RandomSource): Int =
            ((LEAST_RADIUS + random.nextInt(RADIUS_SPREAD)) * scale).roundToInt().coerceIn(0, MOST_RADIUS)

        /**
         * The highest open spot over sturdy ground within reach of [column], or null — highest so a column
         * under an overhang takes the ground on top rather than a hollow beneath it.
         */
        private fun groundOf(
            column: BlockPos,
            height: Int,
            isOpen: (BlockPos) -> Boolean,
            holdsUp: (BlockPos) -> Boolean,
        ): BlockPos? {
            val reach = height + SEARCH_MARGIN
            return (reach downTo -reach)
                .map { column.above(it) }
                .firstOrNull { isOpen(it) && holdsUp(it.below()) }
        }

        private fun square(value: Double) = value * value
    }
}
