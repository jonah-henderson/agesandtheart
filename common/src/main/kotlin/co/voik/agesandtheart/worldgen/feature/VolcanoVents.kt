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
import kotlin.math.roundToInt

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
     * **A cone of tubes rising through the lake, not a disc sunk in its floor** (Jonah, 2026-09-09).
     *
     * How often a volcano throws is how many tubes it has, because every one of them is visited on its
     * own — so a vent that climbs most of the way to the surface throws several times a second where a
     * skin of one on the floor threw once in ten. Tapering it means the count comes from the base rather
     * than from a chimney, and what stands under the lava reads as a plug rather than a pillar.
     *
     * It is laid into whatever is already there, rock or the crater's own lava, and stops [LAVA_OVER_THE_VENT]
     * short of the surface so the mass stays drowned — a tube in open air over the lake would be a chimney
     * you could stand on, and the whole force of a caldera comes of it erupting from under its own lake.
     *
     * Columns are filtered once by their own floor: well above the middle's is the crater wall, and tubes
     * up a wall are a seam running out of a hillside rather than a vent under a lake.
     */
    private fun seat(level: WorldGenLevel, land: TerrainField, middle: BlockPos, random: RandomSource): Boolean {
        val floor = surfaceAt(land, middle.x, middle.z)
        val base = NARROWEST_VENT + random.nextInt(WIDEST_VENT - NARROWEST_VENT + 1)
        val crown = middle.y - LAVA_OVER_THE_VENT
        val climb = (crown - floor).coerceAtLeast(ONE)
        val columns = discOf(base).filter { (offsetX, offsetZ) ->
            abs(surfaceAt(land, middle.x + offsetX, middle.z + offsetZ) - floor) <= FLOOR_RELIEF
        }
        var seatedAnything = false
        for (y in floor - ROOTED..crown) {
            val reach = reachAt(base, (y - floor).coerceAtLeast(0), climb)
            for ((offsetX, offsetZ) in columns) {
                if (offsetX * offsetX + offsetZ * offsetZ > reach * reach) continue
                val at = BlockPos(middle.x + offsetX, y, middle.z + offsetZ)
                val standing = level.getBlockState(at)
                if (!standing.isSolidRender && !standing.`is`(Blocks.LAVA)) continue
                level.setBlock(at, AgeContent.LAVA_TUBE_BLOCK.defaultBlockState(), UPDATE_NONE)
                seatedAnything = true
            }
        }
        return seatedAnything
    }

    /** The cone's width this far up it — full at the floor, down to a point's worth at the crown. */
    private fun reachAt(base: Int, climbed: Int, climb: Int): Int =
        (base - (base - TIP) * climbed.toDouble() / climb).roundToInt().coerceAtLeast(TIP)

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

    /** How wide the cone is at the floor, drawn per crater so two volcanoes are not the same machine. */
    private const val NARROWEST_VENT = 3
    private const val WIDEST_VENT = 4

    /** And how wide at the crown — a point's worth, so the mass is a cone and not a chimney. */
    private const val TIP = 1

    /** How far it carries on under the floor, so a drained crater still has a vent in it. */
    private const val ROOTED = 2

    /** How much lava is left standing over the mass, so it always erupts from under its own lake. */
    private const val LAVA_OVER_THE_VENT = 3

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

    private const val ONE = 1
}
