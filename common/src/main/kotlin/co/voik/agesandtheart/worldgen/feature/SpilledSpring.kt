package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.feature.Feature

/**
 * **A spring that tried and could not** — the substance pushing out of the wall, falling a little, and
 * setting where it stopped.
 *
 * A spring runs with a fluid, and `fluidState` of a block that is not one is `Fluids.EMPTY`, so
 * `gold_block springs` describes something the game cannot make. That is charged for as an incoherence
 * ([co.voik.agesandtheart.age.Register.DISPLACED]) and the world says so too: what stands there is the
 * shape a flow would have left if it had been able to happen.
 *
 * **Flowing turns a corner, and that is why this is a feature rather than a composition.** `BLOCK_COLUMN`
 * runs straight down from where it starts, which is a stub hanging under the source and reads as nothing
 * in particular; `BlockPileFeature` heaps on a floor. A fluid leaving a wall goes *out* through the opening
 * the rock left and only then down, and nothing vanilla ships turns that corner.
 *
 * The substance is the whole of what varies, and in 26.3 a feature carries its own configuration, so it
 * is a field here rather than a separate record beside it.
 */
data class SpilledSpring(val substance: BlockState) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean =
        spill(
            source = origin,
            substance = substance,
            isOpen = level::isEmptyBlock,
            random = random,
        ) { position, state -> level.setBlock(position, state, PLACED_BY_WORLDGEN) }

    companion object {

        val CODEC: MapCodec<SpilledSpring> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockState.CODEC.fieldOf("substance").forGetter(SpilledSpring::substance),
            ).apply(instance, ::SpilledSpring)
        }

        /**
         * The shape itself, of anything that can say what is open and take a block — so what it draws can
         * be checked without a world under it, a `WorldGenLevel` having a hundred members where this reads
         * one.
         *
         * False, having laid nothing, wherever the picture would not be true: a source in open air has no
         * wall to have come out of, and one walled in on every side had nowhere to go. Both matter, because
         * a spring's placement puts these at any height and most of them land inside unbroken rock.
         */
        fun spill(
            source: BlockPos,
            substance: BlockState,
            isOpen: (BlockPos) -> Boolean,
            random: RandomSource,
            lay: (BlockPos, BlockState) -> Unit,
        ): Boolean {
            if (isOpen(source)) return false
            // Taken in an order the seed decides, so a chamber open on two sides is not always spilled
            // into the same one.
            val ways = Direction.Plane.HORIZONTAL.shuffledCopy(random)
            val out = ways.firstOrNull { isOpen(source.relative(it)) } ?: return false

            lay(source, substance)
            val spilled = source.relative(out)
            lay(spilled, substance)

            val falling = spilled.below().mutable()
            repeat(random.nextInt(FURTHEST_FALL) + 1) {
                if (!isOpen(falling)) return true
                lay(falling.immutable(), substance)
                falling.move(Direction.DOWN)
            }
            return true
        }

        /** How far what came out gets before it sets — a block or two, and sometimes a third. */
        private const val FURTHEST_FALL = 3

        /** Vanilla's own flag for a block a feature lays: change it, and do not tell a neighbour. */
        private const val PLACED_BY_WORLDGEN = 2
    }
}
