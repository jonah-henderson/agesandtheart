package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.MapCodec
import net.minecraft.util.Interval
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext

/**
 * **How high a column's ground stands before its caves were cut** — the router slot vanilla's surface system
 * reads as `chunkSurfaceLevel`, answered from the field tree.
 *
 * The slot is a height: what reads it floors whatever it returns straight into a Y. Vanilla fills it by
 * stepping down from an upper bound and returning the first height at which a density without caves is
 * solid, then interpolating that across a sixteen-block cell. This is the same answer from rock we already
 * have: the higher of the cut rock's top and the uncut rock's, and [lowestY] where the column holds no rock
 * at all.
 *
 * **Answered exactly, where it used to be stepped to an eight-block cell.** That step mirrored the old
 * `findTopSurface`, which sampled down a cell at a time. 26.3's own `chunk_surface_level` interpolates its
 * preliminary surface with `cell_size_y: 1` — no vertical step at all — so ours was rounding off a
 * precision vanilla now keeps, for a resemblance that no longer exists.
 *
 * What reads it is vanilla's surface system: its minimum surface level, which is how far down a frozen
 * ocean's iceberg may reach, and `abovePreliminarySurface`. Our own surface rules ask the column itself
 * through `NearTheSurface`.
 *
 * **It is not a route back to density functions for terrain.** `notes/terrain-architecture.md` records why
 * that was declined and none of it has changed. What makes this one sound is that it is asked a handful of
 * times per chunk, at quart columns, and memoised by the caller.
 *
 * **The one of ours that is still a `DensityFunction`.** 26.3 splits a function from the sampler it
 * compiles to, and the other two of ours only ever needed the sampler — this one sits in a `NoiseRouter`,
 * which holds functions, so it keeps both halves.
 */
class PreliminarySurface(
    private val field: TerrainField,
    private val uncut: TerrainField?,
    private val lowestY: Int,
    private val highestY: Int,
) : DensityFunction, DensitySampler {

    override fun compileSampler(context: DensityFunction.CompileContext): DensitySampler = this

    override fun sampleValue(context: SamplerContext, blockX: Int, blockY: Int, blockZ: Int): Float {
        val cutTop = field.columnSpans(blockX, blockZ).highestSolidY ?: lowestY
        val uncutTop = uncut?.columnSpans(blockX, blockZ)?.highestSolidY ?: lowestY
        return maxOf(lowestY, maxOf(cutTop, uncutTop)).toFloat()
    }

    override fun sampleVolume(context: SamplerContext, into: DensityBuffer, over: DensityVolume) =
        DensitySampler.sampleVolumeNaive(context, into, over, this)

    /** Nothing beneath it to rewrite — the field tree is ours and not a density function. */
    override fun rewriteChildren(rule: net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule) = this

    override fun range(): Interval = Interval.of(lowestY.toFloat(), highestY.toFloat())

    /** A height per column: the same answer at every Y, so Y is not one of its axes. */
    override fun domainAxes(): Int = DensityFunction.AXIS_X or DensityFunction.AXIS_Z

    /**
     * Never reached. An Age's generator serialises through `AgeChunkGenerator.CODEC`, which writes the field
     * tree and the few settings an Age chooses — the noise settings this sits in are **rebuilt** from those
     * on load rather than written, so nothing ever asks this to encode itself.
     */
    override fun codec(): MapCodec<out DensityFunction> =
        error("PreliminarySurface is built for a generator's own router and is never serialised")
}
