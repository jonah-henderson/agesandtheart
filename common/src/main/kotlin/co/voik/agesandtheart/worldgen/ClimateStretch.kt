package co.voik.agesandtheart.worldgen

import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.NoiseRouter
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction
import java.util.IdentityHashMap

/**
 * Vanilla's overworld router with its climate read wider — **vanilla's own large biomes, at any size**.
 *
 * Vanilla's large-biomes world is the same router with continentalness, erosion, temperature and vegetation each
 * two octaves lower, which is four times as wide and can only ever be a power of two. The same four noises are read
 * at a horizontal scale that takes any value, so dividing it by the stretch does what the octaves do at whatever
 * size a book asks for. The land is shaped from those noises and the biomes are chosen from them, so the two grow
 * together. Ridges are left alone, as vanilla leaves them.
 *
 * **The aquifers read the land too**, outside the router: where their surface is left unstretched, they drain the
 * stretched seas they no longer think are there.
 */
object ClimateStretch {

    /** [router] with its climate noises read [stretch] times wider, or [router] itself where it is not stretched. */
    fun of(router: NoiseRouter, stretch: Double): NoiseRouter {
        if (stretch == UNSTRETCHED) return router
        val widened = Widening(stretch)
        return NoiseRouter(
            widened.rewrite(router.temperature()),
            widened.rewrite(router.vegetation()),
            widened.rewrite(router.continents()),
            widened.rewrite(router.erosion()),
            widened.rewrite(router.depth()),
            widened.rewrite(router.ridges()),
            widened.rewrite(router.chunkSurfaceLevel()),
            widened.rewrite(router.finalDensity()),
        )
    }

    /** [aquifers] reading the land [of] makes when stretched as much. */
    fun of(aquifers: Aquifer.Config, stretch: Double): Aquifer.Config {
        if (stretch == UNSTRETCHED) return aquifers
        val widened = Widening(stretch)
        return Aquifer.Config(
            widened.rewrite(aquifers.barrierNoise()),
            widened.rewrite(aquifers.fluidLevelFloodednessNoise()),
            widened.rewrite(aquifers.fluidLevelSpreadNoise()),
            widened.rewrite(aquifers.lavaNoise()),
            widened.rewrite(aquifers.exclusion()),
            widened.rewrite(aquifers.surfaceLevel()),
        )
    }

    /**
     * Rewrites a function and everything under it, rescaling each climate noise. Remembered by identity, since the
     * router reaches the same named functions many times over and each should be rebuilt once.
     *
     * **Only a node that was a climate noise is widened.** A named function rewrites to what it names, already
     * rewritten, so asking of the result instead widened every named climate noise twice.
     */
    private class Widening(private val stretch: Double) : DfRewriteRule {
        private val rebuilt = IdentityHashMap<DensityFunction, DensityFunction>()

        override fun rewrite(function: DensityFunction): DensityFunction {
            rebuilt[function]?.let { return it }
            val beneath = function.rewriteChildren(this)
            val wasClimate = function is NoiseFunction && isClimate(function)
            val result = if (wasClimate && beneath is NoiseFunction) widened(beneath) else beneath
            rebuilt[function] = result
            return result
        }

        private fun widened(noise: NoiseFunction) =
            NoiseFunction(noise.noise(), noise.xzScale() / stretch, noise.yScale(), noise.shiftX(), noise.shiftY(), noise.shiftZ())

        private fun isClimate(noise: NoiseFunction): Boolean =
            noise.noise().unwrapKey().map { it.identifier().toString() in CLIMATE_NOISES }.orElse(false)
    }

    /** What vanilla's large biomes reads two octaves lower, in both its sizes. */
    private val CLIMATE_NOISES = setOf("continentalness", "erosion", "temperature", "vegetation")
        .flatMap { listOf("minecraft:$it", "minecraft:${it}_large") }
        .toSet()

    private const val UNSTRETCHED = 1.0
}
