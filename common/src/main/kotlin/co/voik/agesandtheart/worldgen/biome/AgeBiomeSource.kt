package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.util.KeyDispatchDataCodec
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.TheEndBiomeSource
import net.minecraft.world.level.levelgen.DensityFunction
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
    /** The world these biomes come from — a template's own, or a table of ours. See [BiomeTables]. */
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
    /** The one biome no climate can pick, because "indoors" is a place rather than a climate. */
    private val roofed: Roofed? = null,
) : BiomeSource() {

    override fun codec(): MapCodec<out BiomeSource> = CODEC

    fun sampledForDepth(): AgeBiomeSource = AgeBiomeSource(under, AsSampled, bent, grounding, roofed)

    fun groundedIn(terrain: TerrainField): AgeBiomeSource =
        AgeBiomeSource(under, BelowTerrain(terrain), bent, grounding, roofed)

    fun suitedTo(grounding: Grounding?): AgeBiomeSource = AgeBiomeSource(under, depth, bent, grounding, roofed)

    fun roofedBy(roofed: Roofed?): AgeBiomeSource = AgeBiomeSource(under, depth, bent, grounding, roofed)

    fun told(bent: RegionalClimate): AgeBiomeSource = AgeBiomeSource(under, depth, bent, grounding, roofed)

    override fun getNoiseBiome(quartX: Int, quartY: Int, quartZ: Int, climate: Climate.Sampler): Holder<Biome> {
        // Answered before any climate is consulted at all, because it is not a climate question: the halls
        // are a *place*, and no table has a coordinate that means "indoors". See [roofed].
        roofed?.biomeAt(QuartPos.toBlock(quartY))?.let { return it }
        return under.getNoiseBiome(quartX, quartY, quartZ, asThisAgeSeesIt(climate))
    }

    /** The world below's own, plus anything only [roofed] hands out — which is in no table and must be said. */
    override fun collectPossibleBiomes(): Stream<Holder<Biome>> =
        Stream.concat(under.possibleBiomes().stream(), Stream.ofNullable(roofed?.biome))

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
     * [climate] as this Age reads it — the same six numbers, bent, grounded and re-deepened.
     *
     * **Remembered against the sampler it was made from.** One sampler serves a level, and building six
     * wrappers per biome lookup would be a few thousand allocations a chunk. The check is by identity and
     * the worst a race can do is build one twice.
     */
    @Volatile
    private var seenThrough: Pair<Climate.Sampler, Climate.Sampler>? = null

    private fun asThisAgeSeesIt(climate: Climate.Sampler): Climate.Sampler {
        if (handsTheSamplerOn) return climate
        seenThrough?.let { (given, made) -> if (given === climate) return made }

        fun biasAt(context: DensityFunction.FunctionContext) =
            bent.at(QuartPos.fromBlock(context.blockX()), QuartPos.fromBlock(context.blockZ()))

        val made = Climate.Sampler(
            // Bent first, then chilled by however far this column stands above the Age's floor — the bias is
            // what the writer asked for and the lapse is what the mountain does to it, so a warm Age still
            // has cold summits and a cold one has colder. See [Elevation].
            asThisAgeReadsIt(climate.temperature()) { context, vanillas ->
                val warmth = biasAt(context).shift(ClimateAxis.TEMPERATURE, vanillas)
                grounding?.temperatureAt(context.blockX(), context.blockZ(), warmth) ?: warmth
            },
            asThisAgeReadsIt(climate.humidity()) { context, vanillas ->
                biasAt(context).shift(ClimateAxis.HUMIDITY, vanillas)
            },
            // Continentalness describes shape, and an Age's shape is the field tree's rather than the
            // climate's — so by default it passes through untouched and the two simply disagree. A
            // *grounded* Age reads it off the shape instead. See [Grounding] and [ClimateAxis].
            asThisAgeReadsIt(climate.continentalness()) { context, vanillas ->
                grounding?.continentalnessAt(context.blockX(), context.blockZ()) ?: vanillas
            },
            // Erosion means how worn flat the ground is, so a grounded Age reads it off its own fall rather
            // than off a noise that never saw the terrain — which decides a sandy beach from a stony shore.
            asThisAgeReadsIt(climate.erosion()) { context, vanillas ->
                grounding?.erosionAt(context.blockX(), context.blockZ()) ?: vanillas
            },
            depthAsThisAgeReadsIt(climate.depth()),
            // Weirdness passes through too. A grounded Age pushes it into the valley band where its own
            // rivers run, which is where vanilla files them. See [ClimateAxis].
            asThisAgeReadsIt(climate.weirdness()) { context, vanillas ->
                grounding?.weirdnessAt(context.blockX(), context.blockZ(), vanillas) ?: vanillas
            },
            climate.spawnTarget(),
        )
        seenThrough = climate to made
        return made
    }

    /** One axis of [climate], read the way this Age reads it. */
    private fun asThisAgeReadsIt(vanillas: DensityFunction, read: (DensityFunction.FunctionContext, Float) -> Float) =
        AsThisAgeReadsIt(vanillas) { context -> read(context, vanillas.compute(context).toFloat()).toDouble() }

    /**
     * Depth, which alone decides whether to *ask* the world below at all — two of the three depths answer
     * from their own rock, and computing vanilla's for them walks a density tree per lookup to throw the
     * answer away.
     */
    private fun depthAsThisAgeReadsIt(vanillas: DensityFunction) = AsThisAgeReadsIt(vanillas) { context ->
        val sampled = if (!depth.readsVanillas) UNREAD else vanillas.compute(context).toFloat()
        depth.at(context.blockX(), context.blockY(), context.blockZ(), sampled).toDouble()
    }

    companion object {
        val CODEC: MapCodec<AgeBiomeSource> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("under").forGetter { it.under },
                ClimateDepth.CODEC.optionalFieldOf("depth", AsSampled).forGetter { it.depth },
                RegionalClimate.CODEC.optionalFieldOf("bias", RegionalClimate.NONE).forGetter { it.bent },
                Grounding.CODEC.optionalFieldOf("grounding").forGetter { Optional.ofNullable(it.grounding) },
                // Absent for every Age with nothing indoors, which is almost all of them.
                Roofed.CODEC.optionalFieldOf("roofed").forGetter { Optional.ofNullable(it.roofed) },
            ).apply(instance) { under, depth, bias, grounded, indoors ->
                AgeBiomeSource(under, depth, bias, grounded.orElse(null), indoors.orElse(null))
            }
        }
    }
}

