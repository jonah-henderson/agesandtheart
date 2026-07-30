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
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.RandomState
import java.util.Optional
import java.util.stream.Stream

/**
 * Which biome stands where in an Age: vanilla's climate, read through a table this Age is allowed to bend.
 *
 * A biome source is handed a [Climate.Sampler] and expected to read the six climate parameters out of it,
 * and **that sampler is now real**, so this simply does what a biome source is meant to do. It was not
 * always: [net.minecraft.server.level.ChunkMap] builds the level's [RandomState] from
 * `NoiseGeneratorSettings.dummy()` for any generator that is not a `NoiseBasedChunkGenerator`, whose router
 * is inert — so the sampler reported zero everywhere and this class had to build a whole private
 * [RandomState] from vanilla's overworld settings to get a climate at all.
 *
 * `AgeChunkGenerator` is a `NoiseBasedChunkGenerator` now, and carries vanilla's real climate functions in
 * its router (2026-07-28), so the private copy is gone and the handed sampler is the same climate it used
 * to build for itself — same noise, same domain warping, same continents and biome sizes, seeded from the
 * Age's own seed because Fantasy sets the dimension seed from the recipe. The Age's terrain is still
 * entirely ours; only "what grows here" is answered vanilla's way.
 *
 * **[depth] is still ours and must stay so**, which is why the generator leaves that one aspect of the router
 * at zero: vanilla's depth describes vanilla's relief, and ours has to be measured against the field tree.
 *
 * Two seams for when a writer expresses a preference:
 * - [biomes] is the climate-to-biome table. It is a registry holder, so a datapack can define a new
 *   `multi_noise_biome_source_parameter_list` and an Age can name it — swapping the whole table without
 *   a line of code.
 * - [depth] is the one parameter we must answer ourselves; see [ClimateDepth].
 */
