package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.BonemealSource
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A torchflower a scarab has grazed, growing back into the flower (design §7.1.2).
 *
 * **Not vanilla's torchflower crop**, which is a `CropBlock` and wants farmland: set onto a jungle floor it
 * would pop off at its first neighbour update. This stands wherever the flower stood, wears the crop's two
 * stages, and becomes the flower again, so a colony never eats its own habitat away.
 */
class GrazedTorchflowerBlock(properties: Properties) : VegetationBlock(properties), BonemealableBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(AGE, YOUNGEST))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AGE)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES[state.getValue(AGE)]

    override fun isRandomlyTicking(state: BlockState): Boolean = true

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(ONE_IN_THIS_MANY_TICKS_GROWS) == 0) grow(level, pos, state)
    }

    override fun isValidBonemealTarget(level: LevelReader, pos: BlockPos, state: BlockState, source: BonemealSource) =
        true

    override fun isBonemealSuccess(
        level: Level,
        random: RandomSource,
        pos: BlockPos,
        state: BlockState,
        source: BonemealSource,
    ): Boolean = true

    override fun performBonemeal(
        level: ServerLevel,
        random: RandomSource,
        pos: BlockPos,
        state: BlockState,
        source: BonemealSource,
    ) = grow(level, pos, state)

    /** One stage, and from the last stage the flower itself. */
    private fun grow(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val age = state.getValue(AGE)
        val next = if (age >= OLDEST) Blocks.TORCHFLOWER.defaultBlockState() else state.setValue(AGE, age + 1)
        level.setBlock(pos, next, UPDATE_CLIENTS)
    }

    companion object {
        /** The torchflower crop's own two stages. */
        val AGE: IntegerProperty = BlockStateProperties.AGE_1
        private const val YOUNGEST = 0
        private const val OLDEST = 1

        /**
         * About four and a half minutes a stage at the default random tick speed, so a grazed flower is back
         * in about nine. How far a colony's flowers go is this against how often it eats (`Scarab.MEAL`), and
         * is for playtest to tune.
         */
        private const val ONE_IN_THIS_MANY_TICKS_GROWS = 4

        /** The crop's own shapes: a stalk six wide, taller at each stage. */
        private val SHAPES: Array<VoxelShape> = arrayOf(Block.column(6.0, 0.0, 6.0), Block.column(6.0, 0.0, 10.0))
    }
}
