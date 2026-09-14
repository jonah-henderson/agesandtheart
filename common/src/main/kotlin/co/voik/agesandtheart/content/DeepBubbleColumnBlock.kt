package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.Level
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
 * A whirlpool rising through the abyss — vanilla's bubble column, made of deep water below the abyss line and
 * of ordinary water above it, so one column runs from a vent to the surface without carrying the abyss up
 * with it.
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
 * **[DEEP] rather than a second block for the part above the line.** A column keeps itself standing by asking
 * whether the block under it is the same block, so where a column of ours met one of vanilla's, neither would
 * recognise the other and each would keep taking the boundary back. One block with a property is one column.
 *
 * **Raised by [raise] rather than by vanilla's `updateColumn`**, from [DeepWaterBlock.tick] and from this
 * block's own tick alike.
 */
class DeepBubbleColumnBlock(properties: BlockBehaviour.Properties) : BubbleColumnBlock(properties) {

    init {
        registerDefaultState(defaultBlockState().setValue(DEEP, true))
    }

    override fun codec(): MapCodec<BubbleColumnBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(DEEP)
    }

    /**
     * The abyss below the line, and vanilla's own water above it.
     *
     * **Cached once per state in `BlockStateBase.initCache`**, not read per lookup — which is why
     * [DeepWaterLogging.settleTheCache] has to reach this block's deep states as well as the waterlogged ones.
     * If deep water's fluid was not registered when our states were built, every one of them cached vanilla
     * water and nothing about it would ever look like a bug.
     */
    override fun getFluidState(state: BlockState): FluidState =
        if (isDeep(state)) DeepWater.deepWater().fluidState else super.getFluidState(state)

    /** Vanilla's tick, with the column raised by [raise]. */
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        raise(level, pos, state, level.getBlockState(pos.below()))
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
        val CODEC: MapCodec<BubbleColumnBlock> = simpleCodec(::DeepBubbleColumnBlock)

        /** Whether a block of the column stands at or below the abyss line, and so is the abyss. */
        @JvmField
        val DEEP: BooleanProperty = BooleanProperty.create("deep")

        /** The amount a full source of a fluid carries. */
        private const val FULL = 8

        fun isDeep(state: BlockState): Boolean = state.hasProperty(DEEP) && state.getValue(DEEP)

        /**
         * Raises the column standing at [from] over [below]: vanilla's `updateColumn`, each block deep or
         * ordinary by which side of the abyss line it stands — and without vanilla's fall-through.
         *
         * **Where nothing under a liquid raises a column, vanilla's loop runs on anyway with the liquid itself as
         * the column.** That is harmless for water, which only ever meets more of itself; for deep water under
         * the ordinary sea it was a pump. Every scheduled tick of the abyss's top layer wrote deep water up
         * through the sea to its surface, the sea's settling took it back, and the change woke the pump again.
         *
         * Vanilla's rules otherwise, restated because they are private: a column takes a full source of a fluid
         * the tag admits, or a block already the column; it drags down over magma and lifts over soul sand; and
         * a column that has lost what raised it goes back to the sea it stands in.
         */
        fun raise(level: ServerLevel, from: BlockPos, occupying: BlockState, below: BlockState) {
            if (!canOccupy(occupying)) return
            // With no abyss at all, a column of ours is ordinary water from its foot.
            val line = DeepWater.lineIn(level) ?: Int.MIN_VALUE
            val dragsDown = dragDownOver(below)
            when {
                dragsDown != null -> build(level, from, line, dragsDown)
                occupying.`is`(AgeContent.DEEP_BUBBLE_COLUMN) -> dissolve(level, from, line)
            }
        }

        /** The column from [from] up through everything it may occupy, stopping where it finds itself already right. */
        private fun build(level: ServerLevel, from: BlockPos, line: Int, dragsDown: Boolean) {
            val at = from.mutable()
            level.setBlock(at, columnAt(at.y, line, dragsDown), Block.UPDATE_CLIENTS)
            at.move(Direction.UP)
            while (canOccupy(level.getBlockState(at))) {
                if (!level.setBlock(at, columnAt(at.y, line, dragsDown), Block.UPDATE_CLIENTS)) return
                at.move(Direction.UP)
            }
        }

        /** A column that has lost what raised it, given back to the sea it stands in. */
        private fun dissolve(level: ServerLevel, from: BlockPos, line: Int) {
            val at = from.mutable()
            while (level.getBlockState(at).`is`(AgeContent.DEEP_BUBBLE_COLUMN)) {
                level.setBlock(at, seaAt(at.y, line), Block.UPDATE_CLIENTS)
                at.move(Direction.UP)
            }
        }

        private fun columnAt(y: Int, line: Int, dragsDown: Boolean): BlockState =
            AgeContent.DEEP_BUBBLE_COLUMN.defaultBlockState()
                .setValue(BubbleColumnBlock.DRAG_DOWN, dragsDown)
                .setValue(DEEP, y <= line)

        private fun seaAt(y: Int, line: Int): BlockState =
            if (y <= line) DeepWater.deepWater() else Blocks.WATER.defaultBlockState()

        /** Which way what is under a column moves it, or null where nothing under it raises one. */
        private fun dragDownOver(below: BlockState): Boolean? = when {
            below.`is`(AgeContent.DEEP_BUBBLE_COLUMN) -> below.getValue(BubbleColumnBlock.DRAG_DOWN)
            below.`is`(Blocks.MAGMA_BLOCK) -> true
            below.`is`(Blocks.SOUL_SAND) -> false
            else -> null
        }

        private fun canOccupy(state: BlockState): Boolean {
            if (state.`is`(AgeContent.DEEP_BUBBLE_COLUMN)) return true
            val fluid = state.fluidState
            val isAFullSource = fluid.isSource && fluid.amount >= FULL
            return fluid.`is`(FluidTags.BUBBLE_COLUMN_CAN_OCCUPY) && state.block is LiquidBlock && isAFullSource
        }
    }
}