/**
 * One climate axis as an Age reads it, wrapping the world's own.
 *
 * A [DensityFunction.SimpleFunction] because that is the whole of what a sampler asks of one: a value at a
 * position. It is never serialised — a sampler is built per level from the level's own router, and this is
 * built from that.
 */
private class AsThisAgeReadsIt(
    private val vanillas: DensityFunction,
    private val read: (DensityFunction.FunctionContext) -> Double,
) : DensityFunction.SimpleFunction {
    override fun compute(context: DensityFunction.FunctionContext): Double = read(context)

    // **Wider than the axis, deliberately.** These bound an optimiser rather than the answer, and a bend
    // may carry a value past whatever the world below would have produced on its own.
    override fun minValue(): Double = minOf(vanillas.minValue(), LOWEST)
    override fun maxValue(): Double = maxOf(vanillas.maxValue(), HIGHEST)

    override fun codec(): KeyDispatchDataCodec<out DensityFunction> =
        error("an Age's own climate is built for one level's sampler and is never serialised")

    private companion object {
        /** Vanilla's climate axes live in −2..2, and a bend cannot carry one past the end of its own axis. */
        const val LOWEST = -2.0
        const val HIGHEST = 2.0
    }
}

/** What stands in for vanilla's depth where the Age's own depth never reads it — see [ClimateDepth.readsVanillas]. */
private const val UNREAD = 0.0f
