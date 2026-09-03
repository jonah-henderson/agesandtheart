package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.worldgen.field.Box
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Variation
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.placement.PlacedFeature

/**
 * A shape of one substance, laid without a world under it.
 *
 * The shape being a `TerrainField` is what makes six shapes one feature, and the two things that buys are
 * what is checked here: a pose belongs to the **formation** rather than to each column of it, and resizing
 * grows the description rather than stretching what it built.
 */
@Tags(NEEDS_REGISTRIES)
class FormationCheck : FunSpec({

    beforeSpec { MinecraftRegistries.ensureStoodUp() }

    val stone by lazy { Blocks.STONE.defaultBlockState() }

    /** Nine by four by three, and deliberately not square: a turn has to be visible in it. */
    fun slab() = Box(minX = -4, minY = 0, minZ = -1, maxX = 4, maxY = 3, maxZ = 1)

    fun laidBy(shape: co.voik.agesandtheart.worldgen.field.TerrainField, variation: Variation, pose: Long): Set<BlockPos> {
        val laid = mutableSetOf<BlockPos>()
        Formation.raise(FormationConfiguration(shape, variation, stone), BlockPos.ZERO, pose) { at, _ -> laid += at }
        return laid
    }

    test("a shape is laid where the shape is") {
        val laid = laidBy(slab(), Variation.NONE, pose = 1L)
        val expected = 9 * 4 * 3
        check(laid.size == expected) { "a 9x4x3 box laid ${laid.size} blocks, not $expected" }
        check(BlockPos(4, 3, 1) in laid && BlockPos(-4, 0, -1) in laid) { "its corners are missing" }
        check(BlockPos(5, 0, 0) !in laid) { "it laid a block outside itself" }
    }

    /**
     * **The pose is the formation's, not the column's.** [Variation.sample] takes its turn from the random
     * it is handed, so a source shared across columns would rotate each one differently and lay a smear.
     * A quarter turn of an oblong is still an oblong of the same size, and a smear is not.
     */
    test("every column of one formation is turned the same way") {
        val quarterTurns = Variation(yawSteps = 4, minScale = 1.0, maxScale = 1.0, scaleSteps = 1, pivotY = 0)
        val sizes = (1L..40L).map { pose -> laidBy(slab(), quarterTurns, pose).size }.distinct()
        check(sizes == listOf(9 * 4 * 3)) {
            "a quarter-turned 9x4x3 box came out ${sizes.sorted()} blocks — a formation posed per column " +
                "rather than as one thing"
        }
    }

    test("a turn actually turns it") {
        val quarterTurns = Variation(yawSteps = 4, minScale = 1.0, maxScale = 1.0, scaleSteps = 1, pivotY = 0)
        val shapes = (1L..40L).map { pose -> laidBy(slab(), quarterTurns, pose) }.distinct()
        check(shapes.size > 1) { "every pose laid the same blocks, so nothing is being turned" }
    }

    /**
     * Resizing a field resizes the *description*, so a bigger formation has more courses of blocks rather
     * than a stretched staircase — which is the whole reason `Features.SIZE` can reach this at all.
     */
    test("a bigger one is bigger") {
        val ordinary = laidBy(slab(), Variation.NONE, pose = 1L).size
        val doubled = laidBy(slab().resized(2.0, 0), Variation.NONE, pose = 1L).size
        check(doubled > ordinary * 4) { "doubled, a $ordinary-block shape came out $doubled" }
    }

    /** A slab is solid to the horizon, so laying one would be laying every block in the world. */
    test("an unbounded shape is not a formation") {
        val laid = laidBy(Slab(lowY = 0, highY = 4), Variation.NONE, pose = 1L)
        check(laid.isEmpty()) { "a slab laid ${laid.size} blocks and should have declined" }
    }

    /** The other half of `gold_block obelisks`: the substance is the only thing a minting replaces. */
    test("minting swaps the substance and nothing else") {
        val shape = slab()
        val pattern = Holder.direct(
            PlacedFeature(
                Holder.direct(ConfiguredFeature(Formation, FormationConfiguration(shape, Variation.NONE, stone))),
                emptyList(),
            ),
        )
        val minted = FeatureShape.mintedFrom(pattern, "minecraft:gold_block")
        val configuration = minted.value().feature().value().config()
        check(configuration is FormationConfiguration) { "a minted formation came back as $configuration" }
        check(configuration.substance == Blocks.GOLD_BLOCK.defaultBlockState()) {
            "it is made of ${configuration.substance} rather than gold"
        }
        check(configuration.shape == shape) { "minting changed the shape as well as the substance" }
    }
})
