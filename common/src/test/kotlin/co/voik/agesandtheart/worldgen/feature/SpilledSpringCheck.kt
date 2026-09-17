package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * **What a spring given something that cannot run actually leaves behind.**
 *
 * The claim and the configuration are `MintingCheck`'s; this is the shape itself, because the shape is the
 * whole reason the feature exists. A run of blocks straight down under the source was the first attempt
 * and reads as nothing in particular — flowing is *lateral first*, out through whatever opening the rock
 * left, and only then down (Jonah, 2026-08-26).
 *
 * Placed against a hand-built column of blocks rather than a world: the feature reads `isEmptyBlock` and
 * writes `setBlock`, and both are answerable by a stub that knows what is solid.
 */
@Tags(NEEDS_REGISTRIES)
class SpilledSpringCheck : FunSpec({

    // `by lazy`, because Kotest builds a spec to discover its tests and `Blocks` needs the registries
    // standing — a fixture in the constructor is read before any test has asked for them.
    val substance by lazy { Blocks.GOLD_BLOCK.defaultBlockState() }

    /**
     * Solid rock with one open column beside it, which is a cave wall as far as this feature can tell.
     * The source is laid at [source]; everything with `x > source.x` is open.
     */
    fun wallWithAnOpeningEastOf(source: BlockPos): FakeChunk = FakeChunk { at ->
        if (at.x > source.x) Blocks.AIR.defaultBlockState() else Blocks.STONE.defaultBlockState()
    }

    fun solidRock(): FakeChunk = FakeChunk { Blocks.STONE.defaultBlockState() }

    fun openAir(): FakeChunk = FakeChunk { Blocks.AIR.defaultBlockState() }

    test("it comes out sideways before it falls") {
        val source = BlockPos(0, 40, 0)
        val world = wallWithAnOpeningEastOf(source)

        check(world.spill(source, substance)) { "nothing was laid against a wall with an opening beside it" }

        check(world.at(source) == substance) { "the source is not in the wall: ${world.at(source)}" }
        val out = source.relative(Direction.EAST)
        check(world.at(out) == substance) { "nothing came out into the opening: ${world.at(out)}" }
        check(world.at(out.below()) == substance) { "what came out did not fall: ${world.at(out.below())}" }

        // And it is the *opening* it went into, never the rock: a spill that wrote west would be writing
        // into stone, which is the failure a "something was placed" check would pass straight over.
        check(world.at(source.relative(Direction.WEST)) != substance) { "it spilled into the rock" }
    }

    /** And what falls, stops — this is a thing that set, not a column to the floor. */
    test("what came out falls a little and sets") {
        val source = BlockPos(0, 40, 0)
        val world = wallWithAnOpeningEastOf(source)
        world.spill(source, substance)

        val out = source.relative(Direction.EAST)
        val fallen = generateSequence(out.below()) { it.below() }.takeWhile { world.at(it) == substance }.count()
        check(fallen in 1..3) { "what came out fell $fallen blocks, and a spill is a block or two" }
    }

    /**
     * **A source with nowhere to go leaves nothing**, which is what keeps these out of unbroken rock. The
     * spring's placement puts them at any height, so most land buried, and a spill that laid a block there
     * would be a gold block in the middle of the stone with no story attached to it.
     */
    test("a source walled in on every side leaves nothing at all") {
        val source = BlockPos(0, 40, 0)
        val solid = solidRock()

        check(!solid.spill(source, substance)) { "a spill laid something inside unbroken rock" }
        check(solid.written.isEmpty()) { "a refused spill still wrote ${solid.written}" }
    }

    /** And one hanging in open air is not a spring at all — there is no wall for it to have come out of. */
    test("a source in open air leaves nothing either") {
        val source = BlockPos(0, 40, 0)
        val air = openAir()

        check(!air.spill(source, substance)) { "a spill was laid in mid-air, with no wall to come out of" }
        check(air.written.isEmpty()) { "a refused spill still wrote ${air.written}" }
    }
})

/**
 * Just enough world for a feature that only asks what is empty and only writes blocks.
 *
 * A `WorldGenLevel` has a hundred members and this feature reads two of them, so the alternative was
 * booting a chunk generator to answer `isEmptyBlock`.
 */
private class FakeChunk(private val standing: (BlockPos) -> BlockState) {
    val written = mutableMapOf<BlockPos, BlockState>()

    fun at(position: BlockPos): BlockState = written[position] ?: standing(position)

    fun spill(source: BlockPos, substance: BlockState): Boolean = SpilledSpring.spill(
        source,
        substance,
        isOpen = { at(it).isAir },
        random = RandomSource.create(PLACED_SEED),
    ) { position, state -> written[position] = state }
}

/** One seed, so a shuffled set of directions is the same set every run. */
private const val PLACED_SEED = 4242L
