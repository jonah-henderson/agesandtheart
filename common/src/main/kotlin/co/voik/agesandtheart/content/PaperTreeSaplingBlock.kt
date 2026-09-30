package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
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
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A paper tree's sapling, **cheap enough to lose** (design §7.1.2): leaves drop it as oak's drop theirs, and
 * the experiment is planting one and seeing whether it takes.
 *
 * It follows the root's rule from the day it is planted — wetter beside water, drier away from it, rain no
 * help — and a sapling kept in its band grows, where one left out of it too long dies to a dead bush. When
 * it grows, the ground under it becomes the tree's heart, with the moisture the sapling had.
 */
class PaperTreeSaplingBlock(properties: Properties) : VegetationBlock(properties), BonemealableBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(PaperTreeHealth.MOISTURE, PaperTreeHealth.SETTLED)
                .setValue(STAGE, 0)
                .setValue(STRAIN, 0),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(PaperTreeHealth.MOISTURE, STAGE, STRAIN)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    /** Soil a tide reaches — the ground a root grows through ([PaperTreeGrowth.isSoftGround]). */
    override fun mayPlaceOn(state: BlockState, level: BlockGetter, pos: BlockPos): Boolean =
        PaperTreeGrowth.isSoftGround(state)

    override fun isRandomlyTicking(state: BlockState): Boolean = true

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val was = state.getValue(PaperTreeHealth.MOISTURE)
        val moisture = PaperTreeHealth.moistened(was, touchesWater(level, pos))
        val wetted = state.setValue(PaperTreeHealth.MOISTURE, moisture)
        if (PaperTreeHealth.bandOf(moisture) != PaperTreeHealth.Band.SUITS) return wither(level, pos, wetted)
        val eased = wetted.setValue(STRAIN, 0)
        val growsNow = random.nextInt(GROWS_ONE_TICK_IN) == 0
        if (growsNow) grow(level, pos, eased, random) else setIfChanged(level, pos, state, eased)
    }

    override fun isValidBonemealTarget(level: LevelReader, pos: BlockPos, state: BlockState, source: BonemealSource) =
        PaperTreeHealth.bandOf(state.getValue(PaperTreeHealth.MOISTURE)) == PaperTreeHealth.Band.SUITS

    override fun isBonemealSuccess(
        level: Level,
        random: RandomSource,
        pos: BlockPos,
        state: BlockState,
        source: BonemealSource,
    ): Boolean = random.nextFloat() < BONEMEAL_TAKES

    override fun performBonemeal(
        level: ServerLevel,
        random: RandomSource,
        pos: BlockPos,
        state: BlockState,
        source: BonemealSource,
    ) = grow(level, pos, state, random)

    /** A stage, and from the last stage the tree, with the ground beneath as its heart. */
    private fun grow(level: ServerLevel, pos: BlockPos, state: BlockState, random: RandomSource) {
        if (state.getValue(STAGE) < LAST_STAGE) {
            level.setBlock(pos, state.setValue(STAGE, state.getValue(STAGE) + 1), UPDATE_CLIENTS)
            return
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_NONE)
        val grew = PaperTreeGrowth.grow(
            level,
            pos.below(),
            random.nextLong(),
            state.getValue(PaperTreeHealth.MOISTURE),
            UPDATE_ALL,
        )
        if (!grew) level.setBlock(pos, state, UPDATE_NONE)
    }

    /** A tick out of its band: a step of strain, and past what a sapling can bear, a dead bush. */
    private fun wither(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val strain = state.getValue(STRAIN) + 1
        if (strain > MOST_STRAIN) {
            level.setBlock(pos, Blocks.DEAD_BUSH.defaultBlockState(), UPDATE_ALL)
            return
        }
        level.setBlock(pos, state.setValue(STRAIN, strain), UPDATE_CLIENTS)
    }

    private fun setIfChanged(level: ServerLevel, pos: BlockPos, was: BlockState, now: BlockState) {
        if (now != was) level.setBlock(pos, now, UPDATE_CLIENTS)
    }

    /** Whether water touches the sapling or the soil it stands in — the soil being where a root would be. */
    private fun touchesWater(level: ServerLevel, pos: BlockPos): Boolean {
        fun isWater(at: BlockPos) = level.getFluidState(at).`is`(FluidTags.WATER)
        fun isWaterBeside(at: BlockPos) = Direction.entries.any { side -> isWater(at.relative(side)) }
        return isWaterBeside(pos) || isWaterBeside(pos.below())
    }

    companion object {
        val STAGE: IntegerProperty = IntegerProperty.create("stage", 0, 1)
        val STRAIN: IntegerProperty = IntegerProperty.create("strain", 0, 3)

        private const val LAST_STAGE = 1
        private const val MOST_STRAIN = 3

        /** About three random ticks in band a stage, so a sapling kept right grows in a handful of minutes. */
        private const val GROWS_ONE_TICK_IN = 3

        /** Oak's odds, since bone meal is how a player hurries a sapling everywhere else. */
        private const val BONEMEAL_TAKES = 0.45f

        private val SHAPE: VoxelShape = Block.column(12.0, 0.0, 12.0)
    }
}
