package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.util.KeyDispatchDataCodec
import net.minecraft.world.level.levelgen.DensityFunction
import java.lang.Math.floorDiv

/**
 * **How high a column's ground stands before its caves were cut** — the router slot vanilla's surface system
 * reads as `NoiseChunk.preliminarySurfaceLevel`, answered from the field tree.
 *
 * The slot is a height: `NoiseChunk` floors whatever it returns straight into a Y. Vanilla fills it with
 * `DensityFunctions.findTopSurface`, which steps down from an upper bound one [cellHeight] at a time and
 * returns the first height it samples at which a density without caves is solid. This is the same answer
 * from rock we already have: the higher of the cut rock's top and the uncut rock's, stepped down to the cell
 * the way `findTopSurface` samples it, and [lowestY] where the column holds no rock at all.
 *
 * What reads it is vanilla's surface system: its minimum surface level, which is how far down a frozen
 * ocean's iceberg may reach, and `abovePreliminarySurface`. Our own surface rules ask the column itself
 * through `NearTheSurface`.
 *
 * **It is not a route back to density functions for terrain.** `notes/terrain-architecture.md` records why
 * that was declined and none of it has changed. What makes this one sound is that it is asked a handful of
 * times per chunk, at quart columns, and memoised by the caller.
 */
class PreliminarySurface(
    private val field: TerrainField,
    private val uncut: TerrainField?,
    private val lowestY: Int,
    private val highestY: Int,
    private val cellHeight: Int,
) : DensityFunction.SimpleFunction {

    override fun compute(context: DensityFunction.FunctionContext): Double {
        val cutTop = field.columnSpans(context.blockX(), context.blockZ()).highestSolidY ?: lowestY
        val uncutTop = uncut?.columnSpans(context.blockX(), context.blockZ())?.highestSolidY ?: lowestY
        val top = maxOf(cutTop, uncutTop)
        return maxOf(lowestY, floorDiv(top, cellHeight) * cellHeight).toDouble()
    }

    override fun minValue(): Double = lowestY.toDouble()

    override fun maxValue(): Double = highestY.toDouble()

    /**
     * Never reached. An Age's generator serialises through `AgeChunkGenerator.CODEC`, which writes the field
     * tree and the few settings an Age chooses — the noise settings this sits in are **rebuilt** from those
     * on load rather than written, so nothing ever asks this to encode itself.
     */
    override fun codec(): KeyDispatchDataCodec<out DensityFunction> =
        error("PreliminarySurface is built for a generator's own router and is never serialised")
}
