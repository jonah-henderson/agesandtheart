package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.structure.StructureDensity
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * What may be built here — whether anyone ever raised anything in this Age (design §3.1).
 *
 * It rode on the dressing until now, as a `settlement` parameter, and the move was the cheapest of the aspect
 * migration precisely because the *code* already thought of it this way:
 * [co.voik.agesandtheart.age.AgeGeneration] unioned the sets across every dressing territory and said in a
 * comment that structures are deliberately not per-territory. An aspect is what that comment was describing.
 *
 * **The presets are only the base an Age starts from; [BUILT] is where the writing happens** (Jonah): this
 * aspect is *roughly analogous to biomes*, so you may add, emphasise or exclude what gets built, and every
 * structure set carries a derived word of its own. [NONE] and [VANILLA] are therefore not the vocabulary — they
 * are "nothing unless I say so" and "whatever vanilla builds", and a word steers either of them.
 *
 * **A structure *set* is the finest thing a writer names, and that is Jonah's call rather than a limitation
 * we backed into.** Vanilla files structures into sets that share a placement, and within the five overworld
 * sets that hold several, the members are *biome variations* of one idea — a desert village against a plains
 * one, a beached shipwreck against a submerged one. Measured: an Age whose biomes are `only desert` produces
 * desert villages and no others with no structure-level control at all, because vanilla re-rolls a set's
 * selection until something fits the biome. So per-structure control would mostly have been a knob that did
 * nothing, and naming the set is both simpler and honest.
 *
 * The one set whose members genuinely differ is `minecraft:nether_complexes` — a fortress and a bastion share
 * their biomes, so the roll really does decide. That is answered with **data rather than code**: we ship
 * `agesandtheart:fortresses` and `agesandtheart:bastions`, one structure each. Any split anyone ever wants
 * costs two JSON files and no mechanism, which a pack author can do as easily as we can.
 *
 * Naming a set is still not the same as placing it: the generator's state builder drops any set whose
 * structures want a biome this Age cannot produce, so an Age with no jungle gets no jungle temples without
 * anyone having to say so. Naming a *nether* set in an overworld Age is the same story, and pairing it with the
 * biome ("fortresses crimson_forest") is what makes it possible again.
 */
enum class Structures(override val key: String) : AspectPreset {
    /** Nobody ever built here — and nothing is, unless a word names it. */
    NONE("none"),

    /** Vanilla's whole overworld set: villages, temples, monuments, mineshafts, cities and strongholds. */
    VANILLA("vanilla"),
    ;

    override val aspect = Aspect.STRUCTURES

    override val parameters: List<Parameter> get() = listOf(BUILT)

    override fun getSerializedName(): String = key

    /**
     * The sets vanilla may consider here, resolved from the registry the Age is being opened against and
     * steered by whatever the sentence said.
     *
     * Three steps, in an order that makes the outcome independent of the writer's word order (§3.5): `only`
     * drops the base, then everything named joins at the density it asked for, then `except` strikes out.
     */
    fun structureSets(server: MinecraftServer, options: Options): List<Holder<StructureSet>> {
        val sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        val asked = Population.of(options.claimsOn(BUILT))
        val seated = LinkedHashMap<ResourceLocation, Holder<StructureSet>>()
        if (!asked.exclusive) {
            for (key in baseSets()) seated[key.location()] = sets.get(key).orElse(null) ?: continue
        }
        for (claim in asked.wanted) {
            val named = ResourceLocation.tryParse(claim.value) ?: continue
            val found = sets.get(ResourceKey.create(Registries.STRUCTURE_SET, named)).orElse(null)
            if (found == null) {
                Constants.LOG.warn("An Age asked to build '{}', which is no structure set in this pack", named)
                continue
            }
            seated[named] = StructureDensity.applied(found, claim.density)
        }
        for (struck in asked.struck) {
            ResourceLocation.tryParse(struck)?.let(seated::remove)
        }
        return seated.values.toList()
    }

    /** What this preset builds before a word says otherwise. */
    private fun baseSets(): List<ResourceKey<StructureSet>> = when (this) {
        NONE -> emptyList()
        VANILLA -> OVERWORLD_STRUCTURE_SETS
    }

    companion object {
        /**
         * What is built here — **populative**, so naming one adds it and naming two adds both, with
         * `only`/`except` to narrow and a [Density] rung to say how many (design §3.2, and [Claim]).
         *
         * Its values are **structure sets**: `minecraft:villages`, `minecraft:woodland_mansions`. See the note
         * on the class for why that is the right grain rather than the structures inside them.
         *
         * Named `built` rather than `structures`: the referent-plural convention that gives the dressing
         * `biomes` would spell this `structures.structures`, and the redundancy costs a reader more than the
         * small inconsistency does.
         */
        val BUILT = Parameter.population("built")

        /**
         * Vanilla's overworld sets, and **ours splitting the nether complexes** — the base [VANILLA] means.
         *
         * Leaving the nether and end sets out is honesty rather than filtering: no overworld biome could ever
         * admit them, so an Age reaches them by naming them *and* the biomes that would have them.
         */
        private val OVERWORLD_STRUCTURE_SETS = listOf(
            BuiltinStructureSets.VILLAGES,
            BuiltinStructureSets.DESERT_PYRAMIDS,
            BuiltinStructureSets.IGLOOS,
            BuiltinStructureSets.JUNGLE_TEMPLES,
            BuiltinStructureSets.SWAMP_HUTS,
            BuiltinStructureSets.PILLAGER_OUTPOSTS,
            BuiltinStructureSets.OCEAN_MONUMENTS,
            BuiltinStructureSets.WOODLAND_MANSIONS,
            BuiltinStructureSets.BURIED_TREASURES,
            BuiltinStructureSets.MINESHAFTS,
            BuiltinStructureSets.RUINED_PORTALS,
            BuiltinStructureSets.SHIPWRECKS,
            BuiltinStructureSets.OCEAN_RUINS,
            BuiltinStructureSets.ANCIENT_CITIES,
            BuiltinStructureSets.STRONGHOLDS,
            BuiltinStructureSets.TRAIL_RUINS,
            BuiltinStructureSets.TRIAL_CHAMBERS,
        )

        /**
         * Our own halves of `minecraft:nether_complexes`, so a fortress can be asked for without a bastion.
         *
         * **Their numbers are arithmetic, not taste, and JSON cannot hold the working — so it lives here.**
         * Vanilla puts both on one grid of `spacing 27, separation 4` and picks between them by weight,
         * fortress 2 against bastion 3. Two independent grids would otherwise place a fortress *and* a bastion
         * at every site, doubling the nether's complexes and overlapping them, so each set is spaced to carry
         * only the share it used to win:
         *
         * - fortress: 2/5 of the sites → `27 / sqrt(0.4)` ≈ 43, separation `4 × 43/27` ≈ 6
         * - bastion: 3/5 of the sites → `27 / sqrt(0.6)` ≈ 35, separation `4 × 35/27` ≈ 5
         *
         * The salts are vanilla's plus one and two, which is the point rather than laziness: they must differ
         * from each other *and* from `nether_complexes`, or the grids would coincide and put the two structures
         * back on the same chunks.
         */
        val FORTRESSES: ResourceKey<StructureSet> = ours("fortresses")
        val BASTIONS: ResourceKey<StructureSet> = ours("bastions")

        private fun ours(path: String): ResourceKey<StructureSet> =
            ResourceKey.create(Registries.STRUCTURE_SET, path.location())
    }
}
