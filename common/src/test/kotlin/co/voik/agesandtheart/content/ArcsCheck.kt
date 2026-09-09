package co.voik.agesandtheart.content

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

/**
 * **Who drives a run, and what it is worth** — the two questions a charged machine is built out of, and
 * the pair this codebase keeps collapsing into one.
 *
 * Both have now failed in play, and both failed the same way: *nothing happened*. A machine with no driver
 * throws no field, emits no particle and logs nothing, so there is no symptom to chase — you lay out a
 * build, it does not work, and there is no way to tell that from having built it wrong. That is exactly
 * the shape of thing a check has to hold, and it needs no server to hold it.
 *
 * **Both halves are predicates the caller passes**, so these are laid out of plain vanilla blocks and
 * name nothing of ours. That is not an accident of the test: `AgeContent` cannot be class-initialised
 * offline at all — the item registry is frozen by then and building one throws "can't create intrusive
 * holders" — so a check that had to name the real block could only ever have been a walk.
 */
@Tags(NEEDS_REGISTRIES)
class ArcsCheck : FunSpec({

    /**
     * **The bug that shipped**: a bank of crystal against a bar drove nothing at all.
     *
     * Electing the driver over the whole connected *pile* hands the run to whichever block of the bank
     * sorts lowest — and that block is somewhere in the middle of the stack with no metal beside it, so it
     * has no run to drive and the crystal that does touch the bar has already lost. Every arrangement a
     * player would actually build is a bank against a bar.
     */
    test("a bank of crystal drives the bar it is stacked against") {
        val bench = bank()
        val driven = everyRunIn(bench, THE_BANK)
        check(driven.size == ONE_RUN) { "a bank against a bar drove ${driven.size} runs, not one" }
        check(driven.single().blocks.size == THREE_BLOCKS) { "the bar came out ${driven.single().blocks.size} long" }
    }

    /**
     * And the bank is what it is *worth*, which is the other half and the one that was broken first.
     *
     * Three crystal over three iron is one apiece — the anchor — where only the touching block counting
     * would make it a third however tall the bank was built.
     */
    test("the whole bank feeds the bar, not just the block touching it") {
        val run = everyRunIn(bank(), THE_BANK).single()
        check(run.crystal == THREE_BLOCKS) { "three crystal fed the bar as ${run.crystal}" }
        check(run.force == ONE_TO_ONE) { "three over three came out at ${run.force} rather than the anchor" }
    }

    /**
     * **One bar, one driver**, which is what the election is for: two crystals on one bar both see it, and
     * left alone it would pull twice as hard as anything a builder could read off it.
     */
    test("a bar with a crystal at each end is driven once") {
        val bench = Bench.of {
            crystal(0, 0, 0)
            iron(1, 0, 0); iron(2, 0, 0); iron(3, 0, 0)
            crystal(4, 0, 0)
        }
        val driven = everyRunIn(bench, listOf(BlockPos(0, 0, 0), BlockPos(4, 0, 0)))
        check(driven.size == ONE_RUN) { "a bar with two crystals on it was driven ${driven.size} times" }
    }

    /** And a crystal switched off neither drives nor feeds — the whole of the control surface. */
    test("a crystal that is switched off hands its run to the one that is not") {
        val bench = Bench.of {
            crystal(0, 0, 0)
            iron(1, 0, 0); iron(2, 0, 0)
            crystal(3, 0, 0)
        }
        val ends = listOf(BlockPos(0, 0, 0), BlockPos(3, 0, 0))
        val off = BlockPos(0, 0, 0)
        val driven = ends.flatMap {
            Arcs.runsDrivenFrom(bench, it, IS_IRON, live = { at -> at != off }, worth = IS_CRYSTAL)
        }
        check(driven.size == ONE_RUN) { "switching one end off left ${driven.size} runs rather than one" }
        check(driven.single().crystal == ONE_BLOCK) { "the switched-off crystal still fed the bar" }
    }
}) {
    private companion object {
        private const val ONE_RUN = 1
        private const val ONE_BLOCK = 1
        private const val THREE_BLOCKS = 3
        private const val ONE_TO_ONE = 1.0
        private const val NOT_CRYSTAL = 0

        private val IS_IRON: (BlockState) -> Boolean = { it.`is`(Blocks.IRON_BLOCK) }

        /**
         * **A bank the bar comes off the corner of**, which is the arrangement that matters.
         *
         * The block touching the iron must *not* be the one that sorts lowest, or the wrong election and
         * the right one agree by luck and the check passes either way — which is what the first draft of
         * this did. So the bank runs away from the bar rather than up from it.
         */
        private val THE_BANK = listOf(BlockPos(0, 0, 0), BlockPos(-1, 0, 0), BlockPos(0, -1, 0))

        private fun bank(): Bench = Bench.of {
            crystal(0, 0, 0); crystal(-1, 0, 0); crystal(0, -1, 0)
            iron(1, 0, 0); iron(2, 0, 0); iron(3, 0, 0)
        }

        /** Diamond stands in for arc crystal, worth the one an uncharged block is worth. */
        private val IS_CRYSTAL: (BlockState) -> Int =
            { if (it.`is`(Blocks.DIAMOND_BLOCK)) ONE_BLOCK else NOT_CRYSTAL }

        /** Every run any of [crystals] drives, which is what the machinery actually asks each turn. */
        private fun everyRunIn(bench: Bench, crystals: List<BlockPos>): List<Arcs.Run> =
            crystals.flatMap { Arcs.runsDrivenFrom(bench, it, IS_IRON, worth = IS_CRYSTAL) }
    }

    /** A handful of blocks in the void, which is all any of this needs to be asked of. */
    private class Bench(private val blocks: Map<BlockPos, BlockState>) : BlockGetter {
        override fun getBlockEntity(pos: BlockPos): BlockEntity? = null

        override fun getBlockState(pos: BlockPos): BlockState =
            blocks[pos] ?: Blocks.AIR.defaultBlockState()

        override fun getFluidState(pos: BlockPos): FluidState = Fluids.EMPTY.defaultFluidState()

        override fun getHeight(): Int = A_WORLD_TALL

        override fun getMinY(): Int = A_WORLD_BOTTOM

        class Laying {
            val blocks = mutableMapOf<BlockPos, BlockState>()

            fun crystal(x: Int, y: Int, z: Int) {
                blocks[BlockPos(x, y, z)] = Blocks.DIAMOND_BLOCK.defaultBlockState()
            }

            fun iron(x: Int, y: Int, z: Int) {
                blocks[BlockPos(x, y, z)] = Blocks.IRON_BLOCK.defaultBlockState()
            }
        }

        companion object {
            const val A_WORLD_TALL = 384
            const val A_WORLD_BOTTOM = -64

            fun of(laying: Laying.() -> Unit): Bench = Bench(Laying().apply(laying).blocks)
        }
    }
}
