package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.Constants
import com.mojang.datafixers.util.Pair
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.core.HolderGetter
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.abs

/**
 * What an Age was told to grow, applied to vanilla's climate-to-biome table (design §3.2, §8).
 *
 * A biome word does **not** replace an Age's biomes; it enriches them — "adds it if it wasn't there
 * already, or makes it more likely if it was" (Jonah). That is the *populative* half of §3.2 and the
 * precision ladder applied to a list: naming shifts weights, and `only`/`except` narrow, arriving with the
 * grammar rather than as machinery of their own.
 *
 * **Weighting happens in climate space rather than by carving out regions.** A named biome keeps landing
 * where it belongs — cherry groves in mild bands, a summoned nether biome where the overworld climate most
 * resembles the nether — so the world still reads as a place rather than an assembly of patches. It also
 * makes exclusion trivial and correct: **removing a biome is deleting its entries**, after which vanilla's
 * nearest-neighbour search fills the gap with whatever is climatically adjacent. A world without swamps
 * gets more marsh-adjacent forest, not a hole.
 */
data class BiomePreference(val biome: ResourceLocation, val weight: Double) {
    /**
     * Signed, in the same language as a word's tag query (§3.3, "queries may push away as well as pull"):
     * positive strengthens or introduces, anything at or below zero removes.
     *
     * Keeping removal in the same field rather than a separate flag is what makes `except` a *parser*
     * change later instead of a mechanism change.
     */
    val removes: Boolean get() = weight <= 0.0

