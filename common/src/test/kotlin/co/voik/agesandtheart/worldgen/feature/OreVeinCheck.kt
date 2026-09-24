package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks

/**
 * A vein laid over a world of solid stone, so what it lays is the vein and nothing about the ground.
 *
 * The two shapes are vanilla's iron vein and its copper one, and they differ only in where they stand and
 * what they are strung through — so over the same ground they must lay about as much as each other.
 */
@Tags(NEEDS_REGISTRIES)
class OreVeinCheck : FunSpec({

    val iron by lazy {
        OreVein(
            ore = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
            rawOre = Blocks.RAW_IRON_BLOCK.defaultBlockState(),
            filler = Blocks.TUFF.defaultBlockState(),
            rawOreChance = 0.02f,
            minY = -60,
            maxY = -8,
            cuts = BlockTags.BASE_STONE_OVERWORLD,
            // Offline, no tag is bound, so the stone this ground is made of is named outright.
            alsoCuts = listOf(Blocks.STONE),
            seed = 318520977L,
        )
    }

    /** How many blocks [vein] lays across a square of chunks of stone. */
    fun laidBy(vein: OreVein): Int {
        val stone = Blocks.STONE.defaultBlockState()
        var laid = 0
        for (chunkX in 0..<CHUNKS_ACROSS) {
            for (chunkZ in 0..<CHUNKS_ACROSS) {
                laid += vein.lay(WORLD_SEED, ChunkPos(chunkX, chunkZ), vein.minY, vein.maxY, { _: BlockPos -> stone }) { _, _ -> }
            }
        }
        return laid
    }

    test("the copper shape lays about as much vein as the iron shape") {
        val deep = laidBy(iron)
        val shallow = laidBy(iron.inTheCopperBand())
        check(deep > 0) { "the iron shape laid nothing over ${CHUNKS_ACROSS * CHUNKS_ACROSS} chunks of stone" }
        // Only over a wide square: the coarse noise is about 170 blocks a wavelength, so over a few chunks
        // one band can lay a quarter of what the other does and nothing be wrong.
        check(shallow > deep / 2 && shallow < deep * 2) { "the iron shape laid $deep and the copper shape $shallow" }
    }

    test("more abundance lays more vein") {
        val ordinary = laidBy(iron)
        val teeming = laidBy(iron.copy(abundance = 4.0))
        check(teeming > ordinary * 2) { "four times the abundance laid $teeming against $ordinary" }
    }
}) {
    private companion object {
        const val WORLD_SEED = 7L
        const val CHUNKS_ACROSS = 16
    }
}
