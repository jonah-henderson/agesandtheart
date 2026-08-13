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
 * The handed [Climate.Sampler] is real, because `AgeChunkGenerator` is a `NoiseBasedChunkGenerator` and
 * carries vanilla's climate functions in its router — same noise, same warping, seeded from the Age's own
 * seed. The terrain is still entirely ours; only "what grows here" is answered vanilla's way.
 *
 * **[depth] is ours and must stay so**, which is why the generator leaves that one slot of the router at
 * zero: vanilla's depth describes vanilla's relief, and ours is measured against the field tree.
 *
 * [biomes] is a registry holder, so a datapack can define a new
 * `multi_noise_biome_source_parameter_list` and an Age can name it, swapping the table without code.
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
     * The biomes this Age was told to grow, or not to — the exact half, applied to the table itself. A hot
     * dry world that excludes deserts is a contradiction for the instability index to price (§5), not one
     * for this class to arbitrate, so both halves are applied as written.
     */
    private val preferences: List<BiomePreference> = emptyList(),
    /**
     * Whether the sentence **singled biomes out**, so everything it did not name is struck from the table
     * (§4.3.1's `only`). A flag rather than a preference per unwanted biome, because the writer named what
     * they *did* want and only the table knows what else was in it. `only` decides what survives, and a
     * mention among the survivors still strengthens.
     */
    private val keepsOnlyNamed: Boolean = false,
    /**
     * Whether this Age's biomes agree with its shape, and how — null for the default, which is that they do
     * not. See [Grounding] for why "they do not" is a decision rather than a defect.
     */
    private val grounding: Grounding? = null,
    /**
     * A band of this Age that is indoors, answered before the climate table — null for an Age with no
     * such band, which is almost all of them. See [Roofed].
     */
    private val roofed: Roofed? = null,
    /**
     * One biome for the whole table, before any preference is applied. Vanilla's climate *positions* are
     * kept and only the biome at each is replaced, so anchoring and the surface filter work exactly as
     * they do over the overworld — which is what a `FixedBiomeSource` could never offer, having no table
     * to enrich.
     */
    private val flattenedTo: Holder<Biome>? = null,
    private val biomeLookup: HolderGetter<Biome>,
) : BiomeSource() {

    override fun codec(): MapCodec<out BiomeSource> = CODEC

    /**
     * The same table, taking **vanilla's own depth** — for an Age whose rock is vanilla's and which has no
     * field to measure against. See [AsSampled], and note that [AtSurface] would be wrong rather than
     * merely coarse: it answers zero everywhere, so no cave biome would ever be reached.
     */
    fun sampledForDepth(): AgeBiomeSource =
        AgeBiomeSource(biomes, seed, AsSampled, bent, preferences, keepsOnlyNamed, grounding, roofed, flattenedTo, biomeLookup)

    /** The same table, with [depth] measured against [terrain] — see [BelowTerrain]. */
    fun groundedIn(terrain: TerrainField): AgeBiomeSource =
        AgeBiomeSource(biomes, seed, BelowTerrain(terrain), bent, preferences, keepsOnlyNamed, grounding, roofed, flattenedTo, biomeLookup)

    /** The same table, with ocean, coast and river read off the shape — see [Grounding]. */
    fun suitedTo(grounding: Grounding?): AgeBiomeSource =
        AgeBiomeSource(biomes, seed, depth, bent, preferences, keepsOnlyNamed, grounding, roofed, flattenedTo, biomeLookup)

    /** The same table, with a band of it indoors — see [Roofed]. */
    fun roofedBy(roofed: Roofed?): AgeBiomeSource =
        AgeBiomeSource(biomes, seed, depth, bent, preferences, keepsOnlyNamed, grounding, roofed, flattenedTo, biomeLookup)

    /** The same source, told what to grow — see [BiomePreference] and [RegionalClimate]. */
    fun told(bent: RegionalClimate, preferences: List<BiomePreference>, keepsOnlyNamed: Boolean = false) =
        AgeBiomeSource(biomes, seed, depth, bent, preferences, keepsOnlyNamed, grounding, roofed, flattenedTo, biomeLookup)

    /** The same table, but one biome everywhere until something is named — see [flattenedTo]. */
    fun flattenedTo(only: Holder<Biome>): AgeBiomeSource =
        AgeBiomeSource(biomes, seed, depth, bent, preferences, keepsOnlyNamed, grounding, roofed, only, biomeLookup)

    /**
     * Vanilla's climate-to-biome table with this Age's preferences folded in. **Lazy**, because applying
     * preferences rebuilds an RTree over some seven thousand entries and construction happens during codec
     * decode — a round trip must not pay for it.
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
        // Answered before the climate table is consulted at all, because it is not a climate question: the
        // halls are a *place*, and vanilla's table has no coordinate that means "indoors". See [roofed].
        roofed?.biomeAt(blockY)?.let { return it }
        val point = DensityFunction.SinglePointContext(blockX, blockY, blockZ)
        // Which climate governs *here*, since an Age may have fractured into more than one (see
        // [RegionalClimate]). One climate answers without consulting a map at all.
        val bias = bent.at(quartX, quartZ)
        // Bent on the way past, which is the whole of "a hot, dry world": vanilla's own table then answers
        // with deserts and badlands, and nothing had to name one. Depth is left alone — it is ours, not
        // the climate's (see [ClimateDepth]).
        // Bent first, then chilled by however far this column stands above the Age's floor — the bias is what
        // the writer asked for and the lapse is what the mountain does to it, so a warm Age still has cold
        // summits and a cold one has colder. See [Elevation].
        val warmth = bias.shift(ClimateAxis.TEMPERATURE, climate.temperature().compute(point).toFloat())
        return table.findValue(
            Climate.target(
                grounding?.temperatureAt(blockX, blockZ, warmth) ?: warmth,
                bias.shift(ClimateAxis.HUMIDITY, climate.humidity().compute(point).toFloat()),
                // Continentalness describes shape, and an Age's shape is the field tree's rather than the
                // climate's — so by default it passes through untouched and the two simply disagree. A
                // *grounded* Age reads it off the shape instead. See [Grounding] and [ClimateAxis].
                grounding?.continentalnessAt(blockX, blockZ)
                    ?: climate.continentalness().compute(point).toFloat(),
                // Erosion means how worn flat the ground is, so a grounded Age reads it off its own fall
                // rather than off a noise that never saw the terrain — which is what decides a sandy beach
                // from a stony shore. See [Grounding].
                grounding?.erosionAt(blockX, blockZ) ?: climate.erosion().compute(point).toFloat(),
                depth.at(blockX, blockY, blockZ, climate.depth().compute(point).toFloat()),
                // Weirdness passes through too, now that its vocabulary belongs to Biomes rather than to
                // Climate — step 5 picks it up. A grounded Age pushes it into the valley band where its own
                // rivers run, which is where vanilla files them. See [ClimateAxis].
                weirdnessAt(blockX, blockZ, climate.weirdness().compute(point).toFloat()),
            ),
        )
    }

    private fun weirdnessAt(blockX: Int, blockZ: Int, vanillas: Float): Float =
        grounding?.weirdnessAt(blockX, blockZ, vanillas) ?: vanillas

    /** The table's own, plus anything only [roofed] can hand out — which is in no table and must be said. */
    override fun collectPossibleBiomes(): Stream<Holder<Biome>> =
        Stream.concat(table.values().stream().map { it.second }, Stream.ofNullable(roofed?.biome))

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
                Grounding.CODEC.optionalFieldOf("grounding").forGetter { Optional.ofNullable(it.grounding) },
                // Absent for every Age with nothing indoors, which is almost all of them.
                Roofed.CODEC.optionalFieldOf("roofed").forGetter { Optional.ofNullable(it.roofed) },
                Biome.CODEC.optionalFieldOf("flattened_to").forGetter { Optional.ofNullable(it.flattenedTo) },
                // Not a stored field: retrieved from the ops on decode, absent on encode.
                RegistryOps.retrieveGetter<Biome, AgeBiomeSource>(Registries.BIOME),
            ).apply(instance) { table, seed, depth, bias, preferences, onlyNamed, grounded, indoors, flattened,
                                lookup ->
                AgeBiomeSource(
                    table, seed, depth, bias, preferences, onlyNamed,
                    grounded.orElse(null), indoors.orElse(null), flattened.orElse(null), lookup,
                )
            }
        }

        /**
         * Vanilla's overworld biome table, at the Age's own [seed] — the default for an Age naming no
         * preferences. The *climate* those biomes are looked up at comes from the generator's router, not
         * from here.
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
