package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.structure.StructureDensity
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.StructureSet

/**
 * What may be built here (design §3.1). The presets are only the base an Age starts from; [BUILT] is
 * where the writing happens, so [NONE] and [VANILLA] mean "nothing unless I say so" and "whatever vanilla
 * builds", and a word steers either.
 *
 * A structure *set* is the finest grain a writer names: within the overworld sets holding several, the
 * members are biome variations of one idea, and vanilla re-rolls a set's selection until something fits
 * the biome — so per-structure control would mostly be a knob that did nothing. The one set whose members
 * genuinely differ is `minecraft:nether_complexes`, split with data rather than code (see [FORTRESSES]).
 *
 * Naming a set is not the same as placing it: the generator's state builder drops any set whose structures
 * want a biome this Age cannot produce.
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
     * The sets vanilla may consider here, steered by whatever the sentence said. Three steps, ordered so
     * the outcome is independent of the writer's word order (§3.5): `only` drops the base, everything
     * named joins at its density, then `except` strikes out.
     */
    fun structureSets(server: MinecraftServer, options: Options): List<Holder<StructureSet>> {
        val sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        val asked = Population.of(options.claimsOn(BUILT))
        val seated = LinkedHashMap<Identifier, Holder<StructureSet>>()
        if (!asked.exclusive) {
            for (key in baseSets()) seated[key.identifier()] = sets.get(key).orElse(null) ?: continue
        }
        for (claim in asked.wanted) {
            val named = Identifier.tryParse(claim.value) ?: continue
            val found = sets.get(ResourceKey.create(Registries.STRUCTURE_SET, named)).orElse(null)
            if (found == null) {
                Constants.LOG.warn("An Age asked to build '{}', which is no structure set in this pack", named)
                continue
            }
            seated[named] = StructureDensity.applied(found, claim.density)
        }
        for (struck in asked.struck) {
            Identifier.tryParse(struck)?.let(seated::remove)
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
         * What is built here — populative, with `only`/`except` to narrow and a [Density] rung to say how
         * many (§3.2, [Claim]). Its values are structure *sets*. Named `built` to avoid
         * `structures.structures`.
         */
        val BUILT = Parameter.population("built")

        /**
         * The base [VANILLA] means. Nether and end sets are left out because no overworld biome could admit
         * them — an Age reaches them by naming them *and* the biomes that would have them.
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
         * Their spacings are arithmetic, and the working lives here because JSON cannot hold it. Vanilla
         * puts both on one grid of `spacing 27, separation 4`, picking by weight fortress 2 : bastion 3.
         * Two independent grids would place both at every site, so each is spaced to carry only its old
         * share — fortress `27/sqrt(0.4)` ≈ 43 sep 6, bastion `27/sqrt(0.6)` ≈ 35 sep 5. The salts must
         * differ from each other *and* from `nether_complexes`, or the grids coincide.
         */
        val FORTRESSES: ResourceKey<StructureSet> = ours("fortresses")
        val BASTIONS: ResourceKey<StructureSet> = ours("bastions")

        private fun ours(path: String): ResourceKey<StructureSet> =
            ResourceKey.create(Registries.STRUCTURE_SET, path.location())
    }
}
