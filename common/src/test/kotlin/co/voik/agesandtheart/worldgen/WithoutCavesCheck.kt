package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction
import java.util.IdentityHashMap

/** Vanilla's overworld with its noise caves taken out: none of the cave noises left, and the ground's all kept. */
@Tags(NEEDS_REGISTRIES)
class WithoutCavesCheck : FunSpec({

    fun settings(key: ResourceKey<NoiseGeneratorSettings>) =
        MinecraftRegistries.worldgen.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value()

    /** Every noise [function] reads, by name. */
    fun noisesIn(function: DensityFunction): Set<String> {
        val found = HashSet<String>()
        val seen = IdentityHashMap<DensityFunction, DensityFunction>()
        val walk = object : DfRewriteRule {
            override fun rewrite(function: DensityFunction): DensityFunction = seen.getOrPut(function) {
                if (function is NoiseFunction) found += function.noise().unwrapKey().map { it.identifier().path }.orElse("unnamed")
                function.rewriteChildren(this)
            }
        }
        walk.rewrite(function)
        return found
    }

    test("the overworld's final density reads no cave noise once its caves are taken out, and every noise it shapes the ground by") {
        val before = noisesIn(settings(NoiseGeneratorSettings.OVERWORLD).noiseRouter().finalDensity())
        val after = noisesIn(WithoutCaves.of(settings(NoiseGeneratorSettings.OVERWORLD).noiseRouter()).finalDensity())
        val caves = before.filter { it.startsWith("cave") || it.startsWith("spaghetti") || it.startsWith("noodle") || it.startsWith("pillar") }
        check(caves.isNotEmpty()) { "vanilla's final density read no cave noise to take out: $before" }
        check(after.none { it in caves }) { "cave noises left: ${after.filter { it in caves }}" }
        check(after == before - caves.toSet()) { "the ground's own noises changed: ${before - caves.toSet()} became $after" }
    }

    test("the nether's router, which names no caves, keeps every noise") {
        val nether = settings(NoiseGeneratorSettings.NETHER).noiseRouter()
        check(noisesIn(WithoutCaves.of(nether).finalDensity()) == noisesIn(nether.finalDensity())) { "the nether lost something" }
    }
})