    companion object {
        /** What a bare mention is worth, before any quantifier exists to say otherwise. */
        const val WEIGHT_OF_A_MENTION = 1.0

        val CODEC: Codec<BiomePreference> = RecordCodecBuilder.create { instance ->
            instance.group(
                ResourceLocation.CODEC.fieldOf("biome").forGetter(BiomePreference::biome),
                Codec.DOUBLE.optionalFieldOf("weight", WEIGHT_OF_A_MENTION).forGetter(BiomePreference::weight),
            ).apply(instance, ::BiomePreference)
        }

        /**
         * [table] with every preference applied.
         *
         * Removals are resolved first, so `except` beats a mention of the same biome rather than depending
         * on which the writer said first — §3.5's rule that word order decides nothing, applied here.
         */
        fun applied(
            table: Climate.ParameterList<Holder<Biome>>,
            preferences: List<BiomePreference>,
            biomes: HolderGetter<Biome>,
            seed: Long,
        ): Climate.ParameterList<Holder<Biome>> {
            if (preferences.isEmpty()) return table
            val removed = preferences.filter { it.removes }.map { it.biome }.toSet()
            val kept = table.values().filterNot { entry -> idOf(entry.second) in removed }
            if (kept.isEmpty()) {
                // Every biome struck out. A world with nothing in it is not a world, so the exclusions are
                // refused wholesale rather than leaving a table that cannot answer a query at all.
                Constants.LOG.warn("An Age excluded every biome it had; the exclusions are ignored")
                return table
            }
            val added = preferences.filterNot { it.removes }
                .flatMap { preference -> entriesFor(preference, kept, biomes, seed) }
            return Climate.ParameterList(kept + added)
        }

        /**
         * The entries one strengthened biome earns — which is three different jobs wearing one name.
         *
         * - **Already here:** widened copies of its own points. Duplicates would win nothing, since the
         *   table is searched by nearest neighbour; only a wider box covers more climate, and covering more
         *   climate is exactly what "more likely" means.
         * - **Known elsewhere:** its points from whichever preset does have them, so a nether biome lands
         *   where the overworld climate most resembles the nether. This is what makes cross-dimension
         *   mixing nearly free.
         * - **Known nowhere:** a seeded synthetic point. End biomes have no climate at all (the End does
         *   not use a multi-noise source) and nor do mod biomes placed by wrapping the biome source. They
         *   land somewhere arbitrary but *stable*, which is impossible geography rather than a bug.
         *
         * The widening count is **normalised against how much of the table the biome already holds**, which
         * the access probe showed is not a detail: vanilla's overworld list carries ~60 points for cherry
         * grove and the nether list carries **one** for crimson forest. A flat multiplier would leave a
         * summoned nether biome either invisible or swallowing the world, depending on nothing the writer
         * said.
         */
        private fun entriesFor(
            preference: BiomePreference,
            table: List<Pair<Climate.ParameterPoint, Holder<Biome>>>,
            biomes: HolderGetter<Biome>,
            seed: Long,
        ): List<Pair<Climate.ParameterPoint, Holder<Biome>>> {
            val reach = 1.0 + preference.weight * WIDENING_PER_WEIGHT
            val own = table.filter { idOf(it.second) == preference.biome }
            if (own.isNotEmpty()) {
                return own.map { entry -> Pair(entry.first.widenedBy(reach), entry.second) }
            }

            val holder = biomes.get(ResourceKey.create(Registries.BIOME, preference.biome)).orElse(null)
                ?: run {
                    Constants.LOG.warn("An Age asked for biome '{}', which this pack does not have", preference.biome)
                    return emptyList()
                }
            val elsewhere = climatePointsFromOtherPresets(preference.biome)
            if (elsewhere.isEmpty()) {
                return listOf(Pair(syntheticPoint(preference.biome, seed).widenedBy(reach), holder))
            }
            // Normalised: a biome arriving with one coarse box gets the same footing as one arriving with
            // sixty fine ones, rather than however many its home dimension happened to spend on it.
            val share = CLIMATE_POINTS_FOR_A_BORROWED_BIOME.toDouble() / elsewhere.size
            return elsewhere.map { point -> Pair(point.widenedBy(reach * share), holder) }
        }

        /** A biome's climate wherever vanilla knows one — its own dimension's preset, usually. */
        private fun climatePointsFromOtherPresets(biome: ResourceLocation): List<Climate.ParameterPoint> =
            MultiNoiseBiomeSourceParameterList.knownPresets().values
                .flatMap { list -> list.values().filter { it.second.location() == biome }.map { it.first } }

        /**
         * Somewhere in climate space for a biome that has no climate anywhere, drawn from the Age's seed
         * and the biome's own name so it is stable for a given Age and different between Ages.
         */
        private fun syntheticPoint(biome: ResourceLocation, seed: Long): Climate.ParameterPoint {
            val random = XoroshiroRandomSource(seed xor (biome.hashCode().toLong() * BIOME_MIXER))
            fun axis(): Climate.Parameter {
                val middle = random.nextDouble().toFloat() * 2f - 1f
                return Climate.Parameter(
                    Climate.quantizeCoord(middle - SYNTHETIC_HALF_WIDTH),
                    Climate.quantizeCoord(middle + SYNTHETIC_HALF_WIDTH),
                )
            }
            return Climate.ParameterPoint(axis(), axis(), axis(), axis(), FULL_DEPTH, axis(), 0L)
        }

        /**
         * One climate box grown about its own middle.
         *
         * `depth` is left alone: it is the one parameter an Age answers for itself ([ClimateDepth]), and
         * widening it would let a surface biome claim the rock below or a cave biome surface — which reads
         * as broken rather than as strange.
         */
        private fun Climate.ParameterPoint.widenedBy(reach: Double) = Climate.ParameterPoint(
            temperature().widenedBy(reach),
            humidity().widenedBy(reach),
            continentalness().widenedBy(reach),
            erosion().widenedBy(reach),
            depth(),
            weirdness().widenedBy(reach),
            offset(),
        )

        private fun Climate.Parameter.widenedBy(reach: Double): Climate.Parameter {
            val middle = (min() + max()) / 2
            val half = abs(max() - min()) / 2
            val grown = (half * reach).toLong().coerceAtLeast(half)
            return Climate.Parameter(middle - grown, middle + grown)
        }

        /** How much of a box's half-width one unit of weight adds. Taste; expect Jonah to retune it. */
        private const val WIDENING_PER_WEIGHT = 0.6

        /** How many points' worth of climate a biome summoned from another dimension is given. */
        private const val CLIMATE_POINTS_FOR_A_BORROWED_BIOME = 8

        /** How wide a synthetic box is, in climate units, before weighting widens it further. */
        private const val SYNTHETIC_HALF_WIDTH = 0.15f

        /** A synthetic biome spans the whole column, since nothing tells us where it belongs vertically. */
        private val FULL_DEPTH = Climate.Parameter(Climate.quantizeCoord(-1f), Climate.quantizeCoord(1f))

        private const val BIOME_MIXER = -0x61c8_8646_80b5_83ebL

        private fun idOf(biome: Holder<Biome>): ResourceLocation? = biome.unwrapKey().orElse(null)?.location()

        /**
         * Every biome vanilla knows a climate for, across all its presets — overworld *and* nether.
         *
         * Registry-free and static, which the access probe confirmed: `knownPresets` builds its lists from
         * `ResourceKey`s through an identity function, so a check can ask this offline without a server.
         */
        val BIOMES_WITH_A_KNOWN_CLIMATE: Set<ResourceLocation> by lazy {
            MultiNoiseBiomeSourceParameterList.knownPresets().values
                .flatMap { list -> list.values().map { it.second.location() } }
                .toSet()
        }
    }
}
