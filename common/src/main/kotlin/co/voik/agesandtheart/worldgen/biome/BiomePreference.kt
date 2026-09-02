package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.Constants
import com.mojang.datafixers.util.Pair
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.core.HolderGetter
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.abs

/**
 * What an Age was told to grow, applied to vanilla's climate-to-biome table (design §3.2, §8). A biome
 * word enriches rather than replaces: naming adds it if absent and strengthens it if present.
 *
 * **Weighting happens in climate space rather than by carving out regions**, so a named biome still lands
 * where it belongs — cherry groves in mild bands, a summoned nether biome where the overworld climate most
 * resembles the nether. It also makes exclusion correct: **removing a biome is deleting its entries**,
 * after which vanilla's nearest-neighbour search fills the gap with what is climatically adjacent, so a
 * world without swamps gets more marsh-adjacent forest rather than a hole.
 */
data class BiomePreference(val biome: Identifier, val weight: Double) {
    /**
     * How much of the world this biome should have **against what it would have had anyway** — so
     * [ORDINARY] leaves it alone, twice that gives it twice the climate to answer for, and half that
     * halves it. At or below zero it goes, removal sharing the field rather than carrying a flag, which
     * is what made `except` a parser change.
     */
    val removes: Boolean get() = weight <= 0.0

    /** Whether this asks for anything at all, a biome left at [ORDINARY] being one nobody spoke about. */
    val isOrdinary: Boolean get() = weight == ORDINARY

