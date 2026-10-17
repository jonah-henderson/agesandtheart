package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Biomes
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext

/** A biome's size is the climate read from nearer the origin, so `colossal biomes` spread four times as wide. */
@Tags(NEEDS_REGISTRIES)
class BiomeStretchCheck : FunSpec({

    fun plains() = AgeBiomeSource(
        FixedBiomeSource(MinecraftRegistries.worldgen.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)),
    )

    val warmingEastward = Climate.Sampler(
        reading { x, _ -> x / WARMED_OVER }, flat, flat, flat, flat, flat,
    )

    test("a stretched Age reads four times as far out what an ordinary one reads") {
        val ordinary = plains().climateAt(warmingEastward, FAR, 0, 0).temperature()
        val stretched = plains().stretched(4.0).climateAt(warmingEastward, FAR * 4, 0, 0).temperature()
        check(ordinary == stretched) { "an Age four times as wide read $stretched where an ordinary one read $ordinary" }
    }

    test("colossal biomes are four times as wide, minuscule a quarter, and an Age that says nothing as vanilla's") {
        fun stretchOf(vararg pages: String) =
            Biomes.stretchIn(ShippedCorpus.resolved(SEED, *pages).composition.optionsFor(Aspect.BIOMES, 0), SEED)
        check(stretchOf("colossal", "biomes") == 4.0) { "'colossal biomes' stretched ${stretchOf("colossal", "biomes")}" }
        check(stretchOf("minuscule", "biomes") == 0.25) { "'minuscule biomes' stretched ${stretchOf("minuscule", "biomes")}" }
        check(stretchOf() == 1.0) { "an Age that said nothing stretched ${stretchOf()}" }
    }
})

private val flat = reading { _, _ -> 0.0 }
private const val WARMED_OVER = 2000.0
private const val FAR = 500
private const val SEED = 4242L

private fun reading(at: (blockX: Int, blockZ: Int) -> Double): DensitySampler.Bound =
    DensitySampler.Bound(
        object : DensitySampler {
            override fun sampleValue(context: SamplerContext, blockX: Int, blockY: Int, blockZ: Int): Float =
                at(blockX, blockZ).toFloat()

            override fun sampleVolume(context: SamplerContext, into: DensityBuffer, over: DensityVolume) =
                DensitySampler.sampleVolumeNaive(context, into, over, this)
        },
        SamplerContext.EMPTY_UNCACHED,
    )
