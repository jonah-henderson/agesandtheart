package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.core.HolderGetter
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.synth.NormalNoise
import java.util.stream.Stream

/**
 * Which biome stands where in an Age. It exists because vanilla's [net.minecraft.world.level.biome.MultiNoiseBiomeSource]
 * cannot work for us, for a reason worth writing down.
 *
 * A biome source is handed a [Climate.Sampler] and expected to read the six climate parameters out of
 * it. But the sampler is not ours to choose: [net.minecraft.server.level.ChunkMap] builds the level's
 * [RandomState] from `NoiseGeneratorSettings.dummy()` for any generator that is not a
 * `NoiseBasedChunkGenerator`, and derives the sampler from *that* settings' noise router — which is
 * inert. So the sampler we are given reports zero for every parameter at every position, and a
 * multi-noise source fed from it would return one biome for the whole world.
 *
 * The way out is not to patch the game: [RandomState.create] is public, so we build our own from
 * vanilla's real overworld noise settings and take the climate half of its router. That gives an Age
 * *bit-identical* climate to vanilla — same noise, same domain warping, same continents and biome
 * sizes — with nothing to tune. The Age's terrain is still entirely ours; only the question "what grows
 * here" is answered vanilla's way, which is the sane default when a player has expressed no preference.
 *
 * Two seams for when they do express one:
 * - [biomes] is the climate-to-biome table. It is a registry holder, so a datapack can define a new
 *   `multi_noise_biome_source_parameter_list` and an Age can name it — swapping the whole table without
 *   a line of code.
 * - [depth] is the one parameter we must answer ourselves; see [ClimateDepth].
 */
class AgeBiomeSource(
    private val biomes: Holder<MultiNoiseBiomeSourceParameterList>,
    private val climateSettings: Holder<NoiseGeneratorSettings>,
    private val seed: Long,
    private val depth: ClimateDepth,
    private val noiseParameters: HolderGetter<NormalNoise.NoiseParameters>,
) : BiomeSource() {

    override fun codec(): MapCodec<out BiomeSource> = CODEC

    /** The same climate and table, with [depth] measured against [terrain] — see [BelowTerrain]. */
    fun groundedIn(terrain: TerrainField): AgeBiomeSource =
        AgeBiomeSource(biomes, climateSettings, seed, BelowTerrain(terrain), noiseParameters)

    private val table: Climate.ParameterList<Holder<Biome>> by lazy { biomes.value().parameters() }

    /**
     * Vanilla's climate functions, seeded with this Age's seed. Built on first use rather than in the
     * constructor because construction happens during codec decode, and instantiating a router's worth
     * of noise is not something a round-trip should pay for.
     *
     * Safe to share across chunk workers: [RandomState] strips the caching `Marker` and `HolderHolder`
     * wrappers when it assembles its sampler, leaving stateless functions — which is exactly why
     * vanilla shares one sampler across all of its own.
     */
    private val climate: Climate.Sampler by lazy {
        RandomState.create(climateSettings.value(), noiseParameters, seed).sampler()
    }

    /**
     * Coordinates arrive quartered (one sample per 4 blocks, as biomes are stored). The sampler we are
     * handed is the inert one described above; we ignore it and read our own.
     */
    override fun getNoiseBiome(quartX: Int, quartY: Int, quartZ: Int, inertSampler: Climate.Sampler): Holder<Biome> {
        val blockX = QuartPos.toBlock(quartX)
        val blockY = QuartPos.toBlock(quartY)
        val blockZ = QuartPos.toBlock(quartZ)
        val point = DensityFunction.SinglePointContext(blockX, blockY, blockZ)
        return table.findValue(
            Climate.target(
                climate.temperature().compute(point).toFloat(),
                climate.humidity().compute(point).toFloat(),
                climate.continentalness().compute(point).toFloat(),
                climate.erosion().compute(point).toFloat(),
                depth.at(blockX, blockY, blockZ),
                climate.weirdness().compute(point).toFloat(),
            ),
        )
    }

    override fun collectPossibleBiomes(): Stream<Holder<Biome>> = table.values().stream().map { it.second }

    companion object {
        val CODEC: MapCodec<AgeBiomeSource> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                MultiNoiseBiomeSourceParameterList.CODEC.fieldOf("biomes").forGetter { it.biomes },
                NoiseGeneratorSettings.CODEC.fieldOf("climate").forGetter { it.climateSettings },
                Codec.LONG.fieldOf("seed").forGetter { it.seed },
                ClimateDepth.CODEC.optionalFieldOf("depth", AtSurface).forGetter { it.depth },
                // Not a stored field: retrieved from the ops on decode, absent on encode.
                RegistryOps.retrieveGetter<NormalNoise.NoiseParameters, AgeBiomeSource>(Registries.NOISE),
            ).apply(instance, ::AgeBiomeSource)
        }

        /**
         * Vanilla's overworld biomes under vanilla's overworld climate, at the Age's own [seed]. The
         * default for an Age that names no biome preferences.
         *
         * Note `NoiseGeneratorSettings.OVERWORLD` is consulted for nothing but the climate half of its
         * router — its terrain functions are never evaluated, since the field tree shapes our world.
         * [NoiseGeneratorSettings.LARGE_BIOMES] differs only in climate scale, so it is a drop-in here.
         */
        fun vanillaOverworld(
            server: MinecraftServer,
            seed: Long,
            climate: ResourceKey<NoiseGeneratorSettings> = NoiseGeneratorSettings.OVERWORLD,
        ): AgeBiomeSource {
            val registries = server.registryAccess()
            return AgeBiomeSource(
                registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD),
                registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(climate),
                seed,
                AtSurface,
                registries.lookupOrThrow(Registries.NOISE),
            )
        }
    }
}
