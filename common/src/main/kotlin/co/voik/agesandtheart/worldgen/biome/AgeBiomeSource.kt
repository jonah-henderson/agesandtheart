package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.datafixers.util.Pair
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
import java.util.Optional
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
    /**
     * What this Age is *like* — the vague half of biome authoring, applied to the climate before the table
     * is asked (design §3.2). Idle for an Age whose author said nothing about it.
     */
    private val bias: ClimateBias = ClimateBias.NONE,
    /**
     * The biomes this Age was told to grow, or not to — the exact half, applied to the table itself.
     *
     * Both halves are wanted and they can disagree: a hot dry world that excludes deserts is a sentence
     * somebody will write. That is a contradiction for the instability index to price (§5), not something
     * for this class to arbitrate, so both are applied as written.
     */
    private val preferences: List<BiomePreference> = emptyList(),
    /**
     * One biome for the whole table, before any preference is applied — how a *barren* dressing gets a
     * climate table instead of a single fixed biome.
     *
     * Vanilla's climate *positions* are kept and only the biome at each is replaced, which is what makes
     * this three lines rather than a mechanism: anchoring, the surface filter and everything else in
     * [BiomePreference] then work exactly as they do over the overworld. Naming a biome against a barren
     * Age therefore gives isolated patches of it in the waste, which is what a writer means by "a bare
     * stone world with cherry groves" (Jonah, 2026-07-27) — and what a [FixedBiomeSource] could never do,
     * because a single biome has no table to enrich.
     */
    private val flattenedTo: Holder<Biome>? = null,
    private val noiseParameters: HolderGetter<NormalNoise.NoiseParameters>,
    private val biomeLookup: HolderGetter<Biome>,
) : BiomeSource() {

    override fun codec(): MapCodec<out BiomeSource> = CODEC

    /** The same climate and table, with [depth] measured against [terrain] — see [BelowTerrain]. */
    fun groundedIn(terrain: TerrainField): AgeBiomeSource =
        AgeBiomeSource(
            biomes, climateSettings, seed, BelowTerrain(terrain), bias, preferences, flattenedTo,
            noiseParameters, biomeLookup,
        )

    /** The same source, told what to grow — see [BiomePreference] and [ClimateBias]. */
    fun told(bias: ClimateBias, preferences: List<BiomePreference>): AgeBiomeSource =
        AgeBiomeSource(
            biomes, climateSettings, seed, depth, bias, preferences, flattenedTo, noiseParameters, biomeLookup,
        )

    /** The same climate, but one biome everywhere until something is named — see [flattenedTo]. */
    fun flattenedTo(only: Holder<Biome>): AgeBiomeSource =
        AgeBiomeSource(biomes, climateSettings, seed, depth, bias, preferences, only, noiseParameters, biomeLookup)

    /**
     * Vanilla's climate-to-biome table with this Age's preferences folded in.
     *
     * Lazy for the same reason [climate] is, and it matters more here than it looks: applying preferences
     * rebuilds an RTree over some seven and a half thousand entries, and construction happens during codec
     * decode. A round trip must not pay for that.
     */
    private val table: Climate.ParameterList<Holder<Biome>> by lazy {
        val base = biomes.value().parameters()
        val flattened = flattenedTo?.let { only ->
            Climate.ParameterList(base.values().map { entry -> Pair.of(entry.first, only) })
        } ?: base
        BiomePreference.applied(flattened, preferences, biomeLookup, seed)
    }

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
        // Bent on the way past, which is the whole of "a hot, dry world": vanilla's own table then answers
        // with deserts and badlands, and nothing had to name one. Depth is left alone — it is ours, not
        // the climate's (see [ClimateDepth]).
        return table.findValue(
            Climate.target(
                bias.shift(ClimateAxis.TEMPERATURE, climate.temperature().compute(point).toFloat()),
                bias.shift(ClimateAxis.HUMIDITY, climate.humidity().compute(point).toFloat()),
                // Continentalness and erosion pass through untouched: they describe shape, and an Age's
                // shape is the field tree's, not the climate's. See [ClimateAxis].
                climate.continentalness().compute(point).toFloat(),
                climate.erosion().compute(point).toFloat(),
                depth.at(blockX, blockY, blockZ),
                bias.shift(ClimateAxis.WEIRDNESS, climate.weirdness().compute(point).toFloat()),
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
                ClimateBias.CODEC.optionalFieldOf("bias", ClimateBias.NONE).forGetter { it.bias },
                BiomePreference.CODEC.listOf().optionalFieldOf("preferences", emptyList())
                    .forGetter { it.preferences },
                Biome.CODEC.optionalFieldOf("flattened_to").forGetter { Optional.ofNullable(it.flattenedTo) },
                // Not stored fields: retrieved from the ops on decode, absent on encode.
                RegistryOps.retrieveGetter<NormalNoise.NoiseParameters, AgeBiomeSource>(Registries.NOISE),
                RegistryOps.retrieveGetter<Biome, AgeBiomeSource>(Registries.BIOME),
            ).apply(instance) { table, climate, seed, depth, bias, preferences, flattened, noise, lookup ->
                AgeBiomeSource(
                    table, climate, seed, depth, bias, preferences, flattened.orElse(null), noise, lookup,
                )
            }
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
                ClimateBias.NONE,
                emptyList(),
                null,
                registries.lookupOrThrow(Registries.NOISE),
                registries.lookupOrThrow(Registries.BIOME),
            )
        }
    }
}