    companion object {
        /** As much of the world as this Age was going to give it regardless. */
        const val ORDINARY = 1.0

        /**
         * How little of the world a word may push a biome down to without striking it out. An evocative
         * word tilts and never removes (§3.3), so its floor is here rather than at zero.
         */
        const val LEAST_KEPT = 0.2

        /** What `except` is worth — see [removes], and why removal shares the field rather than a flag. */
        const val STRUCK_OUT = 0.0

        val CODEC: Codec<BiomePreference> = RecordCodecBuilder.create { instance ->
            instance.group(
                Identifier.CODEC.fieldOf("biome").forGetter(BiomePreference::biome),
                Codec.DOUBLE.optionalFieldOf("weight", ORDINARY).forGetter(BiomePreference::weight),
            ).apply(instance, ::BiomePreference)
        }

        /**
         * [table] with every preference applied. Removals resolve first, so `except` beats a mention of the
         * same biome whatever order the writer said them in (§3.5). [keepsOnlyNamed] is `only`: everything
         * unnamed goes, and a mention among the survivors still widens.
         */
        fun applied(
            table: Climate.ParameterList<Holder<Biome>>,
            preferences: List<BiomePreference>,
            keepsOnlyNamed: Boolean,
            biomes: HolderGetter<Biome>,
            seed: Long,
        ): Climate.ParameterList<Holder<Biome>> {
            if (preferences.isEmpty() && !keepsOnlyNamed) return table
            val named = preferences.filterNot { it.removes }.map { it.biome }.toSet()
            val removed = preferences.filter { it.removes }.map { it.biome }.toSet()
            val weights = preferences.filterNot { it.removes }.associate { it.biome to it.weight }
            val survives = { entry: Pair<Climate.ParameterPoint, Holder<Biome>> ->
                val id = idOf(entry.second)
                val struckOut = id in removed
                val leftOutOfAnOnly = keepsOnlyNamed && id !in named
                !struckOut && !leftOutOfAnOnly
            }
            // **Add first, restrict second — and that order is the whole of expressing "only this biome".**
            //
            // It used to be the other way round, and the bug it caused is worth keeping written down. `only`
            // filtered vanilla's table and *then* additions were anchored against whatever survived, so naming a
            // biome the table has no entry for — a datapack biome, a nether or End one — emptied it, tripped the
            // guard below, and threw the whole narrowing away. An Age asking for "only plasma" silently got
            // vanilla's twenty-two biomes, complete with their decoration. That is precisely §3.3's silent drop,
            // and it hid behind a warning nobody reads.
            //
            // Anchoring additions against the **unfiltered** table instead makes one mechanism cover everything
            // (Jonah: *"eventually just subsets of biomes too, we need to be able to express all"*):
            //   - `only plasma`        → plasma is added, then everything else is dropped: one biome everywhere.
            //   - `only ocean beach`   → a subset, as before.
            //   - `plasma`             → added to vanilla's table and mingled with it.
            //   - `except desert`      → struck out, as before.
            // The additions are what `entriesFor` earns a biome, including a synthetic climate point for one the
            // table has never heard of — which is exactly why it must see the full table to place it.
            // **Scaled where they stand, not added beside.** A wider box is a biome answering more of the
            // climate cube and a narrower one is it answering less, and only the second needs the entry
            // *replaced*: adding a shrunken copy beside the original leaves the original covering exactly
            // what it did, so "fewer swamps" would read as no change at all.
            val standing = table.values().map { entry ->
                val weight = weights[idOf(entry.second)] ?: ORDINARY
                if (weight == ORDINARY) entry else Pair(entry.first.scaledBy(weight), entry.second)
            }
            val alreadyHere = table.values().mapNotNull { idOf(it.second) }.toSet()
            val added = preferences.filterNot { it.removes || it.biome in alreadyHere }
                .flatMap { preference -> entriesFor(preference, table.values(), biomes, seed) }
            val kept = (standing + added).filter(survives)
            if (kept.isEmpty()) {
                // Still reachable: every biome struck out by `except`, or an `only` naming nothing at all. A world
                // with nothing in it is not a world, so the narrowing is refused wholesale rather than leaving a
                // table that cannot answer a query.
                Constants.LOG.warn("An Age narrowed its biomes down to none; the narrowing is ignored")
                return table
            }
            return Climate.ParameterList(kept)
        }

        /**
         * The entries a biome the table has **never heard of** earns — everything already in it is scaled
         * where it stands instead.
         *
         * - **Known elsewhere:** its points from whichever preset has them, so a nether biome lands where
         *   the overworld climate most resembles the nether.
         * - **Known nowhere:** a seeded synthetic point. End biomes have no climate at all, nor do mod
         *   biomes placed by wrapping the source; they land somewhere arbitrary but *stable*.
         *
         * The count is **normalised against how much of the table a native biome holds**: vanilla's
         * overworld list carries ~60 points for cherry grove and the nether list carries **one** for
         * crimson forest, so a flat multiplier leaves a summoned biome invisible or world-swallowing.
         */
        private fun entriesFor(
            preference: BiomePreference,
            table: List<Pair<Climate.ParameterPoint, Holder<Biome>>>,
            biomes: HolderGetter<Biome>,
            seed: Long,
        ): List<Pair<Climate.ParameterPoint, Holder<Biome>>> {
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
            val halfWidth = quantized(BORROWED_HALF_WIDTH * preference.weight)
            return homes.map { point -> Pair(point.grownTo(halfWidth), holder) }
        }

        /** A biome's climate wherever vanilla knows one — its own dimension's preset, usually. */
        private fun climatePointsFromOtherPresets(biome: Identifier): List<Climate.ParameterPoint> =
            MultiNoiseBiomeSourceParameterList.knownPresets().values
                .flatMap { list -> list.values().filter { it.second.identifier() == biome }.map { it.first } }

        /**
         * Where to put a biome that has no climate anywhere — End biomes, and mod biomes placed by wrapping
         * the source rather than extending the parameter list.
         *
         * **Anchored on a climate the world actually reaches**, never drawn uniformly: the six parameters
         * are noise, so a world's values cluster on a small part of the cube and a uniform point lands
         * where the noise never goes — the biome then exists in the table and nowhere in the ground, which
         * a census caught for `end_highlands`.
         */
        private fun homesFor(
            biome: Identifier,
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
         * One climate box resized about its own middle — wider for a biome asked for, narrower for one
         * spoken against. `depth` is left alone, being the one parameter an Age answers for itself
         * ([ClimateDepth]): resizing it would let a surface biome claim the rock below, or a cave biome
         * the surface.
         */
        private fun Climate.ParameterPoint.scaledBy(weight: Double) = Climate.ParameterPoint(
            temperature().scaledBy(weight),
            humidity().scaledBy(weight),
            continentalness().scaledBy(weight),
            erosion().scaledBy(weight),
            depth(),
            weirdness().scaledBy(weight),
            offset(),
        )

        /**
         * A box never closes to nothing, however faint the claim: the table is searched by nearest
         * neighbour, so a biome with a point in it is still somewhere's answer — being *small* is what
         * makes it rare, and being absent is what `except` is for.
         */
        private fun Climate.Parameter.scaledBy(weight: Double): Climate.Parameter {
            val middle = (min() + max()) / 2
            val half = abs(max() - min()) / 2
            val resized = (half * weight).toLong().coerceAtLeast(NARROWEST_BOX)
            return Climate.Parameter(middle - resized, middle + resized)
        }

        /** Half-width in quantized climate units, below which a box is a point and cannot shrink further. */
        private val NARROWEST_BOX = Climate.quantizeCoord(0.005f)

        /**
         * How wide a niche a biome borrowed from another dimension (or invented for one with no climate)
         * is given, in climate units either side of its centre, before its weight scales it.
         */
        private const val BORROWED_HALF_WIDTH = 0.10

        /**
         * How many climate points a biome from elsewhere is given. A native biome holds dozens, so one
         * entry is invisible however wide its box — `/age biomes` is the only honest way to set this.
         */
        private const val HOMES_FOR_A_BORROWED_BIOME = 96

        /** Spacing between borrowed homes in the table, so they are neighbours rather than one spot. */
        private const val HOME_STRIDE = 7

        /** Depth at or below which an entry is one you can stand on rather than one you have to dig to. */
        private val SURFACE_DEPTH = Climate.quantizeCoord(0.1f)

        private const val BIOME_MIXER = -0x61c8_8646_80b5_83ebL

        private fun idOf(biome: Holder<Biome>): Identifier? = biome.unwrapKey().orElse(null)?.identifier()

        /**
         * Every biome vanilla knows a climate for, across all its presets — overworld *and* nether.
         * Registry-free and static: `knownPresets` builds its lists from `ResourceKey`s through an identity
         * function, so a check can ask this offline without a server.
         */
        val BIOMES_WITH_A_KNOWN_CLIMATE: Set<Identifier> by lazy {
            MultiNoiseBiomeSourceParameterList.knownPresets().values
                .flatMap { list -> list.values().map { it.second.identifier() } }
                .toSet()
        }
    }
}
