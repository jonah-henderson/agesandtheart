package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.FillNotebookFunction
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.PageWordFunction
import co.voik.agesandtheart.age.word.grammar.Said
import co.voik.agesandtheart.book.BindLinkingBookFunction
import co.voik.agesandtheart.book.BookEntity
import co.voik.agesandtheart.book.LinkTarget
import co.voik.agesandtheart.book.RepatternBookRecipe
import co.voik.agesandtheart.worldgen.fissure.StarFissureBlock
import co.voik.agesandtheart.worldgen.fissure.StarFissureBlockEntity
import co.voik.agesandtheart.desk.WritersDeskBlock
import co.voik.agesandtheart.desk.WritersDeskBlockEntity
import co.voik.agesandtheart.desk.InkCaseMenu
import co.voik.agesandtheart.desk.SupplyBinMenu
import co.voik.agesandtheart.desk.WritersDeskMenu
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.field.NearTheSurface
import co.voik.agesandtheart.worldgen.field.RegionRule
import co.voik.agesandtheart.worldgen.carver.Porosity
import co.voik.agesandtheart.worldgen.carver.RuleCarver
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import co.voik.agesandtheart.worldgen.fissure.StarFissurePiece
import co.voik.agesandtheart.worldgen.fissure.StarFissureStructure
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.MenuType
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.carver.CarverConfiguration
import net.minecraft.world.level.levelgen.carver.WorldCarver
import net.minecraft.world.level.storage.loot.functions.LootItemFunction

/**
 * The mod's registered content, defined loader-agnostically.
 *
 * Instances are built eagerly here (constructing them needs no registry); the *registration*
 * is driven per loader — Fabric registers directly during init, NeoForge via `RegisterEvent` —
 * with each loader iterating [components], [items], and [chunkGeneratorCodecs]. See each loader's
 * entrypoint.
 */
object AgeContent {
    /**
     * Data component stored on a Descriptive Book stack: the id of the Age it links to.
     * `persistent` = saved to disk; `networkSynchronized` = sent to the client.
     */
    val AGE_ID: DataComponentType<Identifier> = DataComponentType.builder<Identifier>()
        .persistent(Identifier.CODEC)
        .networkSynchronized(Identifier.STREAM_CODEC)
        .build()

    /**
     * **An item must know its own id before it is constructed.** `Item.Properties.setId` is not optional:
     * the constructor derives the description id and the component initialisers from it and throws
     * "Item id not set" without one. So the id is named here and the registration below reuses it, rather
     * than being spelled twice.
     */
    private val DESCRIPTIVE_BOOK_ID: Identifier = "descriptive_book".location()

