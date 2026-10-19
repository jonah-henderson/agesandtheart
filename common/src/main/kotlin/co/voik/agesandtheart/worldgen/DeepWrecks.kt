package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.location
import net.minecraft.core.Holder
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureSet
import net.minecraft.resources.ResourceKey
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootTable

/**
 * What a ship that sank into an abyss carries: vanilla's shipwreck chests, each pointed at a table of ours that
 * holds vanilla's own and more, for getting down to it (Jonah, 2026-10-19).
 *
 * Swapped once the chunk is decorated, because a shipwreck sets its chests' tables from markers inside its own
 * placement, and only once it is placed can a chest be asked whether it lies under the abyss's line. The seed is
 * kept, so the chest rolls as it would have.
 */
object DeepWrecks {

    /** Where an abyssal shipwreck may stand, which is the abyss alone — see `has_structure/abyssal_shipwreck.json`. */
    private val STANDS_IN_THE_ABYSS: TagKey<Biome> =
        TagKey.create(Registries.BIOME, "has_structure/abyssal_shipwreck".location())

    private val ABYSSAL_SHIPWRECKS: ResourceKey<StructureSet> =
        ResourceKey.create(Registries.STRUCTURE_SET, "abyssal_shipwrecks".location())

    private val SHIPWRECK: ResourceKey<Structure> =
        ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("shipwreck"))

    /**
     * The abyss's own shipwrecks, for an Age with an abyss whose [built] sets build shipwrecks at all — so a book
     * that keeps them out keeps them out of the deep as well.
     */
    fun structureSets(server: MinecraftServer, hasAnAbyss: Boolean, built: List<Holder<StructureSet>>): List<Holder<StructureSet>> {
        if (!hasAnAbyss) return emptyList()
        fun buildsShipwrecks(set: Holder<StructureSet>) = set.value().structures().any { it.structure().`is`(SHIPWRECK) }
        if (built.none(::buildsShipwrecks)) return emptyList()
        return listOfNotNull(server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).get(ABYSSAL_SHIPWRECKS).orElse(null))
    }

    /** Whether [structure] is one of the abyss's own, which stand only where the sea bed is under its line. */
    fun standsInTheAbyss(structure: Structure): Boolean =
        structure.biomes().unwrapKey().orElse(null) == STANDS_IN_THE_ABYSS

    private val anywhere = ThreadLocal<HolderSet<Biome>?>()

    /**
     * Run [starting] with the abyss's own structures let into any of [biomes]: a wreck starts on the sea bed, under
     * the abyss's band, so its biome there is never the abyss. The generator keeps only the starts whose bed is under
     * the line, which is the rule the biome stood for.
     */
    fun starting(biomes: Collection<Holder<Biome>>, starting: () -> Unit) {
        val was = anywhere.get()
        anywhere.set(HolderSet.direct(biomes.toList()))
        try {
            starting()
        } finally {
            anywhere.set(was)
        }
    }

    /** The biomes [structure] is tried against while [starting], or null to leave it its own. */
    fun biomesFor(structure: Structure): HolderSet<Biome>? = anywhere.get()?.takeIf { standsInTheAbyss(structure) }

    private val RICHER_UNDER_THE_ABYSS: Map<ResourceKey<LootTable>, ResourceKey<LootTable>> = mapOf(
        BuiltInLootTables.SHIPWRECK_TREASURE to abyssal("shipwreck_treasure"),
        BuiltInLootTables.SHIPWRECK_SUPPLY to abyssal("shipwreck_supply"),
        BuiltInLootTables.SHIPWRECK_MAP to abyssal("shipwreck_map"),
    )

    /** Every shipwreck chest in [chunk] at or under [line], where [abyssReaches] says the abyss stands over it. */
    fun enrich(chunk: ChunkAccess, line: Int, abyssReaches: (Int, Int) -> Boolean) {
        if (line < chunk.minY) return
        for (at in chunk.blockEntitiesPos.toList()) {
            if (at.y > line || !abyssReaches(at.x, at.z)) continue
            val container = chunk.getBlockEntity(at) as? RandomizableContainer ?: continue
            val richer = container.lootTable?.let(RICHER_UNDER_THE_ABYSS::get) ?: continue
            container.setLootTable(richer, container.lootTableSeed)
        }
    }

    private fun abyssal(vanillas: String): ResourceKey<LootTable> =
        ResourceKey.create(Registries.LOOT_TABLE, "chests/abyssal_$vanillas".location())
}
