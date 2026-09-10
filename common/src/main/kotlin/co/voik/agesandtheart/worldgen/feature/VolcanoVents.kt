package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import kotlin.math.abs

/**
 * Lava tubes, seated under the lava a volcano's crater arrived full of (design §7.1.2).
 *
 * **A vent goes where the Age's own lava stands**, which is a fact the shape already holds rather than a
 * relationship a feature has to infer. It was inferred for a while — low, with higher ground around, high
 * above the world — and that read a crater fairly while a crater floor was rumpled and its lowest column
 * sat somewhere near its middle. Cutting the caldera flat so it could hold a level lake took the ground
 * out from under it: with every floor column tied, "the lowest" became "the westernmost", which is the
 * one place in a crater that nothing rings.
 *
 * So a crater is not looked for at all now. The lake **is** the crater, and the chunk that seats the vent
 * is the one holding the lake's middle — a question every chunk over that lake answers the same way, so
 * exactly one of them says yes without any of them comparing itself against the others.
 */
object VolcanoVents : Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val generator = context.chunkGenerator() as? AgeChunkGenerator ?: return false
        val land = (generator.rock as? AgeRock.Ours)?.landform ?: return false
        val lakes = moltenIn(generator) ?: return false
        val origin = context.origin()
        val anywhere = someLavaIn(lakes, origin) ?: return false
        val middle = middleOfTheLakeAt(lakes, anywhere)
        if (!inside(origin, middle)) return false
        return seat(context.level(), land, middle, context.random())
    }

    /**
     * The body of lava this Age's shape carries, or null where it carries none.
     *
     * Asked of the generator rather than named here, so the vent and the lake it sits under read one
     * field and cannot come to describe two different craters.
     */
    private fun moltenIn(generator: AgeChunkGenerator): TerrainField? =
        generator.seaFill.carried.firstOrNull { it.fluid.`is`(Blocks.LAVA) }?.where

    /**
     * Any column of this chunk with lava standing over it, on the [STRIDE] lattice.
     *
     * The cheap question that most chunks in an Age answer no to, so nothing below it is paid for except
     * over a crater.
     */
    private fun someLavaIn(lakes: TerrainField, origin: BlockPos): BlockPos? {
        for (offsetX in 0..<CHUNK step STRIDE) {
            for (offsetZ in 0..<CHUNK step STRIDE) {
                val x = origin.x + offsetX
                val z = origin.z + offsetZ
                val surface = surfaceOfLava(lakes, x, z) ?: continue
                return BlockPos(x, surface, z)
            }
        }
        return null
    }

    /**
     * The middle of the lake [from] stands in — its bounding box, halved.
     *
     * **Columns are gathered by their surface height, and that is what makes one lake one lake.** A
     * crater's lava is level to the block, so a height is an exact name for a body of it and no flood
     * fill is needed to tell one from the crater over the ridge, which stands at its own.
     *
     * Every chunk over one lake reads the same box and so computes the same middle, which is what lets
     * the election be "does that middle fall inside me" — a question with exactly one yes.
     */
    private fun middleOfTheLakeAt(lakes: TerrainField, from: BlockPos): BlockPos {
        var leastX = from.x
        var mostX = from.x
        var leastZ = from.z
        var mostZ = from.z
        for (stepX in -LAKE_STRIDES..LAKE_STRIDES) {
            for (stepZ in -LAKE_STRIDES..LAKE_STRIDES) {
                val x = from.x + stepX * STRIDE
                val z = from.z + stepZ * STRIDE
                if (surfaceOfLava(lakes, x, z) != from.y) continue
                leastX = minOf(leastX, x)
                mostX = maxOf(mostX, x)
                leastZ = minOf(leastZ, z)
                mostZ = maxOf(mostZ, z)
            }
        }
        return BlockPos((leastX + mostX) / 2, from.y, (leastZ + mostZ) / 2)
    }

    private fun inside(origin: BlockPos, at: BlockPos): Boolean =
        at.x - origin.x in 0..<CHUNK && at.z - origin.z in 0..<CHUNK

    /** How high the lava stands over this column, or null where none does. */
    private fun surfaceOfLava(lakes: TerrainField, x: Int, z: Int): Int? =
        lakes.columnSpans(x, z).highestSolidY

    /**
     * The top of the rock in this column, or [NO_ROCK] where the field leaves the column empty.
     *
     * The **field** rather than the world, which is what lets a vent read a crater floor tens of blocks
     * outside the chunk being decorated: a feature may only write a chunk past its own, but a field is a
     * pure function of a column and answers anywhere for the cost of the arithmetic.
     */
    private fun surfaceAt(land: TerrainField, x: Int, z: Int): Int =
        land.columnSpans(x, z).ranges.lastOrNull()?.last ?: NO_ROCK

    /**
     * A short run of tubes in the floor, which is what makes a mass rather than a single vent.
     *
     * **Each column is sunk from its own surface**, not from one height read at the middle. A caldera's
     * floor is cut flat so this costs nothing there, but the rock under a crater is whatever the Age put
     * there and a tube left buried is a plugged one, which wells nothing and throws nothing.
     */
    private fun seat(level: WorldGenLevel, land: TerrainField, middle: BlockPos, random: RandomSource): Boolean {
        val floor = surfaceAt(land, middle.x, middle.z)
        val reach = NARROWEST_VENT + random.nextInt(WIDEST_VENT - NARROWEST_VENT + 1)
        val depth = SHALLOWEST_VENT + random.nextInt(DEEPEST_VENT - SHALLOWEST_VENT + 1)
        var seatedAnything = false
        for ((offsetX, offsetZ) in discOf(reach)) {
            val x = middle.x + offsetX
            val z = middle.z + offsetZ
            val surface = surfaceAt(land, x, z)
            // Well above the floor is the crater wall, and tubes up a wall are a seam running out of a
            // hillside rather than a vent under a lake.
            if (abs(surface - floor) > FLOOR_RELIEF) continue
            for (course in 0..<depth) {
                val at = BlockPos(x, surface - course, z)
                if (!level.getBlockState(at).isSolidRender) continue
                level.setBlock(at, AgeContent.LAVA_TUBE_BLOCK.defaultBlockState(), UPDATE_NONE)
                seatedAnything = true
            }
        }
        return seatedAnything
    }

    /**
     * The columns within [reach] of the middle.
     *
     * **Width is what the mechanic needs, twice over.** A mass buys both the force behind a bomb and how
     * often one is thrown, and a caldera's vent is meant to be at the top of both rather than somewhere
     * on the ramp — which is what leaves the ramp itself to the clusters underground.
     */
    private fun discOf(reach: Int): List<Pair<Int, Int>> =
        (-reach..reach).flatMap { x -> (-reach..reach).map { z -> x to z } }
            .filter { (x, z) -> x * x + z * z <= reach * reach }

    private const val NARROWEST_VENT = 3
    private const val WIDEST_VENT = 3

    /** Drawn per crater, so two volcanoes side by side are not the same machine. */
    private const val SHALLOWEST_VENT = 3
    private const val DEEPEST_VENT = 5

    /** How far a vent column may sit off the floor at the middle before it counts as the crater wall. */
    private const val FLOOR_RELIEF = 3

    /**
     * How far the search for a lake's edges reaches, in [STRIDE]s — **wider than the widest lake**, or a
     * crater's box comes back clipped, its middle moves with whichever chunk asked, and no chunk holds it.
     *
     * The broadest crater here is a shield's at its largest pose, a little under ninety blocks across, so
     * twenty-four strides reaches its far side from a column on the near one with room over.
     */
    private const val LAKE_STRIDES = 24

    private const val CHUNK = 16

    /** Coarse: a crater is tens of blocks across, so every fourth column finds it. */
    private const val STRIDE = 4

    /** Below the world, so an empty column can never be mistaken for a crater floor. */
    private const val NO_ROCK = Int.MIN_VALUE / 2

    private const val UPDATE_NONE = 2
}
