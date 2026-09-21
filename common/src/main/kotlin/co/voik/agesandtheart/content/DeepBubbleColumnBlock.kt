package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BubbleColumnBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.FluidState

/**
 * A whirlpool rising through the abyss — vanilla's bubble column, deep where it took the place of deep water and
 * ordinary where it took ordinary water, so one column runs from a vent to the surface without carrying the
 * abyss up with it.
 *
 * **Vanilla's column is water and only water, and that hole was exactly the wrong shape.**
 * `BubbleColumnBlock.getFluidState` returns `Fluids.WATER.getSource(false)` unconditionally, so a column
 * rising through an abyss replaced every block of the bore with something that was not deep water. Two
 * things fell out of that, and both undid the vent: `DeepWater.press` is called from `DeepWaterBlock`'s
 * own `entityInside` and so never fired, and `DeepWaterFog.deepAt` reads the fluid at the eye and so
 * opened the fog back out to whatever the Age's ordinary sea is — up to `AgeAir.CLEAREST`, 256 blocks.
 * The whirlpool was a pressure-free, clear-water lift straight down into the one place that should cost
 * you something to be in.
 *
 * **[DEEP] rather than a second block for the ordinary part.** A column keeps itself standing by asking
 * whether the block under it is the same block, so where a column of ours met one of vanilla's, neither would
 * recognise the other and each would keep taking the boundary back. One block with a property is one column.
 *
 * **Every column in a level with an abyss is raised by [raise]** — from [DeepWaterBlock.tick], from this block's
 * own tick, and from vanilla's `updateColumn` through `BubbleColumnBlockMixin`. Deep water is kept off
 * `#minecraft:bubble_column_can_occupy`, so vanilla's own routine can never turn the abyss ordinary; [raise]
 * admits deep water itself.
 *
 * **Once standing, a column settles as the water it holds would**: deep that could not stand goes ordinary,
 * and deep water beside an ordinary block takes it in (`DeepWater.takeIn`). A column raised through
 * ordinary water that the abyss reaches afterwards is taken in with the rest.
 */
class DeepBubbleColumnBlock(properties: BlockBehaviour.Properties) : BubbleColumnBlock(properties) {

