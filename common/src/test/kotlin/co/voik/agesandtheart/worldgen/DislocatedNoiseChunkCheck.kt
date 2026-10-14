package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.NoiseChunk
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume

/** Vanilla's fill, read from elsewhere in a patch: the density there is the plain density at the moved place. */
@Tags(NEEDS_REGISTRIES)
class DislocatedNoiseChunkCheck : FunSpec({

    val settings: NoiseGeneratorSettings by lazy {
        MinecraftRegistries.worldgen.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value()
    }
    val randomState: RandomState by lazy {
        RandomState.create(MinecraftRegistries.worldgen.lookupOrThrow(Registries.NOISE), SEED, settings)
    }
    val dislocation = Dislocation(SEED, 1.0)
    val fluid = Aquifer.FluidPicker { _, _, _ -> Aquifer.FluidStatus(SEA_LEVEL, settings.defaultFluid()) }

    fun volumeAt(minBlockX: Int, minBlockZ: Int) = DensityVolume(WIDTH, HEIGHT, WIDTH, minBlockX, MIN_Y, minBlockZ)

    fun densityOf(chunk: NoiseChunk, volume: DensityVolume): FloatArray =
        chunk.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(volume).use { buffer ->
            FloatArray(volume.size()) { buffer.get(it) }
        }

    fun plain(volume: DensityVolume) = NoiseChunk(randomState, null, settings, fluid, Blender.empty(), volume)

    test("a patch's columns read the plain density at the place they are moved to, and the rest are untouched") {
        fun movedColumnsIn(chunkX: Int, chunkZ: Int) = (0..<WIDTH).sumOf { x ->
            (0..<WIDTH).count { z -> !dislocation.shiftAt(chunkX * WIDTH + x, chunkZ * WIDTH + z).isNone }
        }
        val (chunkX, chunkZ) = (-20..20).flatMap { x -> (-20..20).map { z -> x to z } }
            .maxBy { (x, z) -> movedColumnsIn(x, z) }
        val volume = volumeAt(chunkX * WIDTH, chunkZ * WIDTH)
        val dislocated = densityOf(
            DislocatedNoiseChunk(randomState, null, settings, fluid, Blender.empty(), volume, dislocation),
            volume,
        )
        val native = densityOf(plain(volume), volume)

        var movedColumns = 0
        var nativeColumns = 0
        for (z in 0..<WIDTH) for (x in 0..<WIDTH) {
            val shift = dislocation.shiftAt(volume.blockX(x), volume.blockZ(z))
            val source = if (shift.isNone) volume else volumeAt(volume.minBlockX() + shift.x, volume.minBlockZ() + shift.z)
            val expected = if (shift.isNone) native else densityOf(plain(source), source)
            if (shift.isNone) nativeColumns++ else movedColumns++
            for (y in 0..<HEIGHT) {
                val index = volume.indexUnchecked(x, y, z)
                check(dislocated[index] == expected[index]) {
                    "column ($x, $z) at $y read ${dislocated[index]}, expected ${expected[index]} (shift ${shift.x}, ${shift.z})"
                }
            }
        }
        check(movedColumns > 0) { "the chunk had no moved column" }
        println("DislocatedNoiseChunkCheck: chunk ($chunkX, $chunkZ) had $movedColumns moved and $nativeColumns native columns")
        val differs = (0..<volume.size()).count { dislocated[it] != native[it] }
        check(differs > 0) { "moving the columns changed nothing" }
    }
}) {
    private companion object {
        const val SEED = 0x5EEDL
        const val WIDTH = 16
        const val HEIGHT = 128
        const val MIN_Y = 0
        const val SEA_LEVEL = 63
    }
}
