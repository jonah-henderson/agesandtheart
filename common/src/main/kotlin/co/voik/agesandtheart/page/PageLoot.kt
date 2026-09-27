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
     * A book somebody wrote, and **the only place the grammar is taught** (§4.5).
     *
     * `and`, `only`, `except` and the rungs are structure rather than content, so no page loot hands one
     * out and no device derives one — a writer meets them in a book somebody else wrote, and reading it is
     * what teaches them. Someone who has only ever found *pages* has a vocabulary and no sentences.
     *
     * **Everywhere, and generously, which is temporary** (Jonah, 2026-08-07). The eventual home is a
     * library structure of its own (design backlog), and until that exists a book that is hard to find is
     * a lesson nobody gets taught — so for now it turns up wherever a page does, at a rate chosen for
     * walking rather than for play. [WHILE_UNPLACED] is the one number to drop when the structure lands.
     */
    val DESCRIPTIVE_BOOK: ResourceKey<LootTable> =
        ResourceKey.create(Registries.LOOT_TABLE, "inject/descriptive_book".location())

    /**
     * One of the three D'ni survey reports (design §7.6). Their home is the lost library, and until it
     * exists they are scattered into the chests nearest one in spirit, all of them rarely.
     */
    val SURVEY_REPORT: ResourceKey<LootTable> =
        ResourceKey.create(Registries.LOOT_TABLE, "inject/survey_report".location())

    /**
     * Chosen for places someone once wrote in: libraries and temples over mineshafts, and nothing that
     * would make pages a mob drop. Chances are per-container, not per-chest-slot.
     */
    val TARGETS: List<PageLootTarget> get() = PLACED + everywherePagesAre()

    /**
     * How often a found book turns up in a container that has any of our writing in it at all.
     *
     * Deliberately generous and deliberately temporary: it exists so the grammar can be *walked*, not
     * because half the chests in the world should hold somebody's Age. Drop it, and
     * [everywherePagesAre], when the library structure gives found books a home.
     */
    private const val WHILE_UNPLACED = 0.5f

    /** One found book target per container pages reach, which is the temporary scatter above. */
    private fun everywherePagesAre(): List<PageLootTarget> = PLACED
        .map { it.table }
        .distinct()
        .map { PageLootTarget(it, WHILE_UNPLACED, DESCRIPTIVE_BOOK) }

    private val PLACED: List<PageLootTarget> = listOf(
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

        // Linking books: rarer than pages everywhere, and only where somebody kept their things.
        vanilla("chests/stronghold_library", 0.20f, LINKING_BOOK),
        vanilla("chests/ancient_city", 0.14f, LINKING_BOOK),
        vanilla("chests/woodland_mansion", 0.12f, LINKING_BOOK),
        vanilla("chests/stronghold_corridor", 0.10f, LINKING_BOOK),
        vanilla("chests/desert_pyramid", 0.08f, LINKING_BOOK),
        vanilla("chests/jungle_temple", 0.08f, LINKING_BOOK),
        vanilla("chests/village/village_cartographer", 0.08f, LINKING_BOOK),
        vanilla("chests/simple_dungeon", 0.05f, LINKING_BOOK),

        // Survey reports: a library's papers, so a library first and places a scholar kept things after.
        vanilla("chests/stronghold_library", 0.15f, SURVEY_REPORT),
        vanilla("chests/woodland_mansion", 0.06f, SURVEY_REPORT),
        vanilla("chests/ancient_city", 0.06f, SURVEY_REPORT),
        vanilla("chests/village/village_cartographer", 0.04f, SURVEY_REPORT),
        vanilla("chests/desert_pyramid", 0.03f, SURVEY_REPORT),
        vanilla("chests/jungle_temple", 0.03f, SURVEY_REPORT),
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
