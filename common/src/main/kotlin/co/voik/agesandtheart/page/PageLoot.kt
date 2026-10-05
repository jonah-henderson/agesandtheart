package co.voik.agesandtheart.page

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.NestedLootTable
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition
import net.minecraft.core.HolderLookup
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders

/** One container kind that carries writing, which table it draws from, and how often. */
data class PageLootTarget(
    val table: ResourceKey<LootTable>,
    val chance: Float,
    val injected: ResourceKey<LootTable>,
)

/**
 * Where pages turn up.
 *
 * Only the *targets* live here. What a page find actually yields is the datapack table
 * `agesandtheart:inject/pages`, so retuning the reward needs no code — and any pack can put pages in its
 * own tables with the `roll_page_word` loot function instead of going through this at all.
 */
object PageLoot {
    /** Loose pages: one or two, any rarity. */
    val PAGES: ResourceKey<LootTable> =
        ResourceKey.create(Registries.LOOT_TABLE, "inject/pages".location())

    /** Someone else's filled notebook. Rarer, and worth far more at once. */
    val NOTEBOOK: ResourceKey<LootTable> =
        ResourceKey.create(Registries.LOOT_TABLE, "inject/notebook".location())

    /**
     * A way home, left by someone who had one. Rarer than pages by some way — a page is a word, this is
     * the thing that decides whether you dare go anywhere.
     *
     * Seeded at all because a linking book you must *craft* cannot be your first one: fine ink and fine
     * paper are mid-game, and going somewhere is early-game. Later these belong in the library structures
     * rather than scattered (design backlog), and the scatter should thin out as that lands.
     */
    val LINKING_BOOK: ResourceKey<LootTable> =
        ResourceKey.create(Registries.LOOT_TABLE, "inject/linking_book".location())

    /**
     * Chosen for places someone once wrote in: libraries and temples over mineshafts, and nothing that
     * would make pages a mob drop. Chances are per-container, not per-chest-slot.
     */
    val TARGETS: List<PageLootTarget> get() = PLACED

    private val PLACED: List<PageLootTarget> = listOf(
        // Pages: about half what they were while vanilla's chests were the only place to learn a word.
        vanilla("chests/stronghold_library", 0.40f),
        vanilla("chests/ancient_city", 0.25f),
        vanilla("chests/woodland_mansion", 0.25f),
        vanilla("chests/desert_pyramid", 0.20f),
        vanilla("chests/jungle_temple", 0.20f),
        vanilla("chests/bastion_treasure", 0.20f),
        vanilla("chests/stronghold_crossing", 0.15f),
        vanilla("chests/stronghold_corridor", 0.15f),
        vanilla("chests/pillager_outpost", 0.15f),
        vanilla("chests/underwater_ruin_big", 0.15f),
        vanilla("chests/village/village_temple", 0.15f),
        vanilla("chests/village/village_cartographer", 0.15f),
        vanilla("chests/ruined_portal", 0.10f),
        vanilla("chests/simple_dungeon", 0.10f),
        vanilla("chests/abandoned_mineshaft", 0.10f),
        vanilla("chests/shipwreck_map", 0.10f),

        // Notebooks: someone's whole collection, so only where a person kept one, and rarely.
        vanilla("chests/stronghold_library", 0.12f, NOTEBOOK),
        vanilla("chests/ancient_city", 0.08f, NOTEBOOK),
        vanilla("chests/woodland_mansion", 0.07f, NOTEBOOK),
        vanilla("chests/village/village_cartographer", 0.05f, NOTEBOOK),
        vanilla("chests/bastion_treasure", 0.04f, NOTEBOOK),

        // Linking books: rarer than pages everywhere, and only where somebody kept their things.
        vanilla("chests/stronghold_library", 0.20f, LINKING_BOOK),
        vanilla("chests/ancient_city", 0.14f, LINKING_BOOK),
        vanilla("chests/woodland_mansion", 0.12f, LINKING_BOOK),
        vanilla("chests/stronghold_corridor", 0.10f, LINKING_BOOK),
        vanilla("chests/desert_pyramid", 0.08f, LINKING_BOOK),
        vanilla("chests/jungle_temple", 0.08f, LINKING_BOOK),
        vanilla("chests/village/village_cartographer", 0.08f, LINKING_BOOK),
        vanilla("chests/simple_dungeon", 0.05f, LINKING_BOOK),
    )

    /** Every target for a table, since pages and a notebook may both reach the same container. */
    fun targetsFor(table: ResourceKey<LootTable>): List<PageLootTarget> = TARGETS.filter { it.table == table }

    /**
     * A pool that rolls the target's table at its chance.
     *
     * **[registries] rather than the key alone**: 26.3 has a nested entry name a `Holder` where it named a
     * `ResourceKey`, so the table has to be resolved rather than pointed at. Fabric's own loot-modify
     * event hands the lookup over, which is the only caller — NeoForge injects through datapack loot
     * modifiers instead and never reaches this.
     */
    fun poolFor(target: PageLootTarget, registries: HolderLookup.Provider): LootPool = LootPool.lootPool()
        .setRolls(ContextIntProviders.exactly(ONE_ROLL))
        .`when`(LootItemRandomChanceCondition.randomChance(target.chance))
        .add(NestedLootTable.lootTableReference(registries.lookupOrThrow(Registries.LOOT_TABLE).getOrThrow(target.injected)))
        .build()

    /** One roll of the injected table; how often it yields anything is the chance above. */
    private const val ONE_ROLL = 1

    private fun vanilla(path: String, chance: Float, injected: ResourceKey<LootTable> = PAGES) =
        PageLootTarget(
            ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace(path)),
            chance,
            injected,
        )
}
