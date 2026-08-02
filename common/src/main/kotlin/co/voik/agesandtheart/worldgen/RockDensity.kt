package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.util.KeyDispatchDataCodec
import net.minecraft.world.level.levelgen.DensityFunction

/**
 * Where the rock is, said in the one language vanilla's surface system will listen to — **the whole of what
 * it takes to stop grass growing on a cave floor.**
 *
 * Vanilla keeps its cave floors bare by wrapping its entire surface tree in `abovePreliminarySurface`, and
 * that condition reads exactly one thing: `NoiseChunk.preliminarySurfaceLevel`, which walks a column from the
 * top down in cell-height steps asking `initialDensityWithoutJaggedness` where it first goes solid. A field
 * Age has no density to answer with, so that slot was left at zero, the condition became meaningless, and
 * `Palette` had to drop the wrapper — which left *every* rock-to-air boundary in the world dressed as a
 * surface, cave ceilings and floors included.
 *
 * So this answers it. Not a density in any real sense — a sign, and that is all the walk reads: the first
 * height at which it reports rock is the surface, and vanilla's own condition takes it from there.
 *
 * **It is not a route back to density functions for terrain.** `notes/terrain-architecture.md` records why
 * that was declined and none of it has changed: the contract is scalar-per-point where the toolkit's is
 * spans, and the caching lives in `NoiseChunk` rather than in the tree. What makes this one sound is that it
 * is asked a handful of times per chunk, at quart columns and cell heights, and memoised by the caller.
 */
class RockDensity(private val field: TerrainField) : DensityFunction.SimpleFunction {

    override fun compute(context: DensityFunction.FunctionContext): Double {
        val standing = field.columnSpans(context.blockX(), context.blockZ()).contains(context.blockY())
        return if (standing) IN_ROCK else IN_AIR
    }

    override fun minValue(): Double = IN_AIR

    override fun maxValue(): Double = IN_ROCK

    /**
     * Never reached. An Age's generator serialises through `AgeChunkGenerator.CODEC`, which writes the field
     * tree and the few settings an Age chooses — the noise settings this sits in are **rebuilt** from those
     * on load rather than written, so nothing ever asks this to encode itself.
     */
    override fun codec(): KeyDispatchDataCodec<out DensityFunction> =
        error("RockDensity is built for a generator's own router and is never serialised")

    private companion object {
        /**
         * Vanilla's walk keeps descending until it reads over `0.390625`, so the two answers only have to
         * straddle that. Plain ±1 rather than anything scaled: there is no density here to be faithful to.
         */
        const val IN_ROCK = 1.0
        const val IN_AIR = -1.0
    }
}
