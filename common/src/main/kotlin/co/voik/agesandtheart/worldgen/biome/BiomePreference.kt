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
            // A biome from elsewhere is given a home in climate this world actually reaches, and its own
            // dimension's coordinates are deliberately not used.
            //
            // Two censuses were needed to see why. A nether biome's parameters describe *the Nether's*
            // climate field, and reusing them here assumes the two spaces are comparable — they are not, so
            // the point lands wherever those numbers happen to fall in ours, which is usually somewhere the
            // noise never visits. Worse, its home preset gives one point where a native biome has dozens, so
            // even a well-placed entry loses to seven thousand neighbours. Both censuses showed a summoned
            // biome changing the world not at all.
            //
            // So: anchor on entries already in the table, which are reachable by construction, and take
            // enough of them to compete. End biomes reach this path too and always did — they have no
            // climate anywhere, and it turns out neither does anything else, in any sense that helps.
            val homes = homesFor(preference.biome, table, seed)
            val halfWidth = quantized(BORROWED_HALF_WIDTH * (1.0 + preference.weight))
            return homes.map { point -> Pair(point.grownTo(halfWidth), holder) }
        }

        /** A biome's climate wherever vanilla knows one — its own dimension's preset, usually. */
        private fun climatePointsFromOtherPresets(biome: ResourceLocation): List<Climate.ParameterPoint> =
            MultiNoiseBiomeSourceParameterList.knownPresets().values
                .flatMap { list -> list.values().filter { it.second.location() == biome }.map { it.first } }

        /**
         * Where to put a biome that has no climate anywhere — End biomes, and mod biomes placed by wrapping
         * the biome source rather than extending the parameter list.
         *
         * **Anchored on a climate the world actually reaches**, not drawn uniformly at random. The six
         * parameters are noise, so the values a world visits cluster on a small part of the cube; a uniform
         * point lands in a region the noise never produces, and the biome then exists in the table and
         * nowhere in the ground — which is what a census caught for `end_highlands`. Borrowing an existing
         * entry's coordinates guarantees somewhere reachable, and the seed and the biome's own name pick
         * *which*, so it is stable for an Age and different between Ages.
         */
        private fun homesFor(
            biome: ResourceLocation,
            table: List<Pair<Climate.ParameterPoint, Holder<Biome>>>,
            seed: Long,
        ): List<Climate.ParameterPoint> {
            // Surface entries only. The table is mostly *cave* biomes by count, and an anchor taken from
            // those carries their depth — a third census caught a summoned biome sitting at depth 1.0,
            // which no surface sample can ever reach, so it existed in the table and nowhere anybody walks.
            val atSurface = table.filter { it.first.depth().min() <= SURFACE_DEPTH }
            val homes = atSurface.ifEmpty { table }
            val random = XoroshiroRandomSource(seed xor (biome.hashCode().toLong() * BIOME_MIXER))
            // Several, clustered around one anchor rather than scattered: a biome should arrive as a *place*
            // somewhere in the world, not as confetti through every climate it happens to be nearest to.
            val anchor = random.nextInt(homes.size)
            return (0..<HOMES_FOR_A_BORROWED_BIOME).map { step ->
                homes[(anchor + step * HOME_STRIDE) % homes.size].first
            }
        }

        /** Every axis widened to the same half-width about its own middle, leaving depth alone. */
        private fun Climate.ParameterPoint.grownTo(halfWidth: Long) = Climate.ParameterPoint(
            temperature().grownTo(halfWidth),
            humidity().grownTo(halfWidth),
            continentalness().grownTo(halfWidth),
            erosion().grownTo(halfWidth),
            depth(),
            weirdness().grownTo(halfWidth),
            offset(),
        )

        private fun Climate.Parameter.grownTo(halfWidth: Long): Climate.Parameter {
            val middle = (min() + max()) / 2
            return Climate.Parameter(middle - halfWidth, middle + halfWidth)
        }

        private fun quantized(climateUnits: Double): Long = Climate.quantizeCoord(climateUnits.toFloat())

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
        private const val WIDENING_PER_WEIGHT = 1.5

        /**
         * How wide a niche a biome borrowed from another dimension (or invented for one with no climate)
         * is given, in climate units either side of its centre, before its weight scales it.
         */
        private const val BORROWED_HALF_WIDTH = 0.10

        /**
         * How many climate points a biome from elsewhere is given.
         *
         * A native biome holds dozens — cherry grove has sixty — so one entry is invisible however wide its
         * box. This is the number that decides whether a summoned biome is findable at all; a census is the
         * only honest way to set it.
         */
        private const val HOMES_FOR_A_BORROWED_BIOME = 96

        /** Spacing between borrowed homes in the table, so they are neighbours rather than one spot. */
        private const val HOME_STRIDE = 7

        /** Depth at or below which an entry is one you can stand on rather than one you have to dig to. */
        private val SURFACE_DEPTH = Climate.quantizeCoord(0.1f)

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