class AgeBiomeSource(
    private val biomes: Holder<MultiNoiseBiomeSourceParameterList>,
    private val seed: Long,
    private val depth: ClimateDepth,
    /**
     * What this Age is *like* — the vague half of biome authoring, applied to the climate before the table
     * is asked (design §3.2). Idle for an Age whose author said nothing about it.
     */
    private val bent: RegionalClimate = RegionalClimate.NONE,
    /**
     * The biomes this Age was told to grow, or not to — the exact half, applied to the table itself.
     *
     * Both halves are wanted and they can disagree: a hot dry world that excludes deserts is a sentence
     * somebody will write. That is a contradiction for the instability index to price (§5), not something
     * for this class to arbitrate, so both are applied as written.
     */
    private val preferences: List<BiomePreference> = emptyList(),
    /**
     * Whether the sentence **singled biomes out**, so everything it did not name is struck from the table —
     * `only cherry groves` (design §4.3.1's `only`).
     *
     * A flag rather than a preference per unwanted biome, because the writer named what they *did* want and
     * the table is the only thing that knows what else was in it. It composes with [preferences] rather than
     * replacing them: `only` decides what survives, and a mention among the survivors still strengthens.
     */
    private val keepsOnlyNamed: Boolean = false,
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
    private val biomeLookup: HolderGetter<Biome>,
) : BiomeSource() {

    override fun codec(): MapCodec<out BiomeSource> = CODEC

    /** The same table, with [depth] measured against [terrain] — see [BelowTerrain]. */
    fun groundedIn(terrain: TerrainField): AgeBiomeSource =
        AgeBiomeSource(biomes, seed, BelowTerrain(terrain), bent, preferences, keepsOnlyNamed, flattenedTo, biomeLookup)

    /** The same source, told what to grow — see [BiomePreference] and [RegionalClimate]. */
    fun told(bent: RegionalClimate, preferences: List<BiomePreference>, keepsOnlyNamed: Boolean = false) =
        AgeBiomeSource(biomes, seed, depth, bent, preferences, keepsOnlyNamed, flattenedTo, biomeLookup)

    /** The same table, but one biome everywhere until something is named — see [flattenedTo]. */
    fun flattenedTo(only: Holder<Biome>): AgeBiomeSource =
        AgeBiomeSource(biomes, seed, depth, bent, preferences, keepsOnlyNamed, only, biomeLookup)

    /**
     * Vanilla's climate-to-biome table with this Age's preferences folded in.
     *
     * Lazy, and it matters more than it looks: applying preferences rebuilds an RTree over some seven and a
     * half thousand entries, and construction happens during codec decode. A round trip must not pay for it.
     */
    private val table: Climate.ParameterList<Holder<Biome>> by lazy {
        val base = biomes.value().parameters()
        val flattened = flattenedTo?.let { only ->
            Climate.ParameterList(base.values().map { entry -> Pair.of(entry.first, only) })
        } ?: base
        BiomePreference.applied(flattened, preferences, keepsOnlyNamed, biomeLookup, seed)
    }

    /**
     * Coordinates arrive quartered (one sample per 4 blocks, as biomes are stored), and [climate] is the
     * level's own sampler — real, and cached per cell by vanilla, which the private one this replaced was
     * not.
     */
    override fun getNoiseBiome(quartX: Int, quartY: Int, quartZ: Int, climate: Climate.Sampler): Holder<Biome> {
        val blockX = QuartPos.toBlock(quartX)
        val blockY = QuartPos.toBlock(quartY)
        val blockZ = QuartPos.toBlock(quartZ)
        val point = DensityFunction.SinglePointContext(blockX, blockY, blockZ)
        // Which climate governs *here*, since an Age may have fractured into more than one (see
        // [RegionalClimate]). One climate answers without consulting a map at all.
        val bias = bent.at(quartX, quartZ)
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
                // Weirdness passes through too, now that its vocabulary belongs to Biomes rather than to
                // Climate — step 5 picks it up. See [ClimateAxis].
                climate.weirdness().compute(point).toFloat(),
            ),
        )
    }

    override fun collectPossibleBiomes(): Stream<Holder<Biome>> = table.values().stream().map { it.second }

    companion object {
        val CODEC: MapCodec<AgeBiomeSource> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                MultiNoiseBiomeSourceParameterList.CODEC.fieldOf("biomes").forGetter { it.biomes },
                Codec.LONG.fieldOf("seed").forGetter { it.seed },
                ClimateDepth.CODEC.optionalFieldOf("depth", AtSurface).forGetter { it.depth },
                RegionalClimate.CODEC.optionalFieldOf("bias", RegionalClimate.NONE).forGetter { it.bent },
                BiomePreference.CODEC.listOf().optionalFieldOf("preferences", emptyList())
                    .forGetter { it.preferences },
                Codec.BOOL.optionalFieldOf("keeps_only_named", false).forGetter { it.keepsOnlyNamed },
                Biome.CODEC.optionalFieldOf("flattened_to").forGetter { Optional.ofNullable(it.flattenedTo) },
                // Not a stored field: retrieved from the ops on decode, absent on encode.
                RegistryOps.retrieveGetter<Biome, AgeBiomeSource>(Registries.BIOME),
            ).apply(instance) { table, seed, depth, bias, preferences, onlyNamed, flattened, lookup ->
                AgeBiomeSource(table, seed, depth, bias, preferences, onlyNamed, flattened.orElse(null), lookup)
            }
        }

        /**
         * Vanilla's overworld biome table, at the Age's own [seed]. The default for an Age that names no
         * biome preferences.
         *
         * The *climate* those biomes are looked up at no longer comes from here — it is whatever
         * `AgeChunkGenerator` put in its router, which the game turns into the level's sampler. Which
         * settings supply it (`OVERWORLD`, or `LARGE_BIOMES`, which differs only in climate scale) is that
         * generator's choice now, and the table below is a separate one.
         */
        fun vanillaOverworld(server: MinecraftServer, seed: Long): AgeBiomeSource {
            val registries = server.registryAccess()
            return AgeBiomeSource(
                registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD),
                seed,
                AtSurface,
                RegionalClimate.NONE,
                emptyList(),
                keepsOnlyNamed = false,
                flattenedTo = null,
                biomeLookup = registries.lookupOrThrow(Registries.BIOME),
            )
        }
    }
}