    init {
        registerDefaultState(defaultBlockState().setValue(DEEP, true))
    }


    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(DEEP)
    }

    /**
     * The abyss where the column is deep, and vanilla's own water where it is not.
     *
     * **Cached once per state in `BlockStateBase.initCache`**, not read per lookup — which is why
     * [DeepWaterLogging.settleTheCache] has to reach this block's deep states as well as the waterlogged ones.
     * If deep water's fluid was not registered when our states were built, every one of them cached vanilla
     * water and nothing about it would ever look like a bug.
     */
    override fun getFluidState(state: BlockState): FluidState =
        if (isDeep(state)) DeepWater.deepWater().fluidState else super.getFluidState(state)

    /** Vanilla's tick, with the column raised by [raise], and then settled. */
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        raise(level, pos, state, level.getBlockState(pos.below()))
        settle(level, pos)
    }

    /** Deep water's random tick, for the part of the column that is deep water — see `DeepWaterBlock`. */
    override fun isRandomlyTicking(state: BlockState): Boolean = isDeep(state)

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (DeepWater.isUnderRock(level, pos)) return
        settle(level, pos)
    }

    /** Vanilla's, but hearing a change from any side, as deep water does: one to the side can depressurise it. */
    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        ticks.scheduleTick(pos, this, SETTLES_IN)
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
    }

    /**
     * The deep pressing on whatever the column is carrying down, below the line.
     *
     * `super` first so the drag itself is vanilla's — what is ours is that being dragged through the abyss costs
     * you the same as swimming in it, which is the whole point of the vent being entered this way.
     */
    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effectApplier: InsideBlockEffectApplier,
        isPrecise: Boolean,
    ) {
        super.entityInside(state, level, pos, entity, effectApplier, isPrecise)
        if (isDeep(state)) DeepWater.press(level, entity)
    }

    companion object {

        /** Whether a block of the column took the place of deep water, and so is the abyss. */
        @JvmField
        val DEEP: BooleanProperty = BooleanProperty.create("deep")

        /** The amount a full source of a fluid carries. */
        private const val FULL = 8

        /** Vanilla's own delay for a column's tick. */
        private const val SETTLES_IN = 5

        fun isDeep(state: BlockState): Boolean = state.hasProperty(DEEP) && state.getValue(DEEP)

        /**
         * The deep whirlpool [state] becomes when the abyss takes it in, or null where it is not a whirlpool the
         * abyss could take — ours already deep, or anything else.
         */
        fun deepened(state: BlockState): BlockState? = when {
            state.`is`(AgeContent.DEEP_BUBBLE_COLUMN) -> if (isDeep(state)) null else state.setValue(DEEP, true)
            state.`is`(Blocks.BUBBLE_COLUMN) -> AgeContent.DEEP_BUBBLE_COLUMN.defaultBlockState()
                .setValue(BubbleColumnBlock.DRAG_DOWN, state.getValue(BubbleColumnBlock.DRAG_DOWN))
            else -> null
        }

        /**
         * A deep block of the column brought into line as deep water is (`DeepWaterBlock.settle`): ordinary where
         * the abyss may not stand, and otherwise taking in what it touches. An ordinary block is left to be taken
         * in from beside, as ordinary water is.
         */
        private fun settle(level: ServerLevel, pos: BlockPos) {
            val state = level.getBlockState(pos)
            if (!isDeep(state)) return
            if (!DeepWater.standsAt(level, pos)) {
                level.setBlockAndUpdate(pos, state.setValue(DEEP, false))
                return
            }
            for (side in Direction.entries) DeepWater.takeIn(level, pos.relative(side))
        }

        /** Whether vanilla's own column is handed to [raise] instead: in a level with an abyss. */
        @JvmStatic
        fun takesOver(bubbleColumn: Block, level: LevelAccessor): Boolean =
            bubbleColumn == Blocks.BUBBLE_COLUMN && level is ServerLevel && DeepWater.lineIn(level) != null

        /**
         * Raises the column standing at [from] over [below]: vanilla's `updateColumn`, each block deep or
         * ordinary by the water it replaces — and without vanilla's fall-through.
         *
         * **Where nothing under a liquid raises a column, vanilla's loop runs on anyway with the liquid itself as
         * the column.** That is harmless for water, which only ever meets more of itself; for deep water under
         * the ordinary sea it was a pump. Every scheduled tick of the abyss's top layer wrote deep water up
         * through the sea to its surface, the sea's settling took it back, and the change woke the pump again.
         *
         * Vanilla's rules otherwise, restated because they are private: a column takes a full source of a fluid
         * the tag admits, or a block already the column; it drags down over magma and lifts over soul sand; and
         * a column that has lost what raised it goes back to the water it took.
         */
        @JvmStatic
        fun raise(level: LevelAccessor, from: BlockPos, occupying: BlockState, below: BlockState) {
            if (!canOccupy(occupying)) return
            val dragsDown = dragDownOver(below)
            when {
                dragsDown != null -> build(level, from, dragsDown)
                isAColumn(occupying) -> dissolve(level, from)
            }
        }

        /** The column from [from] up through everything it may occupy, stopping where it finds itself already right. */
        private fun build(level: LevelAccessor, from: BlockPos, dragsDown: Boolean) {
            val at = from.mutable()
            level.setBlock(at, columnOver(level.getBlockState(at), dragsDown), Block.UPDATE_CLIENTS)
            at.move(Direction.UP)
            while (true) {
                val occupying = level.getBlockState(at)
                if (!canOccupy(occupying)) return
                if (!level.setBlock(at, columnOver(occupying, dragsDown), Block.UPDATE_CLIENTS)) return
                at.move(Direction.UP)
            }
        }

        /** A column that has lost what raised it, each block given back the water it took. */
        private fun dissolve(level: LevelAccessor, from: BlockPos) {
            val at = from.mutable()
            while (true) {
                val column = level.getBlockState(at)
                if (!isAColumn(column)) return
                val water = if (isDeep(column)) DeepWater.deepWater() else Blocks.WATER.defaultBlockState()
                level.setBlock(at, water, Block.UPDATE_CLIENTS)
                at.move(Direction.UP)
            }
        }

        /**
         * One block of the column, over what it replaces: deep where it takes the abyss, ordinary where it takes
         * ordinary water — so one column runs on across the boundary, and [dissolve] gives each block back as it was.
         */
        private fun columnOver(occupying: BlockState, dragsDown: Boolean): BlockState =
            AgeContent.DEEP_BUBBLE_COLUMN.defaultBlockState()
                .setValue(BubbleColumnBlock.DRAG_DOWN, dragsDown)
                .setValue(DEEP, isTheAbyss(occupying))

        private fun isTheAbyss(state: BlockState): Boolean = state.block == DeepWater.deepWater().block || isDeep(state)

        private fun isAColumn(state: BlockState): Boolean =
            state.`is`(AgeContent.DEEP_BUBBLE_COLUMN) || state.`is`(Blocks.BUBBLE_COLUMN)

        /**
         * Which way what is under a column moves it, or null where nothing under it raises one — vanilla's two
         * tags, pushed up before dragged down as vanilla checks them, and a column of either kind carried on.
         */
        private fun dragDownOver(below: BlockState): Boolean? = when {
            below.`is`(AgeContent.DEEP_BUBBLE_COLUMN) || below.`is`(Blocks.BUBBLE_COLUMN) -> below.getValue(BubbleColumnBlock.DRAG_DOWN)
            below.`is`(BlockTags.ENABLES_BUBBLE_COLUMN_PUSH_UP) -> false
            below.`is`(BlockTags.ENABLES_BUBBLE_COLUMN_DRAG_DOWN) -> true
            else -> null
        }

        private fun canOccupy(state: BlockState): Boolean {
            // Vanilla's own column included, so a level with an abyss takes over the ones already standing.
            if (isAColumn(state)) return true
            val fluid = state.fluidState
            val isAFullSource = fluid.isSource && fluid.amount >= FULL
            val isDeepWater = state.block == DeepWater.deepWater().block
            val vanillaWouldTakeIt = fluid.`is`(FluidTags.BUBBLE_COLUMN_CAN_OCCUPY) && state.block is LiquidBlock
            return (isDeepWater || vanillaWouldTakeIt) && isAFullSource
        }
    }
}