    /** Unstackable so each book keeps its own [AGE_ID] identity. */
    val DESCRIPTIVE_BOOK: Item = DescriptiveBookItem(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, DESCRIPTIVE_BOOK_ID))
            .stacksTo(1),
    )

    /** The word written on a page. Rolled when the page is generated, never when it is read. */
    val PAGE_WORD: DataComponentType<Identifier> = DataComponentType.builder<Identifier>()
        .persistent(Identifier.CODEC)
        .networkSynchronized(Identifier.STREAM_CODEC)
        .build()

    private val PAGE_ID: Identifier = "page".location()

    /** Stacks: two pages of the same word are the same page, and differing words never merge anyway. */
    val PAGE: Item = PageItem(
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, PAGE_ID)),
    )

    private val NOTEBOOK_ID: Identifier = "notebook".location()

    /** Unstackable — it holds its own pages. */
    val NOTEBOOK: Item = NotebookItem(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, NOTEBOOK_ID))
            .stacksTo(1),
    )

    private val FINE_PAPER_ID: Identifier = "fine_paper".location()
    private val MASTERWORK_PAPER_ID: Identifier = "masterwork_paper".location()

    /**
     * The better papers. Common paper is not an item of ours at all — it is the `common_paper` tag, so
     * vanilla paper and any modded equivalent already qualify. The upper two are ours by design: the
     * player must make *these*, which is what stops the soft axis being bought at a village.
     */
    val FINE_PAPER: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FINE_PAPER_ID)))

    val MASTERWORK_PAPER: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, MASTERWORK_PAPER_ID)))

    private val ANALYSIS_MACHINE_ID: Identifier = "analysis_machine".location()

    /** See [AnalysisMachineBlock] — the station half of acquaintance learning. */
    val ANALYSIS_MACHINE_BLOCK: AnalysisMachineBlock = AnalysisMachineBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ANALYSIS_MACHINE_ID))
            .mapColor(MapColor.COLOR_PURPLE)
            .strength(ANALYSIS_MACHINE_STRENGTH)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops(),
    )

    val ANALYSIS_MACHINE: Item = BlockItem(
        ANALYSIS_MACHINE_BLOCK,
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, ANALYSIS_MACHINE_ID))
            .useBlockDescriptionPrefix(),
    )

    private const val ANALYSIS_MACHINE_STRENGTH = 3.5f

    private val SURVEYING_DEVICE_ID: Identifier = "surveying_device".location()

    /** See [SurveyingDeviceItem] — the portable half. One per writer is plenty, so it does not stack. */
    val SURVEYING_DEVICE: Item = SurveyingDeviceItem(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, SURVEYING_DEVICE_ID))
            .stacksTo(1),
    )

    /** Where a Linking Book goes. Absent means blank — see [LinkingBookItem]. */
    val LINK_TARGET: DataComponentType<LinkTarget> = DataComponentType.builder<LinkTarget>()
        .persistent(LinkTarget.CODEC)
        .networkSynchronized(LinkTarget.STREAM_CODEC)
        .build()

    private val LINKING_BOOK_ID: Identifier = "linking_book".location()

    /** Unstackable: each one is a different door, even before it is written in. */
    val LINKING_BOOK: Item = LinkingBookItem(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, LINKING_BOOK_ID))
            .stacksTo(1),
    )

    /**
     * The pages a notebook holds, oldest first and **uncapped**.
     *
     * Not `BUNDLE_CONTENTS`: a bundle's capacity is enforced in a private weight check, which would cap a
     * notebook at sixty-four pages — a pocket rather than a catalogue.
     *
     * **Read and written only through [NotebookItem]**, which is not a style preference: this was set as
     * vanilla's `CONTAINER` in one place and asked for here in every other, so a found notebook held its
     * pages where nothing could see them and opened empty.
     */
    val NOTEBOOK_PAGES: DataComponentType<List<ItemStack>> = DataComponentType.builder<List<ItemStack>>()
        .persistent(ItemStack.CODEC.listOf())
        .networkSynchronized(ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()))
        .build()

    private val INK_BOTTLE_ID: Identifier = "ink_bottle".location()

    /**
     * Ink by the bottle — how the desk is filled before anyone has a pump.
     *
     * Squid ink is deliberately *not* accepted by the desk directly; it becomes black dye and then a
     * bottle, so the cheap route still passes through a step the player performs.
     */
    val INK_BOTTLE: Item = Item(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, INK_BOTTLE_ID))
            .craftRemainder(Items.GLASS_BOTTLE)
            .stacksTo(16),
    )

    private val FINE_INK_BOTTLE_ID: Identifier = "fine_ink_bottle".location()

    /**
     * Fine ink by the bottle — the unit a recipe can actually name, where the tank holds a fluid.
     *
     * Masterwork has none yet: nothing needs to *craft* with it, and adding a bottle nobody consumes
     * would be an item to explain rather than an item to use.
     */
    val FINE_INK_BOTTLE: Item = Item(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, FINE_INK_BOTTLE_ID))
            .craftRemainder(Items.GLASS_BOTTLE)
            .stacksTo(16),
    )

    /** Which ink a bottle holds. One place, so the desk and the recipes cannot disagree. */
    val INK_BOTTLES: Map<InkTier, Item> = mapOf(
        InkTier.COMMON to INK_BOTTLE,
        InkTier.FINE to FINE_INK_BOTTLE,
    )

    private val WRITERS_DESK_ID: Identifier = "writers_desk".location()

    /** Three blocks wide; see [co.voik.agesandtheart.desk.WritersDeskBlock]. */
    val WRITERS_DESK_BLOCK: WritersDeskBlock = WritersDeskBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, WRITERS_DESK_ID))
            .mapColor(MapColor.WOOD)
            .strength(2.5f)
            .sound(SoundType.WOOD)
            .noOcclusion(),
    )

    val WRITERS_DESK: Item = BlockItem(
        WRITERS_DESK_BLOCK,
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, WRITERS_DESK_ID))
            .useBlockDescriptionPrefix(),
    )

    /** Built here but registered per loader, like everything else in this object. */
    val WRITERS_DESK_ENTITY: BlockEntityType<WritersDeskBlockEntity> =
        BlockEntityType({ pos, state -> WritersDeskBlockEntity(pos, state) }, setOf(WRITERS_DESK_BLOCK))

    private val STAR_FISSURE_ID: Identifier = "star_fissure".location()

    /**
     * What a star fissure is filled with — see [co.voik.agesandtheart.worldgen.fissure.StarFissureBlock].
     *
     * **No block item and no loot**, deliberately: a fissure is a thing an Age has, not a thing a player
     * carries, and one in a pocket would undo the whole of §7.8's found-not-carried argument. Indestructible
     * for the same reason — an escape hatch you can accidentally mine shut is not one.
     */
    val STAR_FISSURE_BLOCK: StarFissureBlock = StarFissureBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, STAR_FISSURE_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .noCollision()
            .lightLevel { FISSURE_GLOW }
            .strength(-1.0f, Float.MAX_VALUE)
            .noLootTable()
            .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK),
    )

    /** The tear itself, and the piece that cuts it — see the fissure package. */
    val STAR_FISSURE_STRUCTURE: StructureType<StarFissureStructure> = StructureType { StarFissureStructure.CODEC }

    val STAR_FISSURE_PIECE: StructurePieceType = StructurePieceType.ContextlessType(::StarFissurePiece)

    val structureTypes: List<Pair<Identifier, StructureType<*>>> = listOf(
        STAR_FISSURE_ID to STAR_FISSURE_STRUCTURE,
    )

    val structurePieces: List<Pair<Identifier, StructurePieceType>> = listOf(
        STAR_FISSURE_ID to STAR_FISSURE_PIECE,
    )

    val STAR_FISSURE_ENTITY: BlockEntityType<StarFissureBlockEntity> =
        BlockEntityType({ pos, state -> StarFissureBlockEntity(pos, state) }, setOf(STAR_FISSURE_BLOCK))

    /** The end portal's own, so the shaft is lit the way the starfield reads best. */
    private const val FISSURE_GLOW = 15

    /** Where a linked-from book comes to rest. See [co.voik.agesandtheart.book.BookEntity]. */
    val BOOK_ENTITY: EntityType<BookEntity> = EntityType.Builder
        .of({ type, level -> BookEntity(type, level) }, MobCategory.MISC)
        .sized(BOOK_ENTITY_WIDTH, BOOK_ENTITY_HEIGHT)
        .clientTrackingRange(BOOK_TRACKING_CHUNKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "descriptive_book".location()))

    private const val BOOK_ENTITY_WIDTH = 0.4f
    private const val BOOK_ENTITY_HEIGHT = 0.15f
    private const val BOOK_TRACKING_CHUNKS = 8

    val entities: List<Pair<Identifier, EntityType<*>>> = listOf(
        "descriptive_book".location() to BOOK_ENTITY,
    )

    val blocks: List<Pair<Identifier, Block>> = listOf(
        WRITERS_DESK_ID to WRITERS_DESK_BLOCK,
        STAR_FISSURE_ID to STAR_FISSURE_BLOCK,
        ANALYSIS_MACHINE_ID to ANALYSIS_MACHINE_BLOCK,
    )

    val blockEntities: List<Pair<Identifier, BlockEntityType<*>>> = listOf(
        WRITERS_DESK_ID to WRITERS_DESK_ENTITY,
        STAR_FISSURE_ID to STAR_FISSURE_ENTITY,
    )

    /** The sentence a Descriptive Book carries, in order — page order is word order. */
    val BOOK_WORDS: DataComponentType<List<Identifier>> = DataComponentType.builder<List<Identifier>>()
        .persistent(Identifier.CODEC.listOf())
        .networkSynchronized(Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()))
        .build()

    /** What its writer called the Age. */
    val BOOK_TITLE: DataComponentType<String> = DataComponentType.builder<String>()
        .persistent(Codec.STRING)
        .networkSynchronized(ByteBufCodecs.STRING_UTF8)
        .build()

    /**
     * The seed the Age this book makes will be written at — chosen at the **desk**, not when the book is
     * first opened (see [co.voik.agesandtheart.desk.WritingSeed]).
     *
     * On the book rather than only on its writer, so what the desk showed and what the Age turns out to be
     * cannot drift apart: a book changes hands, waits in a chest, and is opened by somebody else.
     *
     * Absent on a found book or one bound before this existed, and `DescriptiveBookRecipe` falls back to
     * seeding from the Age's id there — which is what every book did until now.
     */
    val BOOK_SEED: DataComponentType<Long> = DataComponentType.builder<Long>()
        .persistent(Codec.LONG)
        .networkSynchronized(ByteBufCodecs.VAR_LONG)
        .build()

    /**
     * What the book **says**, column by column — [Readout][co.voik.agesandtheart.age.word.grammar.Readout]'s
     * reading, with the particles a writer was spared for being inferable from position.
     *
     * Written down rather than derived, because reading a sentence takes the whole corpus and a client has
     * none. Column by column rather than as prose, because a book sets the script over its reading **word
     * for word**, and running them together would leave nothing to line up.
     */
    val BOOK_READING: DataComponentType<List<Said>> = DataComponentType.builder<List<Said>>()
        .persistent(Said.CODEC.listOf())
        .networkSynchronized(Said.STREAM_CODEC.apply(ByteBufCodecs.list()))
        .build()

    val WRITERS_DESK_MENU: MenuType<WritersDeskMenu> = MenuType(
        { containerId, inventory -> WritersDeskMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    val INK_CASE_MENU: MenuType<InkCaseMenu> = MenuType(
        { containerId, inventory -> InkCaseMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    val SUPPLY_BIN_MENU: MenuType<SupplyBinMenu> = MenuType(
        { containerId, inventory -> SupplyBinMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    val menus: List<Pair<Identifier, MenuType<*>>> = listOf(
        WRITERS_DESK_ID to WRITERS_DESK_MENU,
        "ink_case".location() to INK_CASE_MENU,
        "supply_bin".location() to SUPPLY_BIN_MENU,
    )

    val recipeSerializers: List<Pair<Identifier, RecipeSerializer<*>>> =
        listOf("repattern_descriptive_book".location() to RepatternBookRecipe.SERIALIZER)

    val components: List<Pair<Identifier, DataComponentType<*>>> = listOf(
        "age_id".location() to AGE_ID,
        "page_word".location() to PAGE_WORD,
        "book_words".location() to BOOK_WORDS,
        "book_title".location() to BOOK_TITLE,
        "book_seed".location() to BOOK_SEED,
        "book_reading".location() to BOOK_READING,
        "link_target".location() to LINK_TARGET,
        "notebook_pages".location() to NOTEBOOK_PAGES,
    )

    val items: List<Pair<Identifier, Item>> = listOf(
        DESCRIPTIVE_BOOK_ID to DESCRIPTIVE_BOOK,
        PAGE_ID to PAGE,
        NOTEBOOK_ID to NOTEBOOK,
        WRITERS_DESK_ID to WRITERS_DESK,
        LINKING_BOOK_ID to LINKING_BOOK,
        INK_BOTTLE_ID to INK_BOTTLE,
        FINE_INK_BOTTLE_ID to FINE_INK_BOTTLE,
        FINE_PAPER_ID to FINE_PAPER,
        MASTERWORK_PAPER_ID to MASTERWORK_PAPER,
        ANALYSIS_MACHINE_ID to ANALYSIS_MACHINE,
        SURVEYING_DEVICE_ID to SURVEYING_DEVICE,
    )

    /** Chunk-generator codecs (Ages persist via Fantasy, so their generator must be serializable). */
    val chunkGeneratorCodecs: List<Pair<Identifier, MapCodec<out ChunkGenerator>>> = listOf(
        "spire".location() to SpireChunkGenerator.CODEC,
        // Renamed from `field` with the class: the generator reaches past field terrain now. Save formats
        // are still free to move (CLAUDE.md), so this is a rename rather than an alias.
        "age".location() to AgeChunkGenerator.CODEC,
    )

    /**
     * Biome-source codecs. Like the generators, an Age's biome source is persisted with it, so the kind
     * has to be nameable — `BiomeSource.CODEC` dispatches over this registry.
     */
    val biomeSourceCodecs: List<Pair<Identifier, MapCodec<out BiomeSource>>> = listOf(
        "age_biomes".location() to AgeBiomeSource.CODEC,
    )

    /**
     * Surface-rule kinds. Ours is persisted with the Age like the generator, so the kind has to be
     * nameable — `RuleSource.CODEC` dispatches over this registry.
     */
    val surfaceRuleCodecs: List<Pair<Identifier, MapCodec<out SurfaceRules.RuleSource>>> = listOf(
        "region".location() to RegionRule.CODEC,
    )

    /**
     * Surface-*condition* kinds, which is the same story one level down: the palette naming this condition
     * is persisted, so `ConditionSource.CODEC` has to be able to dispatch to it.
     */
    val surfaceConditionCodecs: List<Pair<Identifier, MapCodec<out SurfaceRules.ConditionSource>>> = listOf(
        NearTheSurface.ID to NearTheSurface.CODEC.codec(),
    )

    /**
     * Loot-function kinds. What makes pages ordinary loot: a pack puts
     * `{ "function": "agesandtheart:roll_page_word" }` on an item entry in any table it authors.
     */
    val lootFunctions: List<Pair<Identifier, MapCodec<out LootItemFunction>>> = listOf(
        "roll_page_word".location() to PageWordFunction.MAP_CODEC,
        "fill_notebook".location() to FillNotebookFunction.MAP_CODEC,
        "bind_linking_book".location() to BindLinkingBookFunction.MAP_CODEC,
    )

    /**
     * Our own carvers. The configured instances that use them are datapack JSON under
     * `data/agesandtheart/worldgen/configured_carver/`; this registers the carver *kinds* those refer to.
     */
    val carvers: List<Pair<Identifier, WorldCarver<*>>> = listOf(
        "porosity".location() to RuleCarver(CarverConfiguration.CODEC.codec(), Porosity.VUGS),
    )
}
