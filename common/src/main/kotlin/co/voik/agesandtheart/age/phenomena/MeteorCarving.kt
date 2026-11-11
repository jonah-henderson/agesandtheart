package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.worldgen.feature.ImpactCrater
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * How a meteor's crater is cut: what it breaks drops, gathered into stacks as a blast gathers its own, and what a blast
 * of its strength could not break stands where it is.
 */
class MeteorCarving(private val level: ServerLevel, blast: Float) : ImpactCrater.Carving {

    override val flags: Int = Block.UPDATE_ALL

    private val gathered = mutableListOf<ItemStack>()

    /** The hardest block a blast of this strength breaks point-blank, from the explosion's own falloff. */
    private val breaksUpTo: Float = blast * STRONGEST_RAY / STEP - RESISTANCE_PADDING

    override fun set(level: WorldGenLevel, at: BlockPos, state: BlockState) {
        val was = this.level.getBlockState(at)
        if (was == state || !breaks(was, at)) return
        val isSomethingToDrop = !was.isAir && was.block !is LiquidBlock
        if (isSomethingToDrop) gather(was, at)
        this.level.setBlock(at, state, flags)
    }

    /** Everything it broke, let fall at [at]. */
    fun letFall(at: BlockPos) {
        gathered.forEach { Block.popResource(level, at, it) }
        gathered.clear()
    }

    private fun breaks(state: BlockState, at: BlockPos): Boolean {
        if (state.block is LiquidBlock) return true
        val isUnbreakable = state.getDestroySpeed(level, at) < 0.0f
        return !isUnbreakable && state.block.explosionResistance <= breaksUpTo
    }

    private fun gather(state: BlockState, at: BlockPos) {
        val blockEntity = if (state.hasBlockEntity()) level.getBlockEntity(at) else null
        Block.getDrops(state, level, at, blockEntity).forEach(::mergeIn)
        state.spawnAfterBreak(level, at, ItemStack.EMPTY, true)
    }

    private fun mergeIn(stack: ItemStack) {
        for ((index, held) in gathered.withIndex()) {
            if (!ItemEntity.areMergable(held, stack)) continue
            gathered[index] = ItemEntity.merge(held, stack, GATHERED_UP_TO)
            if (stack.isEmpty) return
        }
        gathered.add(stack)
    }

    private companion object {
        /** How much stronger than its power a blast's strongest ray starts. */
        const val STRONGEST_RAY = 1.3f

        /** How long a ray's step is, and the padding added to every block's resistance. */
        const val STEP = 0.3f
        const val RESISTANCE_PADDING = 0.3f

        /** How many of one item are gathered into a stack before another is begun, as a blast gathers them. */
        const val GATHERED_UP_TO = 16
    }
}
