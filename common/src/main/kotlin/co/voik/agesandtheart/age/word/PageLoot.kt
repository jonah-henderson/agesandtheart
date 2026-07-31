package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.NestedLootTable
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue

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
     * Chosen for places someone once wrote in: libraries and temples over mineshafts, and nothing that
     * would make pages a mob drop. Chances are per-container, not per-chest-slot.
     */
    val TARGETS: List<PageLootTarget> = listOf(
        vanilla("chests/stronghold_library", 0.85f),
        vanilla("chests/ancient_city", 0.55f),
        vanilla("chests/woodland_mansion", 0.50f),
        vanilla("chests/desert_pyramid", 0.45f),
        vanilla("chests/jungle_temple", 0.45f),
        vanilla("chests/bastion_treasure", 0.40f),
        vanilla("chests/stronghold_crossing", 0.35f),
        vanilla("chests/stronghold_corridor", 0.35f),
        vanilla("chests/pillager_outpost", 0.30f),
        vanilla("chests/underwater_ruin_big", 0.30f),
        vanilla("chests/village/village_temple", 0.30f),
        vanilla("chests/village/village_cartographer", 0.30f),
        vanilla("chests/ruined_portal", 0.25f),
        vanilla("chests/simple_dungeon", 0.25f),
        vanilla("chests/abandoned_mineshaft", 0.20f),
        vanilla("chests/shipwreck_map", 0.20f),

        // Notebooks: someone's whole collection, so only where a person kept one, and rarely.
        vanilla("chests/stronghold_library", 0.12f, NOTEBOOK),
        vanilla("chests/ancient_city", 0.08f, NOTEBOOK),
        vanilla("chests/woodland_mansion", 0.07f, NOTEBOOK),
        vanilla("chests/village/village_cartographer", 0.05f, NOTEBOOK),
        vanilla("chests/bastion_treasure", 0.04f, NOTEBOOK),
    )

    /** Every target for a table, since pages and a notebook may both reach the same container. */
    fun targetsFor(table: ResourceKey<LootTable>): List<PageLootTarget> = TARGETS.filter { it.table == table }

    /** A pool that rolls the target's table at its chance. */
    fun poolFor(target: PageLootTarget): LootPool = LootPool.lootPool()
        .setRolls(ConstantValue.exactly(1.0f))
        .`when`(LootItemRandomChanceCondition.randomChance(target.chance))
        .add(NestedLootTable.lootTableReference(target.injected))
        .build()

    private fun vanilla(path: String, chance: Float, injected: ResourceKey<LootTable> = PAGES) =
        PageLootTarget(
            ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace(path)),
            chance,
            injected,
        )
}
