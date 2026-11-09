package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState

/**
 * What comes out of a fissure lands on top of the heap at its exit — rubble, fluids and players alike — and a
 * player is not put down in a liquid or on fire where there is anywhere else to go.
 *
 * Laid out of vanilla blocks, since `AgeContent` cannot be class-initialised offline.
 */
@Tags(NEEDS_REGISTRIES)
class ExitGroundCheck : FunSpec({

    test("a fluid is poured on top of the heap, not into a gap inside it") {
        val heap = Heap().apply {
            stone(0, 0..10)
            air(0, 4)
        }
        val poured = ExitGround.pouredInto(heap, heap.column(0, 0))
        check(poured == BlockPos(0, 11, 0)) { "a source went to $poured, inside the heap rather than on it" }
    }

    test("a fluid poured onto a pool rises over it rather than replacing a source") {
        val heap = Heap().apply {
            stone(0, 0..10)
            lava(0, 11)
        }
        val poured = ExitGround.pouredInto(heap, heap.column(0, 0))
        check(poured == BlockPos(0, 12, 0)) { "a source went to $poured rather than over the lava already there" }
    }

    test("a fall comes down on top of the heap, not in a hollow inside it") {
        val heap = Heap().apply {
            stone(0, 0..40)
            air(0, 3)
            air(0, 4)
        }
        val landing = ExitGround.landableNear(heap, 0, 0, heap::column)
        check(landing?.x == 0 && landing.restingOn == 40) { "the fall went to $landing, not the top of the heap" }
    }

    test("a fall lands on a heap that reaches the top of the world, not beside it") {
        val heap = Heap().apply {
            stone(0, 0..maxY)
            stone(3, 0..2)
        }
        val landing = ExitGround.landableNear(heap, 0, 0, heap::column)
        check(landing?.x == 0 && landing.restingOn == heap.maxY) { "the fall went to $landing, not the top of the heap" }
    }

    test("a fall is moved off lava and water to the nearest dry column") {
        val heap = Heap().apply {
            for (x in -2..2) stone(x, 0..2)
            lava(0, 3)
            set(-1, 3, Blocks.WATER.defaultBlockState())
            set(1, 3, Blocks.WATER.defaultBlockState())
            lava(-2, 3)
        }
        val landing = ExitGround.landableNear(heap, 0, 0, heap::column)
        check(landing?.x == 2) { "the fall went to $landing, not the dry column at 2" }
    }

    test("a fall is moved off magma, which burns from underneath") {
        val heap = Heap().apply {
            stone(0, 0..9)
            set(0, 10, Blocks.MAGMA_BLOCK.defaultBlockState())
            stone(1, 0..10)
        }
        val landing = ExitGround.landableNear(heap, 0, 0, heap::column)
        check(landing?.x == 1) { "the fall came down on magma at $landing" }
    }

    test("a fall over nothing has nowhere to land") {
        val heap = Heap()
        val landing = ExitGround.landableNear(heap, 0, 0, heap::column)
        check(landing == null) { "a fall over nothing found $landing" }
    }
}) {

    /** A few columns laid along x, in the void. */
    private class Heap : BlockGetter {
        private val blocks = mutableMapOf<BlockPos, BlockState>()

        fun set(x: Int, y: Int, state: BlockState) {
            blocks[BlockPos(x, y, 0)] = state
        }

        fun stone(x: Int, ys: IntRange) = ys.forEach { set(x, it, Blocks.STONE.defaultBlockState()) }

        fun air(x: Int, y: Int) = set(x, y, Blocks.AIR.defaultBlockState())

        fun lava(x: Int, y: Int) = set(x, y, Blocks.LAVA.defaultBlockState())

        /** The column at [x], [z], read as a level reads it, from over its highest block. */
        fun column(x: Int, z: Int): ExitGround.Column {
            val highest = blocks.filter { (at, state) -> at.x == x && at.z == z && !state.isAir }.keys.maxOfOrNull { it.y }
            return ExitGround.read(this, x, z, clearAbove = (highest ?: minY) + 1)
        }

        override fun getBlockEntity(pos: BlockPos): BlockEntity? = null

        override fun getBlockState(pos: BlockPos): BlockState = blocks[pos] ?: Blocks.AIR.defaultBlockState()

        override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState

        override fun getHeight(): Int = A_WORLD_TALL

        override fun getMinY(): Int = A_WORLD_BOTTOM
    }

    private companion object {
        const val A_WORLD_TALL = 64
        const val A_WORLD_BOTTOM = -1
    }
}
