package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.structure.StructureDensity
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.StructureSet as VanillaStructureSet

/**
 * What may be built here (design §3.1) — **whatever vanilla would**, until a sentence says otherwise.
 *
 * No preset, for the same reason biomes have none: an Age does not pick one of two ways to be inhabited,
 * it starts from what the game builds and a sentence adjusts it. [BUILT] is where the writing happens,
 * naming a set asks for it, a rung says how many, and `only`/`except` are what trim.
 *
 * **That baseline is a behaviour change, made deliberately** (Jonah, 2026-08-04): habitation used to be
 * opt-in through a readiness prior on a `vanilla` preset, so an Age nobody spoke to about building was
 * usually empty. Now it is furnished by default and `untouched` is what empties it. Habitability (§7.6)
 * is expected to take the question back and change this again; what it needs first is a baseline that
 * does not depend on a draw.
 *
 * Naming a set is not the same as placing it: the generator's state builder drops any set whose structures
 * want a biome this Age cannot produce.
 */
object Structures {

    /** Every Age has these, whatever its sentence said — see [escapeHatch]. */
    private val STAR_FISSURE: Identifier = "star_fissure".location()

    /**
     * **Built here rather than shipped as a `structure_set`, and that is not a style choice.** A structure
     * set in the registry applies to *every* dimension whose generator does not name its own — so shipping
     * one tore star fissures across the player's overworld and handed them a free teleport home from it.
     * Walked, found at 422 blocks from spawn, and this is the fix.
     *
     * A direct holder no registry has heard of is exactly what `StructureDensity` already produces and what
     * `AgeChunkGenerator.createState` branches on, so an Age takes it and nothing else can.
     */
    private val FISSURE_PLACEMENT = RandomSpreadStructurePlacement(
        FISSURES_APART,
        FISSURES_NO_CLOSER,
        RandomSpreadType.LINEAR,
        FISSURE_SALT,
    )

    /** Rare enough to be a find, close enough to be a promise — about seven hundred blocks apart. */
    private const val FISSURES_APART = 48
    private const val FISSURES_NO_CLOSER = 20
    private const val FISSURE_SALT = 90210

    /**
     * The sets vanilla may consider here, steered by whatever the sentence said. Three steps, ordered so
     * the outcome is independent of the writer's word order (§3.5): `only` (or [NOTHING]) drops the base,
     * everything named joins at its density, then `except` strikes out.
     */
    fun structureSets(
        server: MinecraftServer,
        options: Options,
        standing: List<ResourceKey<VanillaStructureSet>> = OVERWORLD_STRUCTURE_SETS,
        /**
         * What the *world* asks for more of, by set id and by how many times as often — the abyss wanting
         * wrecks on its floor, and anything later with the same shape of claim.
         *
         * **Applied only where the writer said nothing about that set**, which is the precedence that
         * matters: a sentence naming a density is a person deciding, and a rule derived from the shape of
         * the world must not talk over one.
         */
        thickened: Map<Identifier, Double> = emptyMap(),
    ): List<Holder<VanillaStructureSet>> {
        val sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        val asked = Skew.of(options.claimsOn(BUILT))
        val seated = LinkedHashMap<Identifier, Holder<VanillaStructureSet>>()
        // Sets a writer named, which [thickened] may not talk over.
        val spokenFor = HashSet<Identifier>()
        val startsFromNothing = asked.exclusive || asked.wanted.any { it.value == NOTHING }
        if (!startsFromNothing) {
            for (key in standing) seated[key.identifier()] = sets.get(key).orElse(null) ?: continue
        }
        for (claim in asked.wanted) {
            // `nothing` is the emptier, not a set: it has already done its work above, and asking the
            // registry for it warned that the pack ships no `minecraft:nothing` on every Age ever written.
            if (claim.value == NOTHING) continue
            val named = Identifier.tryParse(claim.value) ?: continue
            // **A description only reweighs what is built here already** — see [Claim.onlyWhereItGrows].
            if (claim.onlyWhereItGrows && named !in seated) continue
            val found = sets.get(ResourceKey.create(Registries.STRUCTURE_SET, named)).orElse(null)
            if (found == null) {
                Constants.LOG.warn("An Age asked to build '{}', which is no structure set in this pack", named)
                continue
            }
            seated[named] = StructureDensity.applied(server, found, claim.density)
            spokenFor += named
        }
        for ((id, factor) in thickened) {
            if (id in spokenFor) continue
            seated[id]?.let { seated[id] = StructureDensity.applied(server, it, factor) }
        }
        for (struck in asked.struck) {
            Identifier.tryParse(struck)?.let(seated::remove)
        }
        return seated.values.toList() + escapeHatch(server)
    }

    /**
     * The star fissures, added **after everything a writer said** and never removed by it (design §7.8).
     *
     * This looks like a special case and is the opposite of one: `only`, `except` and `nothing` all reach
     * this list, so a sentence saying "no structures" would otherwise delete the one way out of the Age it
     * was describing. An escape hatch a writer can accidentally close is not an escape hatch, and closing it
     * *deliberately* is a late-game word rather than a side effect of saying something about villages.
     *
     * Not `seated`, so a claim naming it cannot double it or strike it either.
     */
    private fun escapeHatch(server: MinecraftServer): List<Holder<VanillaStructureSet>> {
        val structures = server.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        val key = ResourceKey.create(Registries.STRUCTURE, STAR_FISSURE)
        val tear = structures.get(key).orElse(null)
        if (tear == null) {
            Constants.LOG.error("The pack ships no '{}', so this Age has no way out of it", STAR_FISSURE)
            return emptyList()
        }
        return listOf(Holder.direct(VanillaStructureSet(tear, FISSURE_PLACEMENT)))
    }

    /**
     * What is built here — populative, with `only`/`except` to narrow and a rung to say how many
     * (§3.2, [Claim]). Its values are structure *sets*. Named `built` to avoid `structures.structures`.
     *
     * A mention is worth the **ordinary** amount of a set, unlike a biome's: a set is opt-in, so naming
     * one asks for a thing that was not there rather than for more of a thing that was. And it may be
     * emptied, a world nobody ever built in being a world (`leastKept`), where every column must have
     * some biome whatever a word thinks of it.
     */
    val BUILT = Pool(
        "built",
        leastKept = NOTHING_AT_ALL,
        emptiedBy = NOTHING,
        help = "Which structures are built here.",
    )

    /**
     * How an Age says nobody ever built here: `built=nothing`, which drops the base whatever vanilla adds
     * to it later. A word that strikes out every set the Art can reach resolves to this rather than to a
     * list of exclusions as long as the pack.
     */
    const val NOTHING = "nothing"

    /** A world nobody ever built in is a world, so a set may be pushed all the way to none of it. */
    private const val NOTHING_AT_ALL = 0.0

    /**
     * The base an Age starts from. Nether and end sets are left out because no overworld biome could
     * admit them — an Age reaches them by naming them *and* the biomes that would have them.
     */
    val OVERWORLD_STRUCTURE_SETS = listOf(
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
}
