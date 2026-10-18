package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.NoiseRouter
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction
import java.util.IdentityHashMap

/** Vanilla's overworld router read wider: its four climate noises at a wider scale, and its ridges left alone. */
@Tags(NEEDS_REGISTRIES)
class ClimateStretchCheck : FunSpec({

    val settings by lazy {
        MinecraftRegistries.worldgen.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value()
    }
    val overworld by lazy { settings.noiseRouter() }
    val aquifers by lazy { settings.aquifers().orElseThrow() }

    /** Every noise [functions] read, by name, at the horizontal scales they read it. */
    fun scalesIn(functions: List<DensityFunction>): Map<String, Set<Double>> {
        val found = HashMap<String, MutableSet<Double>>()
        val seen = IdentityHashMap<DensityFunction, DensityFunction>()
        val walk = object : DfRewriteRule {
            override fun rewrite(function: DensityFunction): DensityFunction = seen.getOrPut(function) {
                if (function is NoiseFunction) {
                    val name = function.noise().unwrapKey().map { it.identifier().toString() }.orElse("unnamed")
                    found.getOrPut(name) { HashSet() } += function.xzScale()
                }
                function.rewriteChildren(this)
            }
        }
        functions.forEach(walk::rewrite)
        return found
    }

    fun scalesIn(router: NoiseRouter) =
        scalesIn(listOf(router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.ridges(), router.finalDensity()))

    fun scalesIn(config: Aquifer.Config) = scalesIn(listOf(config.exclusion(), config.surfaceLevel(), config.barrierNoise()))

    test("the climate is read twice as wide, and the ridges as they were") {
        val before = scalesIn(overworld)
        val after = scalesIn(ClimateStretch.of(overworld, 2.0))
        for (noise in listOf("minecraft:continentalness", "minecraft:erosion", "minecraft:temperature", "minecraft:vegetation")) {
            check(after[noise] == before[noise]?.map { it / 2.0 }?.toSet()) { "$noise read at ${after[noise]} where it was ${before[noise]}" }
        }
        check(after["minecraft:ridge"] == before["minecraft:ridge"]) { "the ridges moved: ${before["minecraft:ridge"]} to ${after["minecraft:ridge"]}" }
    }

    test("the aquifers read the land as wide as the router does, and their own noises as they were") {
        val before = scalesIn(aquifers)
        val after = scalesIn(ClimateStretch.of(aquifers, 2.0))
        check(after["minecraft:continentalness"] == before["minecraft:continentalness"]?.map { it / 2.0 }?.toSet()) {
            "the aquifers' surface read continentalness at ${after["minecraft:continentalness"]}"
        }
        check(after["minecraft:aquifer_barrier"] == before["minecraft:aquifer_barrier"]) { "the aquifer barrier moved" }
    }

    test("a router not stretched is handed back as it was") {
        check(ClimateStretch.of(overworld, 1.0) === overworld) { "an unstretched router was rebuilt" }
    }
})
