package co.voik.agesandtheart.worldgen.biome

import com.google.gson.JsonElement
import com.mojang.serialization.JsonOps
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator

/**
 * How wide a *region* is when an Age divides itself between several terrains — read off the world the Age
 * was written in rather than guessed at (design §3.4).
 *
 * **There is no biome-size parameter to read**: a biome's extent is emergent from six climate noises
 * partitioned by a search tree. What *is* declared is the thing Large Biomes changes — the overworld's
 * temperature function is a shifted noise whose `xz_scale` is the rate its coordinates are sampled at,
 * `0.25` normally and `0.0625` under Large Biomes.
 *
 * **Read through the codec, because `DensityFunctions.ShiftedNoise` is protected** — the type cannot be
 * named from outside and an `is` check will not compile. `DensityFunction.CODEC` is public, and a
 * function that came out of a datapack must go back into one, so encoding the setting and reading the
 * field out of the JSON gets the number through the front door. Works for any datapack that changes
 * climate scale, not merely vanilla's own Large Biomes.
 */
object BiomeScale {

    /**
     * How wide a region should be in [server]'s worlds, in blocks. Read once when an Age is written and
     * then **frozen into its recipe**, never re-read on open — a datapack that retunes climate would
     * otherwise silently redraw the territories of every Age already written.
     */
    fun regionBlocks(server: MinecraftServer): Int {
        val scale = climateXzScale(server)?.takeIf { it > 0.0 } ?: return DEFAULT_REGION_BLOCKS
        return (DEFAULT_REGION_BLOCKS * (DEFAULT_XZ_SCALE / scale)).toInt().coerceIn(SMALLEST, LARGEST)
    }

    /**
     * The rate the overworld samples its temperature noise at, or null where there is nothing to read —
     * a superflat world, a non-noise generator, or a datapack whose climate is some other shape entirely.
     * All fall back to the default: an unreadable world is not a wrongly-sized one.
     *
     * Read from the *settings* rather than the live router: a running router's nodes are wrapped in
     * caches and markers that need not survive a round trip, where the settings hold the function as the
     * datapack declared it.
     */
    private fun climateXzScale(server: MinecraftServer): Double? = runCatching {
        val generator = server.overworld().chunkSource.generator as? NoiseBasedChunkGenerator ?: return null
        val temperature = generator.generatorSettings().value().noiseRouter().temperature()
        val ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess())
        val encoded = DensityFunction.CODEC.encodeStart(ops, temperature).result().orElse(null)
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
     * What a region is at vanilla's default settings — roughly one biome. Also what an unreadable world
     * gets, so the fallback and the default are one number by construction.
     */
    const val DEFAULT_REGION_BLOCKS = 400

    /** Vanilla overworld's own rate, which the figure above is calibrated against. */
    private const val DEFAULT_XZ_SCALE = 0.25

    // A region below a couple of chunks would read as noise rather than geography; one above this would
    // put the second terrain beyond any distance a player would travel to find it.
    private const val SMALLEST = 64
    private const val LARGEST = 8192
}
