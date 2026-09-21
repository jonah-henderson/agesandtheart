package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.TheEndBiomeSource
import net.minecraft.world.level.biome.BiomeResolver
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext
import java.util.Optional
import java.util.stream.Stream

/**
 * **An Age's own way of seeing whatever world it grows its biomes from** — the template's, or a table of
 * ours built from one.
 *
 * It wraps a [BiomeSource] rather than owning a climate table, and that is the whole of what makes an
 * infernal Age grow nether biomes: the base every Age layered onto used to be *hardcoded to the overworld*,
 * so a world with the nether's rock had the overworld's biomes over it and the overworld's features with
 * them — geodes in the nether (Jonah, 2026-08-14, walked).
 *
 * **What it changes, it changes on the way in.** The sampler the world below is handed is this Age's own:
 * the climate is bent where a book bent it, read off the ground where the Age is grounded, and given the
 * Age's own depth. Which biome those numbers pick is then entirely the world below's business — so any
 * source works, including the End's, which chooses by a rule of its own and has no climate to bend.
 *
 * **Silence costs nothing.** An Age that bends no climate, is grounded in nothing and takes vanilla's own
 * depth hands the sampler straight back, and the world below runs exactly as it would have alone.
 */
class AgeBiomeSource(
    /**
     * The world these biomes come from — a template's own, or a table of ours. See
     * [co.voik.agesandtheart.age.AgeTemplate.biomesOf].
     */
    private val under: BiomeSource,
    /**
     * How buried a point is, which is the one climate parameter our worlds answer for themselves.
     * [AsSampled] is vanilla's own answer passed through, and is what a template's rock wants.
     */
    private val depth: ClimateDepth = AsSampled,
    /** How the book bent the climate, and where — see [RegionalClimate]. */
    private val bent: RegionalClimate = RegionalClimate.NONE,
    /** What the Age's own shape says about its climate, where it has a shape of its own. */
    private val grounding: Grounding? = null,
    /**
     * Biomes no climate can pick, because a *place* is not a climate — see [BiomeBand].
     *
     * A list rather than one, since the great halls were the first to want this and the abyss is the
     * second. Read in order, so the first band covering a height wins.
     */
    private val bands: List<BiomeBand> = emptyList(),
) : BiomeSource() {

    override fun codec(): MapCodec<out BiomeSource> = CODEC

    fun sampledForDepth(): AgeBiomeSource = AgeBiomeSource(under, AsSampled, bent, grounding, bands)

    fun groundedIn(terrain: TerrainField): AgeBiomeSource =
        AgeBiomeSource(under, BelowTerrain(terrain), bent, grounding, bands)

    fun suitedTo(grounding: Grounding?): AgeBiomeSource = AgeBiomeSource(under, depth, bent, grounding, bands)

    fun banded(bands: List<BiomeBand>): AgeBiomeSource = AgeBiomeSource(under, depth, bent, grounding, bands)

    fun told(bent: RegionalClimate): AgeBiomeSource = AgeBiomeSource(under, depth, bent, grounding, bands)

    /**
     * **A factory now, where 26.3 asked a source for one biome at a time.** That is what retired the memo
     * this class used to keep: the wrappers below were built per lookup and remembered against the sampler
     * they came from, and a resolver is built once per pass, so there is nothing left to remember.
     */
    override fun createResolver(climate: Climate.Sampler): BiomeResolver = resolverOver(under.createResolver(asThisAgeSeesIt(climate)))

    /**
     * Forwarded rather than left to the default, because `MultiNoiseBiomeSource` overrides this to work a
     * chunk at a time and falling through would throw that away.
     */
    override fun createResolverForChunk(
        climate: Climate.Sampler,
        fromQuartX: Int,
        fromQuartY: Int,
        fromQuartZ: Int,
        toQuartX: Int,
        toQuartY: Int,
        toQuartZ: Int,
    ): BiomeResolver = resolverOver(
        under.createResolverForChunk(
            asThisAgeSeesIt(climate), fromQuartX, fromQuartY, fromQuartZ, toQuartX, toQuartY, toQuartZ,
        ),
    )

    private fun resolverOver(beneath: BiomeResolver) = BiomeResolver { quartX, quartY, quartZ ->
        // Answered before any climate is consulted at all, because it is not a climate question: the halls
        // are a *place*, and no table has a coordinate meaning "indoors" or "under eighty blocks of sea".
        val blockX = QuartPos.toBlock(quartX)
        val blockY = QuartPos.toBlock(quartY)
        val blockZ = QuartPos.toBlock(quartZ)
        bands.firstNotNullOfOrNull { band -> band.biomeAt(blockX, blockY, blockZ) }
            ?: beneath.getNoiseBiome(quartX, quartY, quartZ)
    }

    /** The world below's own, plus anything only a [BiomeBand] hands out — in no table, so it must be said. */
    override fun collectPossibleBiomes(): Stream<Holder<Biome>> =
        Stream.concat(under.possibleBiomes().stream(), bands.stream().map(BiomeBand::biome))

    /** Whether anything here would move a single number. */
    private val bendsNothing: Boolean
        get() = depth == AsSampled && grounding == null && bent.isIdle

    /**
     * **The End must be handed the sampler it was given, not one of ours.**
     *
     * It picks by distance from the centre and a single reading of erosion, so there is no table here to
     * bend and nothing is lost by leaving it alone. What is *gained* is that it still works: Fabric's
     * biome API hangs its own End overrides off a seed that a `Climate.Sampler` only carries when a
     * `RandomState` made it, so handing it one built here throws `MultiNoiseSampler doesn't have a seed
     * set` out of chunk generation. A dark void Age never met it — bending nothing, it passed the sampler
     * on already — and naming a landform is what starts the wrapping (Jonah, 2026-08-15, walked).
     */
    private val underPicksByItsOwnRule: Boolean get() = under is TheEndBiomeSource

    /** Whether the world below is better served by the sampler it was handed. */
    private val handsTheSamplerOn: Boolean get() = bendsNothing || underPicksByItsOwnRule

    /**
     * The climate sampler as this Age reads it — the same six numbers, bent, grounded and re-deepened.
     *
     * **It used to be remembered against the sampler it was made from**, because these were built per
     * biome lookup and six wrappers a lookup is a few thousand allocations a chunk. 26.3 asks a source for
     * a *resolver* instead, built once per pass, so there is nothing left to remember and the memo and its
     * `@Volatile` both go.
     */
    private fun asThisAgeSeesIt(climate: Climate.Sampler): Climate.Sampler {
        if (handsTheSamplerOn) return climate

        fun biasAt(blockX: Int, blockZ: Int) =
            bent.at(QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockZ))

        return Climate.Sampler(
            // Bent first, then chilled by however far this column stands above the Age's floor — the bias is
            // what the writer asked for and the lapse is what the mountain does to it, so a warm Age still
            // has cold summits and a cold one has colder. See [Elevation].
            asThisAgeReadsIt(climate.temperature()) { blockX, _, blockZ, vanillas ->
                val warmth = biasAt(blockX, blockZ).shift(ClimateAxis.TEMPERATURE, vanillas)
                grounding?.temperatureAt(blockX, blockZ, warmth) ?: warmth
            },
            asThisAgeReadsIt(climate.humidity()) { blockX, _, blockZ, vanillas ->
                biasAt(blockX, blockZ).shift(ClimateAxis.HUMIDITY, vanillas)
            },
            // Continentalness describes shape, and an Age's shape is the field tree's rather than the
            // climate's — so by default it passes through untouched and the two simply disagree. A
            // *grounded* Age reads it off the shape instead. See [Grounding] and [ClimateAxis].
            asThisAgeReadsIt(climate.continentalness()) { blockX, _, blockZ, vanillas ->
                grounding?.continentalnessAt(blockX, blockZ) ?: vanillas
            },
            // Erosion means how worn flat the ground is, so a grounded Age reads it off its own fall rather
            // than off a noise that never saw the terrain — which decides a sandy beach from a stony shore.
            asThisAgeReadsIt(climate.erosion()) { blockX, _, blockZ, vanillas ->
                grounding?.erosionAt(blockX, blockZ) ?: vanillas
            },
            depthAsThisAgeReadsIt(climate.depth()),
            // Weirdness passes through too. A grounded Age pushes it into the valley band where its own
            // rivers run, which is where vanilla files them. See [ClimateAxis].
            asThisAgeReadsIt(climate.weirdness()) { blockX, _, blockZ, vanillas ->
                grounding?.weirdnessAt(blockX, blockZ, vanillas) ?: vanillas
            },
        )
    }

    /** One axis of the climate sampler, read the way this Age reads it. */
    private fun asThisAgeReadsIt(
        vanillas: DensitySampler.Bound,
        read: (Int, Int, Int, Float) -> Float,
    ): DensitySampler.Bound = bound(vanillas) { x, y, z -> read(x, y, z, vanillas.sampleValue(x, y, z)) }

    /**
     * Depth, which alone decides whether to *ask* the world below at all — two of the three depths answer
     * from their own rock, and computing vanilla's for them walks a density tree per lookup to throw the
     * answer away.
     */
    private fun depthAsThisAgeReadsIt(vanillas: DensitySampler.Bound) = bound(vanillas) { x, y, z ->
        val sampled = if (!depth.readsVanillas) UNREAD else vanillas.sampleValue(x, y, z)
        depth.at(x, y, z, sampled)
    }

    companion object {
        val CODEC: MapCodec<AgeBiomeSource> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("under").forGetter { it.under },
                ClimateDepth.CODEC.optionalFieldOf("depth", AsSampled).forGetter { it.depth },
                RegionalClimate.CODEC.optionalFieldOf("bias", RegionalClimate.NONE).forGetter { it.bent },
                Grounding.CODEC.optionalFieldOf("grounding").forGetter { Optional.ofNullable(it.grounding) },
                // Empty for every Age with nothing indoors and no abyss, which is most of them.
                BiomeBand.CODEC.listOf().optionalFieldOf("bands", emptyList()).forGetter { it.bands },
            ).apply(instance) { under, depth, bias, grounded, bands ->
                AgeBiomeSource(under, depth, bias, grounded.orElse(null), bands)
            }
        }
    }
}

/**
 * One climate axis as an Age reads it, wrapping the world's own.
 *
 * **A sampler rather than a density function**, which is the whole of what a climate sampler asks for: a
 * value at a position. 26.3 split the two, and this never needed the function half — no codec, no bounds,
 * and nothing to rewrite beneath it. It carries the wrapped axis's own context, so it is asked wherever
 * that one would have been.
 */
private fun bound(vanillas: DensitySampler.Bound, read: (Int, Int, Int) -> Float): DensitySampler.Bound =
    DensitySampler.Bound(
        object : DensitySampler {
            override fun sampleValue(context: SamplerContext, blockX: Int, blockY: Int, blockZ: Int): Float =
                read(blockX, blockY, blockZ)

            override fun sampleVolume(context: SamplerContext, into: DensityBuffer, over: DensityVolume) =
                DensitySampler.sampleVolumeNaive(context, into, over, this)
        },
        vanillas.context(),
    )

/** What stands in for vanilla's depth where the Age's own depth never reads it — see [ClimateDepth.readsVanillas]. */
private const val UNREAD = 0.0f
