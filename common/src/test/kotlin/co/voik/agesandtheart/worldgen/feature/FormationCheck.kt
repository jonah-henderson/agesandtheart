package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.worldgen.field.Box
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Variation
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.placement.PlacedFeature

/**
 * A shape of one substance, laid by every chunk it crosses.
 *
 * **The property that matters is that the chunks agree.** A feature may only write inside the chunk it is
 * decorating, so a formation wider than sixteen blocks is laid in pieces by neighbours that never speak to
 * each other — each recomputing where the formation is, which template it drew, and how it was posed. Get
 * any of that wrong and the halves do not meet: a seam, a doubled block, or a smear. Every test here is
 * some form of "lay it from every chunk and see whether one whole thing comes out".
 */
@Tags(NEEDS_REGISTRIES)
class FormationCheck : FunSpec({

    val stone by lazy { Blocks.STONE.defaultBlockState() }

    /** Ground at a flat sixty-four, so what is laid is the shape and nothing about the terrain. */
    val flatGround: (Int, Int) -> Int = { _, _ -> SEA_LEVEL }

    /** Forty-one across and four thick — wider than a chunk, so it has to be laid by nine of them. */
    fun wideSlab(): TerrainField = Box(minX = -20, minY = 0, minZ = -20, maxX = 20, maxY = 3, maxZ = 20)

    /** One formation at exactly the origin: no jitter, kept always, and its neighbours far out of reach. */
    fun oneAtTheOrigin() = Grid(spacing = 512.0, jitter = 0.0, density = Density.uniform())

    fun configured(shape: TerrainField, variation: Variation = Variation.NONE) =
        FormationConfiguration(listOf(shape), variation, oneAtTheOrigin(), seed = 1L, substance = stone)

    /** What every chunk in a square around the origin lays, and how often each block was laid. */
    fun laidAcross(configuration: FormationConfiguration): Map<BlockPos, Int> {
        val laid = mutableMapOf<BlockPos, Int>()
        for (chunkX in -3..3) {
            for (chunkZ in -3..3) {
                Formation.raise(configuration, WORLD_SEED, ChunkPos(chunkX, chunkZ), flatGround) { at, _ ->
                    laid[at] = (laid[at] ?: 0) + 1
                }
            }
        }
        return laid
    }

    test("what the chunks lay between them is the whole shape, once") {
        val laid = laidAcross(configured(wideSlab()))
        val expected = 41 * 41 * 4
        check(laid.size == expected) { "the chunks laid ${laid.size} blocks between them, not $expected" }

        val twice = laid.filterValues { it > 1 }
        check(twice.isEmpty()) { "${twice.size} blocks were laid by more than one chunk, e.g. ${twice.keys.first()}" }

        check(BlockPos(-20, SEA_LEVEL, -20) in laid && BlockPos(20, SEA_LEVEL + 3, 20) in laid) {
            "the far corners are missing, so a chunk declined its share"
        }
        check(BlockPos(21, SEA_LEVEL, 0) !in laid) { "it laid a block outside the shape" }
    }

    /**
     * **The pose has to be the formation's, and every chunk has to draw the same one.** A quarter turn of
     * a square slab is the same slab, so if the chunks disagreed about the turn the count would still come
     * out right — but the *shape* would not, which is what the corners catch.
     */
    test("every chunk poses the formation the same way") {
        val quarterTurns = Variation(yawSteps = 4, minScale = 1.0, maxScale = 1.0, scaleSteps = 1, pivotY = 0)
        val laid = laidAcross(configured(wideSlab(), quarterTurns))
        check(laid.size == 41 * 41 * 4) {
            "a turned slab came out ${laid.size} blocks — the chunks did not agree about the turn"
        }
        check(laid.values.all { it == 1 }) { "a turned slab was laid twice over in places" }
    }

    test("the ground it stands on is where it is laid") {
        val laid = laidAcross(configured(wideSlab()))
        val lowest = laid.keys.minOf { it.y }
        check(lowest == SEA_LEVEL) { "the shape's base landed at $lowest rather than on the ground at $SEA_LEVEL" }
    }

    /** A slab is solid to the horizon, so no cell scan could ever find every copy covering a column. */
    test("an unbounded shape is not a formation") {
        val laid = laidAcross(configured(Slab(lowY = 0, highY = 4)))
        check(laid.isEmpty()) { "an unbounded shape laid ${laid.size} blocks and should have declined" }
    }

    test("a bigger one is bigger") {
        val ordinary = laidAcross(configured(wideSlab())).size
        val doubled = laidAcross(configured(wideSlab().resized(2.0, 0))).size
        check(doubled > ordinary * 4) { "doubled, a $ordinary-block shape came out $doubled" }
    }

    /** The other half of `gold_block obelisks`: the substance is the only thing a minting replaces. */
    test("minting swaps the substance and nothing else") {
        val configuration = configured(wideSlab())
        val pattern = Holder.direct(
            PlacedFeature(Holder.direct(ConfiguredFeature(Formation, configuration)), emptyList()),
        )
        val minted = FeatureShape.mintedFrom(pattern, "minecraft:gold_block")
        val rebuilt = minted.value().feature().value().config()
        check(rebuilt is FormationConfiguration) { "a minted formation came back as $rebuilt" }
        check(rebuilt.substance == Blocks.GOLD_BLOCK.defaultBlockState()) {
            "it is made of ${rebuilt.substance} rather than gold"
        }
        check(rebuilt.shapes == configuration.shapes) { "minting changed the shape as well as the substance" }
    }
}) {
    private companion object {
        const val SEA_LEVEL = 64
        const val WORLD_SEED = 4242L
    }
}
