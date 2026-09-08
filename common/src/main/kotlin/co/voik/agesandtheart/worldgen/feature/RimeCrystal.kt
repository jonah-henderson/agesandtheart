package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.RimeColour
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration

/**
 * Rime crystals, which grow only where a cliff is sheer (design §7.1.2, `SheerFace`).
 *
 * **A feature rather than a placement**, and for two reasons that are both about what vanilla cannot say.
 * A cliff is a *relationship* between a column and its neighbours, which `BlockPredicateFilter` can only
 * express as sixty-odd offset predicates in four rotations; and the frequency has to *rise* with height,
 * which no vanilla height provider does — they are uniform, trapezoid, or biased toward the bottom.
 *
 * **It scans the chunk rather than being scattered over it.** A sheer face stands at well under one per
 * cent of vanilla's columns, so sampling at random would spend nearly every attempt on flat ground and
 * make the yield a matter of luck rather than of terrain. One pass over the chunk's own columns puts a
 * crystal wherever the ground actually earns one, which is what `/age cliffs` measures and what its
 * numbers were tuned against.
 */
object RimeCrystal : Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val random = context.random()
        val origin = context.origin()
        var grew = false
        for (offsetX in 0..<CHUNK) {
            for (offsetZ in 0..<CHUNK) {
                val x = origin.x + offsetX
                val z = origin.z + offsetZ
                val face = sheerFaceAt(level, x, z) ?: continue
                if (random.nextDouble() >= SheerFace.likelihoodAt(face.y)) continue
                grew = grow(level, face) || grew
            }
        }
        return grew
    }

    /**
     * Where a crystal could grow on the column over [x], [z] — the open block against a sheer face, or null.
     *
     * The test is `SheerFace`'s, asked of the world rather than of a heightmap: a solid block with open air
     * beside it, that air falling [SheerFace.SHEER_BLOCKS] clear and standing
     * [SheerFace.OPEN_ABOVE] clear. A face with a floor close under it is a step, and a face with a ceiling
     * close over it is a crevice; neither is a cliff.
     */
    private fun sheerFaceAt(level: WorldGenLevel, x: Int, z: Int): Standing? {
        val ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1
        val cursor = BlockPos.MutableBlockPos(x, ground, z)
        if (!level.getBlockState(cursor).isSolidRender) return null
        for (way in Direction.Plane.HORIZONTAL) {
            val beside = BlockPos(x + way.stepX, ground, z + way.stepZ)
            if (!level.getBlockState(beside).isAir) continue
            if (!fallsAway(level, beside) || !standsOpen(level, beside)) continue
            // **Facing away from the rock, not at it.** A cluster attaches to the block *opposite* its
            // facing (`AmethystClusterBlock.canSurvive`), so pointing it back at the wall asks the empty
            // air on the far side to hold it up and nothing is ever placed.
            return Standing(beside, way)
        }
        return null
    }

    private fun fallsAway(level: WorldGenLevel, from: BlockPos): Boolean =
        (1..SheerFace.SHEER_BLOCKS).all { level.getBlockState(from.below(it)).isAir }

    private fun standsOpen(level: WorldGenLevel, from: BlockPos): Boolean =
        (1..SheerFace.OPEN_ABOVE).all { level.getBlockState(from.above(it)).isAir }

    /**
     * One crystal, in whatever colour grows around here.
     *
     * **The colour is a fact about the place, not about the crystal** (Jonah, 2026-09-07): [RimeColour]
     * hashes the position down to a coarse cell, so an outcrop comes out one colour and a long range
     * changes every so often. Drawing it per crystal would be confetti, and per Age would put a puzzle
     * needing several colours behind writing several books.
     */
    private fun grow(level: WorldGenLevel, face: Standing): Boolean {
        val colour = RimeColour.around(level.seed, face.at.x, face.at.z)
        val crystal = AgeContent.RIME_CRYSTAL_BLOCKS.getValue(colour).defaultBlockState()
            .setValue(BlockStateProperties.FACING, face.growingFrom)
        if (!crystal.canSurvive(level, face.at)) return false
        level.setBlock(face.at, crystal, Block.UPDATE_CLIENTS)
        return true
    }

    /** An open block against a cliff, and the way a crystal there points — out from the rock. */
    private data class Standing(val at: BlockPos, val growingFrom: Direction) {
        val y: Int get() = at.y
    }

    private const val CHUNK = 16
}
