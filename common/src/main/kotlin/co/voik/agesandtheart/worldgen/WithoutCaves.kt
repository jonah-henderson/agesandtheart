package co.voik.agesandtheart.worldgen

import net.minecraft.world.level.levelgen.NoiseRouter
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction
import java.util.IdentityHashMap

/**
 * Vanilla's overworld router with its noise caves taken out, for an Age whose book asked for an underground of
 * its own, or for none.
 *
 * The overworld's final density cuts its caves in two places, and both are named: a range choice on
 * `sloped_cheese`, which keeps the solid ground outright and the cheese, spaghetti and pillars below it; and a last
 * `min` with `caves/noodle`. The choice becomes `sloped_cheese` itself and the noodles a density no rock is under,
 * which is the terrain with no caves in it. A router naming neither, the nether's or the End's, comes back as it was.
 */
object WithoutCaves {

    fun of(router: NoiseRouter): NoiseRouter {
        val uncaved = Uncaving()
        val finalDensity = uncaved.rewrite(router.finalDensity())
        return NoiseRouter(
            router.temperature(),
            router.vegetation(),
            router.continents(),
            router.erosion(),
            router.depth(),
            router.ridges(),
            router.chunkSurfaceLevel(),
            finalDensity,
        )
    }

    /** Rewrites a function and everything under it, remembered by identity as the router reaches one many times. */
    private class Uncaving : DfRewriteRule {
        private val rebuilt = IdentityHashMap<DensityFunction, DensityFunction>()

        override fun rewrite(function: DensityFunction): DensityFunction {
            rebuilt[function]?.let { return it }
            val result = when {
                isNamed(function, NOODLES) -> NO_NOODLES
                function is RangeChoiceFunction && isNamed(function.input(), SLOPED_CHEESE) -> rewrite(function.input())
                else -> function.rewriteChildren(this)
            }
            rebuilt[function] = result
            return result
        }

        private fun isNamed(function: DensityFunction, endingIn: String): Boolean {
            val holder = (function as? DensityFunctions.HolderHolder)?.function() ?: return false
            return holder.unwrapKey().map { it.identifier().path.endsWith(endingIn) }.orElse(false)
        }
    }

    private const val SLOPED_CHEESE = "/sloped_cheese"
    private const val NOODLES = "/caves/noodle"

    /** Far above any density the rest of the terrain reaches, so the `min` it stands in is never decided by it. */
    private const val ABOVE_ANY_DENSITY = 64f
    private val NO_NOODLES = ConstantFunction(ABOVE_ANY_DENSITY)
}
