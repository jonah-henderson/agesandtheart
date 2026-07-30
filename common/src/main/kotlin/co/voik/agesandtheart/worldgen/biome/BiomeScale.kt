package co.voik.agesandtheart.worldgen.biome

import com.google.gson.JsonElement
import com.mojang.serialization.JsonOps
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator

/**
 * How wide a *region* is, when an Age divides itself between several terrains — read off the world the
 * Age was written in rather than guessed at.
 *
 * The design (`notes/the-art-design.md` §3.4) asks for a region roughly the size of one vanilla biome,
 * and for Large Biomes to widen it accordingly. Minecraft makes that awkward, because **there is no
 * biome-size parameter to read**: a biome's extent is emergent from six climate noises partitioned by a
 * search tree, and nothing anywhere declares "biomes are N blocks across".
 *
 * What *is* declared is the thing Large Biomes actually changes. The overworld's temperature function is
 * a shifted noise, and its `xz_scale` is the rate its coordinates are sampled at — `0.25` in the
 * overworld, `0.0625` under Large Biomes, so a quarter of the rate and four times the biome.
 *
 * Reading it takes one indirection, and the reason is worth recording so nobody tries the obvious thing
 * again: `DensityFunctions.ShiftedNoise` is **protected**, so the type cannot be named from outside and
 * an `is` check will not compile. But `DensityFunction.DIRECT_CODEC` is public, and a function that came
 * out of a datapack must by definition go back into one — so encoding the setting and reading the field
 * out of the JSON gets the number through the front door. It works for any datapack or mod that changes
 * climate scale, not merely for vanilla's own Large Biomes.
 *
 * So one chosen figure for default settings, scaled by the ratio. The figure is a constant we would have
 * had to pick either way; what this avoids is *measuring* biome widths per world, which would be slow,
 * noisy, and wrong on any world whose oceans happened to dominate the sample.
 */
object BiomeScale {

    /**
     * How wide a region should be in [server]'s worlds, in blocks.
     *
     * Read once when an Age is written and then **frozen into its recipe** — never re-read on open. A
     * datapack update that retunes climate would otherwise silently redraw the territories of every Age
     * already written, which is the same trap §4.6 avoids by persisting the resolved recipe rather than
     * the words.
     */
    fun regionBlocks(server: MinecraftServer): Int {
        val scale = climateXzScale(server)?.takeIf { it > 0.0 } ?: return DEFAULT_REGION_BLOCKS
        return (DEFAULT_REGION_BLOCKS * (DEFAULT_XZ_SCALE / scale)).toInt().coerceIn(SMALLEST, LARGEST)
    }

    /**
     * The rate the overworld samples its temperature noise at, or null where there is nothing to read —
     * a superflat world, a world whose generator is not noise-based, or a datapack whose climate is
     * built from some other shape of function entirely. All of those fall back to the default, which is
     * the right answer: an unreadable world is not a wrongly-sized one.
     *
     * Read from the *settings* rather than the live router on purpose. By the time a router is running
     * its nodes have been wrapped in caches and markers, which need not survive a round trip; the
     * settings hold the function as the datapack declared it, which must.
     */
    private fun climateXzScale(server: MinecraftServer): Double? = runCatching {
        val generator = server.overworld().chunkSource.generator as? NoiseBasedChunkGenerator ?: return null
        val temperature = generator.generatorSettings().value().noiseRouter().temperature()
        val ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess())
        val encoded = DensityFunction.DIRECT_CODEC.encodeStart(ops, temperature).result().orElse(null)
        encoded?.let(::firstXzScale)
    }.getOrNull()

    /** The first `xz_scale` anywhere in the encoded function, however deeply it is wrapped. */
    private fun firstXzScale(json: JsonElement): Double? {
        if (json.isJsonObject) {
            val fields = json.asJsonObject
            fields.get(XZ_SCALE)?.takeIf { it.isJsonPrimitive }?.let { return it.asDouble }
            return fields.entrySet().firstNotNullOfOrNull { (_, value) -> firstXzScale(value) }
        }
        if (json.isJsonArray) return json.asJsonArray.firstNotNullOfOrNull(::firstXzScale)
        return null
    }

    private const val XZ_SCALE = "xz_scale"

    /**
     * What a region is at vanilla's default settings — roughly one biome. Tune here, once.
     *
     * Also what an unreadable world gets, and what an Age written before regions existed is taken to
     * have had, so the fallback and the history are the same number by construction.
     */
    const val DEFAULT_REGION_BLOCKS = 400

    /** Vanilla overworld's own rate, which the figure above is calibrated against. */
    private const val DEFAULT_XZ_SCALE = 0.25

    // A region below a couple of chunks would read as noise rather than geography; one above this would
    // put the second terrain beyond any distance a player would travel to find it.
    private const val SMALLEST = 64
    private const val LARGEST = 8192
}
