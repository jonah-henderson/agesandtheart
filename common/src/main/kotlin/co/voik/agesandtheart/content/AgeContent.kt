package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.FillNotebookFunction
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.PageWordFunction
import co.voik.agesandtheart.age.word.grammar.Said
import co.voik.agesandtheart.age.consequence.WoundBlock
import co.voik.agesandtheart.book.BindLinkingBookFunction
import co.voik.agesandtheart.book.WriteFoundBookFunction
import co.voik.agesandtheart.age.phenomena.SandColumn
import co.voik.agesandtheart.book.BookEntity
import co.voik.agesandtheart.book.LinkTarget
import co.voik.agesandtheart.book.RepatternBookRecipe
import co.voik.agesandtheart.worldgen.fissure.CollapsingFissureBlock
import co.voik.agesandtheart.worldgen.fissure.StarFissureBlock
import co.voik.agesandtheart.worldgen.fissure.StarFissureBlockEntity
import co.voik.agesandtheart.desk.WriterProfession
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
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.MenuType
import net.minecraft.resources.Identifier
import net.minecraft.server.level.TicketType
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.equipment.ArmorType
import net.minecraft.world.item.equipment.Equippable
import net.minecraft.world.item.equipment.EquipmentAssets
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.valueproviders.UniformInt
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DropExperienceBlock
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.carver.CarverConfiguration
import co.voik.agesandtheart.worldgen.feature.Formation
import co.voik.agesandtheart.worldgen.feature.RimeCrystal
import co.voik.agesandtheart.worldgen.feature.SpilledSpring
import co.voik.agesandtheart.worldgen.feature.TemperedGround
import co.voik.agesandtheart.worldgen.feature.VolcanoVents
import net.minecraft.world.level.levelgen.feature.Feature
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

    /**
     * The stock a page or notebook is still to be drawn from — **an unwritten page, not a blank one**.
     *
     * A villager's offer is built once and bought many times, so anything a trade writes into the item is
     * written once: a page whose word was rolled when the offer was made hands out that same word for ever.
     * Carrying the *pool* instead defers the draw to the moment somebody takes the item
     * (`MerchantResultSlotMixin`), so every purchase is a different word.
     *
     * This is the one place §8's rule that a page's word is "rolled when the page is generated, never when
     * it is read" is bent, and it is bent rather than broken: a found page is still rolled where it is
     * found. What is deferred here is the roll for a page that has not been handed to anybody yet.
     */
    val STOCKED_FROM: DataComponentType<Identifier> = DataComponentType.builder<Identifier>()
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

    private val PITCHSTONE_ID: Identifier = "pitchstone".location()
    private val PITCHSTONE_ORE_ID: Identifier = "pitchstone_ore".location()
    private val DEEPSLATE_PITCHSTONE_ORE_ID: Identifier = "deepslate_pitchstone_ore".location()
    private val PITCHSTONE_BLOCK_ID: Identifier = "pitchstone_block".location()

    /**
     * What a dangerous Age yields (design §7.7) — "Deretheni" in the language file, and named here for what
     * it is, like the inks.
     *
     * **A mineral rather than a metal**, which the lore settles and the mechanics follow: the ore drops the
     * material directly and it is crafted into a block, which is quartz's shape rather than iron's. There is
     * no smelting step because there is nothing to smelt out — it is stone all the way down.
     *
     * **Fenced out of the vocabulary by `#agesandtheart:forbidden`**, and that fence is load-bearing rather
     * than tidy: a writer who could ask for an Age full of this would have §8.4's duplication exploit with
     * no danger required, which inverts the whole reward. You cause the conditions; you cannot name the
     * outcome.
     */
    val PITCHSTONE: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, PITCHSTONE_ID)))

    val PITCHSTONE_ORE_BLOCK: Block = DropExperienceBlock(
        UniformInt.of(LEAST_ORE_EXPERIENCE, MOST_ORE_EXPERIENCE),
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, PITCHSTONE_ORE_ID))
            .mapColor(MapColor.STONE)
            .strength(ORE_STRENGTH, ORE_RESISTANCE)
            .sound(SoundType.STONE)
            .requiresCorrectToolForDrops(),
    )

    /** The same deposit found below the stone line, harder for the same reason vanilla's deepslate ores are. */
    val DEEPSLATE_PITCHSTONE_ORE_BLOCK: Block = DropExperienceBlock(
        UniformInt.of(LEAST_ORE_EXPERIENCE, MOST_ORE_EXPERIENCE),
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, DEEPSLATE_PITCHSTONE_ORE_ID))
            .mapColor(MapColor.DEEPSLATE)
            .strength(DEEPSLATE_ORE_STRENGTH, ORE_RESISTANCE)
            .sound(SoundType.DEEPSLATE)
            .requiresCorrectToolForDrops(),
    )

    /** Storage, and the form the crafts will ask for. Light for a stone, which is the whole of what it is. */
    val PITCHSTONE_BLOCK_BLOCK: Block = Block(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, PITCHSTONE_BLOCK_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .strength(ORE_STRENGTH, ORE_RESISTANCE)
            .sound(SoundType.STONE)
            .requiresCorrectToolForDrops(),
    )

    val PITCHSTONE_ORE: Item = BlockItem(
        PITCHSTONE_ORE_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, PITCHSTONE_ORE_ID)).useBlockDescriptionPrefix(),
    )

    val DEEPSLATE_PITCHSTONE_ORE: Item = BlockItem(
        DEEPSLATE_PITCHSTONE_ORE_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, DEEPSLATE_PITCHSTONE_ORE_ID))
            .useBlockDescriptionPrefix(),
    )

    val PITCHSTONE_BLOCK: Item = BlockItem(
        PITCHSTONE_BLOCK_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, PITCHSTONE_BLOCK_ID)).useBlockDescriptionPrefix(),
    )

    /** Quartz's numbers, which is the ore this one is shaped after. */
    private const val ORE_STRENGTH = 3.0f
    private const val DEEPSLATE_ORE_STRENGTH = 4.5f
    private const val ORE_RESISTANCE = 3.0f
    private const val LEAST_ORE_EXPERIENCE = 2
    private const val MOST_ORE_EXPERIENCE = 5

    private val PITCHSTONE_PLATE_ID: Identifier = "pitchstone_plate".location()

    /**
     * Deretheni chipped into overlapping plates at a saw — the form the lore's suit was actually built of,
     * and the only thing the armour is made from.
     *
     * **A stonecutter rather than a furnace**, because it is stone: four plates off one piece, which is the
     * saw's usual generosity and what keeps a suit inside one dangerous Age's yield.
     */
    val PITCHSTONE_PLATE: Item =
        Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, PITCHSTONE_PLATE_ID)))

    private val PITCHSTONE_HELMET_ID: Identifier = "pitchstone_helmet".location()
    private val PITCHSTONE_CHESTPLATE_ID: Identifier = "pitchstone_chestplate".location()
    private val PITCHSTONE_LEGGINGS_ID: Identifier = "pitchstone_leggings".location()
    private val PITCHSTONE_BOOTS_ID: Identifier = "pitchstone_boots".location()

    /** See [ProtectiveSuit] — what a writer wears into an Age written to be survived. */
    val PITCHSTONE_HELMET: Item = suitPiece(PITCHSTONE_HELMET_ID, ArmorType.HELMET)
    val PITCHSTONE_CHESTPLATE: Item = suitPiece(PITCHSTONE_CHESTPLATE_ID, ArmorType.CHESTPLATE)
    val PITCHSTONE_LEGGINGS: Item = suitPiece(PITCHSTONE_LEGGINGS_ID, ArmorType.LEGGINGS)
    val PITCHSTONE_BOOTS: Item = suitPiece(PITCHSTONE_BOOTS_ID, ArmorType.BOOTS)

    /**
     * One piece of the suit.
     *
     * `humanoidArmor` sets the material's own modifiers, and [ProtectiveSuit.attributesFor] replaces them
     * with the same set plus the burning-time modifier — so the attributes are stated once rather than
     * being built here and again there.
     */
    private fun suitPiece(id: Identifier, type: ArmorType): Item = Item(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, id))
            .humanoidArmor(ProtectiveSuit.MATERIAL, type)
            .attributes(ProtectiveSuit.attributesFor(type)),
    )

    private val RIME_CRYSTAL_ID: Identifier = "rime_crystal".location()

    /**
     * The crystal a frozen Age grows on its cliffs — the first of §7.1.2's **character materials**, the
     * class that pays for having written many unlike Ages.
     *
     * **"Rime" is the English word for frost**, which is what makes it usable as an id at all: the Age it
     * also nods to is a coincidence we are allowed to enjoy, where a name that were *only* the Age's would
     * belong in the language file like every other. See CLAUDE.md's convention.
     *
     * **An amethyst cluster in shape and in blockstate**, because that is what it is: a crystal growing out
     * of a face, at any of six orientations, and vanilla's own block already knows how to be one. Its blue
     * is a tint over vanilla's texture rather than a texture of ours — the mod ships no art, and a
     * recoloured copy of Mojang's would be their art in our jar.
     */
    val RIME_CRYSTAL_BLOCK: AmethystClusterBlock = AmethystClusterBlock(
        CRYSTAL_HEIGHT,
        CRYSTAL_WIDTH,
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, RIME_CRYSTAL_ID))
            .mapColor(MapColor.ICE)
            .forceSolidOn()
            .noOcclusion()
            .randomTicks()
            .sound(SoundType.AMETHYST_CLUSTER)
            .strength(CRYSTAL_STRENGTH)
            .lightLevel { CRYSTAL_GLOW },
    )

    val RIME_CRYSTAL: Item = BlockItem(
        RIME_CRYSTAL_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, RIME_CRYSTAL_ID)).useBlockDescriptionPrefix(),
    )

    /** Vanilla's own full cluster, which is the shape this is. */
    private const val CRYSTAL_HEIGHT = 7.0f
    private const val CRYSTAL_WIDTH = 3.0f
    private const val CRYSTAL_STRENGTH = 1.5f

    /** Faint, so a cliff face full of them reads at a distance without lighting the Age. */
    private const val CRYSTAL_GLOW = 4

    /**
     * The blue a rime crystal is rendered in — **a tint over vanilla's amethyst rather than art of ours**.
     *
     * The mod ships no textures, and a recoloured copy of Mojang's would be Mojang's art in our jar. A
     * `tintindex` in the model and this number are the whole of the difference, and both go when the asset
     * pass draws a real one (Phase 9).
     */
    const val RIME_CRYSTAL_TINT = 0x7FC8F0

    private val RIME_SKATES_ID: Identifier = "rime_skates".location()

    /**
     * A rime blade under a leather boot — see [RimeSkates] for what wearing them does.
     *
     * **Equippable rather than armour**, because they defend nothing: what they carry is a movement speed
     * and, through the mixin, the ground's grip on you. Vanilla's leather model dresses them, which
     * references Mojang's art rather than shipping it — the same bargain the rime crystal's tint makes.
     */
    val RIME_SKATES: Item = Item(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, RIME_SKATES_ID))
            .stacksTo(1)
            .durability(SKATE_DURABILITY)
            .component(
                DataComponents.EQUIPPABLE,
                Equippable.builder(EquipmentSlot.FEET)
                    .setAsset(EquipmentAssets.LEATHER)
                    .setEquipSound(SoundEvents.ARMOR_EQUIP_LEATHER)
                    .build(),
            )
            .attributes(
                ItemAttributeModifiers.builder()
                    .add(
                        Attributes.MOVEMENT_SPEED,
                        AttributeModifier(
                            RIME_SKATES_ID,
                            SKATE_HURRY,
                            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                        ),
                        EquipmentSlotGroup.FEET,
                    )
                    .build(),
            ),
    )

    /**
     * How much faster a stride is on skates.
     *
     * **Read against what the slickness then does with it.** Friction near one holds nearly all of your
     * speed from tick to tick, so a third more push comes out at something over twice a walk once you are
     * running — about a horse on the flat, and a good deal worse than a horse anywhere with corners.
     */
    private const val SKATE_HURRY = 0.35

    /** A boot's, and they take the wear of one. */
    private const val SKATE_DURABILITY = 195

    private val TEMPERSTONE_ID: Identifier = "temperstone".location()
    private val SCORCHED_TEMPERSTONE_ID: Identifier = "scorched_temperstone".location()
    private val RAW_TEMPERSTONE_ID: Identifier = "raw_temperstone".location()
    private val TEMPERSTONE_CLIMBERS_ID: Identifier = "temperstone_climbers".location()
    private val LAVA_TUBE_ID: Identifier = "lava_tube".location()

    /**
     * The vent in a volcano's caldera — see [co.voik.agesandtheart.content.LavaTubes] for what a mass does.
     *
     * Obsidian's blast resistance, so a volcano cannot destroy its own vents and switch itself off.
     */
    val LAVA_TUBE_BLOCK: Block = LavaTubeBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, LAVA_TUBE_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .strength(TEMPERSTONE_STRENGTH, BLAST_PROOF)
            .sound(SoundType.BASALT)
            .randomTicks()
            .lightLevel { TUBE_GLOW }
            .requiresCorrectToolForDrops(),
    )

    val LAVA_TUBE: Item = BlockItem(
        LAVA_TUBE_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, LAVA_TUBE_ID)).useBlockDescriptionPrefix(),
    )

    /** Enough to find a vent by in a dark caldera, not enough to light the crater. */
    private const val TUBE_GLOW = 6

    /**
     * Stone baked at the right distance from lava (design §7.1.2) — the second early material.
     *
     * A stone's mining time with obsidian's blast resistance: the two numbers answer different questions,
     * and obsidian's strength would make the stuff a chore to gather. Climbable while
     * [TEMPERSTONE_CLIMBERS] are worn — see [co.voik.agesandtheart.content.Temperstone].
     */
    val TEMPERSTONE_BLOCK: Block = TemperstoneBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, TEMPERSTONE_ID))
            .mapColor(MapColor.DEEPSLATE)
            .strength(TEMPERSTONE_STRENGTH, BLAST_PROOF)
            .sound(SoundType.BASALT)
            .requiresCorrectToolForDrops(),
    )

    /**
     * The untempered form: the outermost band of a formation, and what turns up in blobs elsewhere.
     *
     * Random-ticks so that a block left near lava bakes where it lies, which is what lets a player work the
     * rule the ground taught them — see [co.voik.agesandtheart.content.RawTemperstoneBlock].
     */
    val RAW_TEMPERSTONE_BLOCK: Block = RawTemperstoneBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, RAW_TEMPERSTONE_ID))
            .mapColor(MapColor.STONE)
            .strength(TEMPERSTONE_STRENGTH)
            .sound(SoundType.STONE)
            .randomTicks()
            .requiresCorrectToolForDrops(),
    )

    /**
     * The band that sat too close to the heat: cooked past use as a stone, and ground down for gunpowder.
     */
    val SCORCHED_TEMPERSTONE_BLOCK: Block = Block(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, SCORCHED_TEMPERSTONE_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .strength(SCORCHED_STRENGTH)
            .sound(SoundType.NETHERRACK),
    )

    val TEMPERSTONE: Item = BlockItem(
        TEMPERSTONE_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, TEMPERSTONE_ID)).useBlockDescriptionPrefix(),
    )

    val SCORCHED_TEMPERSTONE: Item = BlockItem(
        SCORCHED_TEMPERSTONE_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, SCORCHED_TEMPERSTONE_ID))
            .useBlockDescriptionPrefix(),
    )

    val RAW_TEMPERSTONE: Item = BlockItem(
        RAW_TEMPERSTONE_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, RAW_TEMPERSTONE_ID)).useBlockDescriptionPrefix(),
    )

    /**
     * Temperstone underfoot — see [co.voik.agesandtheart.content.Temperstone] for what wearing them does.
     *
     * Equippable rather than armour, for the reason the skates are: they defend nothing.
     */
    val TEMPERSTONE_CLIMBERS: Item = Item(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, TEMPERSTONE_CLIMBERS_ID))
            .stacksTo(1)
            .durability(CLIMBER_DURABILITY)
            .component(
                DataComponents.EQUIPPABLE,
                Equippable.builder(EquipmentSlot.FEET)
                    .setAsset(EquipmentAssets.LEATHER)
                    .setEquipSound(SoundEvents.ARMOR_EQUIP_GENERIC)
                    .build(),
            ),
    )

    /** A stone's mining time, so gathering it is not a chore. */
    private const val TEMPERSTONE_STRENGTH = 1.5f

    /** Obsidian's, which is the point of the material. */
    private const val BLAST_PROOF = 1200.0f

    /** Netherrack's: it crumbles, which is what being cooked too hard means. */
    private const val SCORCHED_STRENGTH = 0.4f

    /** A boot's, like the skates. */
    private const val CLIMBER_DURABILITY = 195

    private val TOOLBOX_ID: Identifier = "toolbox".location()
    private val GEOLOGISTS_TOOLS_ID: Identifier = "geologists_tools".location()

    /**
     * A case of shelves holding spares — see [Toolbox] for the thing it is actually for.
     *
     * **Useful before it is an ingredient**, which is the whole reason it exists as its own object: carry
     * one and a tool that breaks in your hands is replaced from it. Being furniture the desk counts, and
     * being what a geologist's kit is built in, are both things it does *afterwards*.
     */
    val TOOLBOX_BLOCK: ToolboxBlock = ToolboxBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, TOOLBOX_ID))
            .mapColor(MapColor.WOOD)
            .strength(WOODEN_STRENGTH)
            .sound(SoundType.WOOD),
    )

    /** The nine compartments, which travel with the block — see [ToolboxBlockEntity]. */
    val TOOLBOX_ENTITY: BlockEntityType<ToolboxBlockEntity> =
        BlockEntityType({ pos, state -> ToolboxBlockEntity(pos, state) }, setOf(TOOLBOX_BLOCK))

    /**
     * The same case with the instruments in it — §7.7's geologist's tools, which say what an Age will hold
     * before it exists.
     *
     * **Filled by a shapeless recipe on purpose** (Jonah, 2026-09-05): how you arrange tools in a box is
     * not a thing the world should have an opinion about. What is shaped is building the box.
     */
    val GEOLOGISTS_TOOLS_BLOCK: Block = Block(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, GEOLOGISTS_TOOLS_ID))
            .mapColor(MapColor.WOOD)
            .strength(WOODEN_STRENGTH)
            .sound(SoundType.WOOD),
    )

    val TOOLBOX: Item = BlockItem(
        TOOLBOX_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, TOOLBOX_ID)).useBlockDescriptionPrefix(),
    )

    val GEOLOGISTS_TOOLS: Item = BlockItem(
        GEOLOGISTS_TOOLS_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, GEOLOGISTS_TOOLS_ID))
            .useBlockDescriptionPrefix(),
    )

    /** A cabinet's, which is what both of these are. */
    private const val WOODEN_STRENGTH = 2.5f

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

    /** What the machine is chewing on, which outlives no unload — see [AnalysisMachineBlockEntity]. */
    val ANALYSIS_MACHINE_ENTITY: BlockEntityType<AnalysisMachineBlockEntity> =
        BlockEntityType({ pos, state -> AnalysisMachineBlockEntity(pos, state) }, setOf(ANALYSIS_MACHINE_BLOCK))

    private const val ANALYSIS_MACHINE_STRENGTH = 3.5f

    private val SURVEYING_DEVICE_ID: Identifier = "surveying_device".location()

    /** See [SurveyingDeviceBlock] — the half you carry to the place and set down. */
    val SURVEYING_DEVICE_BLOCK: SurveyingDeviceBlock = SurveyingDeviceBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, SURVEYING_DEVICE_ID))
            .mapColor(MapColor.COLOR_PURPLE)
            .strength(SURVEYING_DEVICE_STRENGTH)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()
            // An instrument reading *this* place should not be shovable to another one — and a piston does
            // not carry a block's scheduled tick with it, so a pushed survey would run forever.
            .pushReaction(PushReaction.BLOCK),
    )

    val SURVEYING_DEVICE: Item = BlockItem(
        SURVEYING_DEVICE_BLOCK,
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, SURVEYING_DEVICE_ID))
            .useBlockDescriptionPrefix(),
    )

    /** Lighter than the machine: an instrument you expect to pick up and carry on is worth less digging. */
    private const val SURVEYING_DEVICE_STRENGTH = 2.5f

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

    /** The desk as a job site — see [WriterProfession] for why only its centre counts. */
    val WRITERS_DESK_POI: PoiType = WriterProfession.poiType(WRITERS_DESK_BLOCK)

    /** The villager who works one. */
    val WRITER_PROFESSION: VillagerProfession = WriterProfession.profession()

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

    private val COLLAPSING_FISSURE_ID: Identifier = "collapsing_fissure".location()

    /**
     * A star fissure that is still opening — see [co.voik.agesandtheart.worldgen.fissure.CollapsingFissureBlock].
     *
     * **`randomTicks()` is the whole of what makes it spread**, handing the scheduling to vanilla exactly as
     * a grass block does. Everything else it is, it is by being a star fissure.
     */
    val COLLAPSING_FISSURE_BLOCK: CollapsingFissureBlock = CollapsingFissureBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, COLLAPSING_FISSURE_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .noCollision()
            .lightLevel { FISSURE_GLOW }
            .strength(-1.0f, Float.MAX_VALUE)
            .noLootTable()
            .randomTicks()
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
        BlockEntityType(
            { pos, state -> StarFissureBlockEntity(pos, state) },
            // Both blocks, so one renderer draws the starfield for the finished tear and the opening one.
            setOf(STAR_FISSURE_BLOCK, COLLAPSING_FISSURE_BLOCK),
        )

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

    /**
     * A column of sand walking an Age. See [co.voik.agesandtheart.age.phenomena.SandColumn].
     *
     * **The box is the column's footprint, not the column.** What is drawn stands from the ground to the
     * build limit, and a bounding box that tall would be a collision volume and a chunk-tracking cost for
     * something nothing may collide with. The renderer answers the culling instead, by declining it.
     *
     * Tracked from far off, because being seen coming *is* the phenomenon — this is the granularity its
     * counterplay needs, and a column that popped into view would be a different, worse hazard.
     */
    val SAND_COLUMN: EntityType<SandColumn> = EntityType.Builder
        .of({ type, level -> SandColumn(type, level) }, MobCategory.MISC)
        .sized(SAND_COLUMN_WIDTH, SAND_COLUMN_HEIGHT)
        .clientTrackingRange(SAND_COLUMN_TRACKING_CHUNKS)
        .updateInterval(SAND_COLUMN_UPDATE_TICKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "sand_column".location()))

    /** The widest a column's footprint gets, so the box at least contains what the column is doing. */
    private const val SAND_COLUMN_WIDTH = 12.0f
    private const val SAND_COLUMN_HEIGHT = 12.0f

    /** As far as vanilla tracks anything — the ender dragon's own range. */
    private const val SAND_COLUMN_TRACKING_CHUNKS = 32

    /** It moves slowly and in a straight line, so its position is worth sending rarely. */
    private const val SAND_COLUMN_UPDATE_TICKS = 10

    /**
     * The ticket that holds a linking panel's chunks (§7.8.1).
     *
     * **Here rather than beside the code that uses it, because registries freeze.** `PanelViews` is an
     * `object`, so anything registered in its initialiser runs the first time a book is opened — long after
     * `BuiltInRegistries` is shut — and throws `Registry is already frozen`, taking the server thread with
     * it. Everything the mod registers is declared here and written into the registries by each loader's
     * entrypoint at init, which is the only time that is allowed.
     *
     * `FLAG_LOADING` without `FLAG_SIMULATION`: a panel wants the terrain drawn and emphatically does not
     * want an Age *ticking* for somebody glancing at a book — no mobs, no growth, no phenomena running for
     * a viewer who is not there. `FLAG_KEEP_DIMENSION_ACTIVE` so the level is not unloaded under the ring.
     * No timeout, because closing the book is what ends a view and a ticket that expired on its own would
     * blank a panel somebody was still looking at.
     */
    val PANEL_TICKET: TicketType =
        TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING or TicketType.FLAG_KEEP_DIMENSION_ACTIVE)

    val tickets: List<Pair<Identifier, TicketType>> = listOf("panel".location() to PANEL_TICKET)

    val entities: List<Pair<Identifier, EntityType<*>>> = listOf(
        "descriptive_book".location() to BOOK_ENTITY,
        "sand_column".location() to SAND_COLUMN,
    )

    val WOUND_ID: Identifier = "wound".location()

    /**
     * A tear in spacetime (design §5.1). Unbreakable and lootless — the only thing to be done with one is
     * to box it in, and a wound that could be mined would be a wound that could be *tidied*.
     *
     * `noOcclusion` and an invisible render shape because what is drawn is not a cube: `WoundField` gives
     * it the flicker, and a solid model would light the room it is in.
     *
     * **No block entity, deliberately** — see `WoundField`. One would be an object in memory and a record
     * in chunk NBT per wound, which is a ceiling on a register whose whole point is that the count climbs
     * (§5.2.1). `Wounds` indexes them off the chunk's palette instead.
     */
    val WOUND_BLOCK: WoundBlock = WoundBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, WOUND_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .strength(-1.0f, Float.MAX_VALUE)
            .noLootTable()
            .noOcclusion()
            // **No collision, and that is what makes it a hole.** A solid tear would be a black cube you
            // bump into and items would come to rest on top of it; an open one is walked into and dropped
            // through, which is the whole of how a player meets one.
            .noCollision()
            .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK),
    )

    val blocks: List<Pair<Identifier, Block>> = listOf(
        WOUND_ID to WOUND_BLOCK,
        WRITERS_DESK_ID to WRITERS_DESK_BLOCK,
        STAR_FISSURE_ID to STAR_FISSURE_BLOCK,
        COLLAPSING_FISSURE_ID to COLLAPSING_FISSURE_BLOCK,
        ANALYSIS_MACHINE_ID to ANALYSIS_MACHINE_BLOCK,
        SURVEYING_DEVICE_ID to SURVEYING_DEVICE_BLOCK,
        PITCHSTONE_ORE_ID to PITCHSTONE_ORE_BLOCK,
        DEEPSLATE_PITCHSTONE_ORE_ID to DEEPSLATE_PITCHSTONE_ORE_BLOCK,
        PITCHSTONE_BLOCK_ID to PITCHSTONE_BLOCK_BLOCK,
        RIME_CRYSTAL_ID to RIME_CRYSTAL_BLOCK,
        TEMPERSTONE_ID to TEMPERSTONE_BLOCK,
        SCORCHED_TEMPERSTONE_ID to SCORCHED_TEMPERSTONE_BLOCK,
        RAW_TEMPERSTONE_ID to RAW_TEMPERSTONE_BLOCK,
        LAVA_TUBE_ID to LAVA_TUBE_BLOCK,
        TOOLBOX_ID to TOOLBOX_BLOCK,
        GEOLOGISTS_TOOLS_ID to GEOLOGISTS_TOOLS_BLOCK,
    )

    /**
     * **Each loader registers these its own way**, and that is the whole platform cost of the profession.
     * NeoForge maps a point of interest's block states off the registry itself; Fabric's `PoiTypes` keeps
     * that map private, so its API rebuilds the type from these three values.
     */
    val poiTypes: List<Pair<Identifier, PoiType>> = listOf(
        WriterProfession.ID to WRITERS_DESK_POI,
    )

    val villagerProfessions: List<Pair<Identifier, VillagerProfession>> = listOf(
        WriterProfession.ID to WRITER_PROFESSION,
    )

    val blockEntities: List<Pair<Identifier, BlockEntityType<*>>> = listOf(
        TOOLBOX_ID to TOOLBOX_ENTITY,
        WRITERS_DESK_ID to WRITERS_DESK_ENTITY,
        STAR_FISSURE_ID to STAR_FISSURE_ENTITY,
        ANALYSIS_MACHINE_ID to ANALYSIS_MACHINE_ENTITY,
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

    /**
     * That this book was bound at a desk by a player, rather than found already written (design §7.7) —
     * what [co.voik.agesandtheart.age.AgeRecipe.authored] is set from when the Age is first made.
     *
     * **Stated rather than inferred, though it could be inferred today.** A found book happens to carry no
     * [BOOK_SEED] and a bound one always does, so the two are already distinguishable — but that is a
     * coincidence of two write paths rather than a claim either of them makes, and the moment a found book
     * gains a seed the reward economy would quietly open to the loot table. The fact worth recording is
     * *who wrote this*, so it is recorded.
     *
     * Absent on a found book, on a book bound before this existed, and on any hand-built stack — all of
     * which read as not authored, which is the safe way round.
     */
    val BOOK_AUTHORED: DataComponentType<Boolean> = DataComponentType.builder<Boolean>()
        .persistent(Codec.BOOL)
        .networkSynchronized(ByteBufCodecs.BOOL)
        .build()

    val WRITERS_DESK_MENU: MenuType<WritersDeskMenu> = MenuType(
        { containerId, inventory -> WritersDeskMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    val INK_CASE_MENU: MenuType<InkCaseMenu> = MenuType(
        { containerId, inventory -> InkCaseMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    /**
     * The toolbox's own type rather than `GENERIC_9x3`, because the client builds a menu from its type —
     * borrowing vanilla's would give the client unrestricted slots and the server restricted ones.
     */
    val TOOLBOX_MENU: MenuType<ToolboxMenu> = MenuType(
        { containerId, inventory ->
            ToolboxMenu(containerId, inventory, ToolboxMenu.emptyContents())
        },
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
        TOOLBOX_ID to TOOLBOX_MENU,
    )

    val recipeSerializers: List<Pair<Identifier, RecipeSerializer<*>>> =
        listOf("repattern_descriptive_book".location() to RepatternBookRecipe.SERIALIZER)

    val components: List<Pair<Identifier, DataComponentType<*>>> = listOf(
        "stocked_from".location() to STOCKED_FROM,
        "age_id".location() to AGE_ID,
        "page_word".location() to PAGE_WORD,
        "book_words".location() to BOOK_WORDS,
        "book_title".location() to BOOK_TITLE,
        "book_seed".location() to BOOK_SEED,
        "book_reading".location() to BOOK_READING,
        "book_authored".location() to BOOK_AUTHORED,
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
        PITCHSTONE_ID to PITCHSTONE,
        PITCHSTONE_ORE_ID to PITCHSTONE_ORE,
        DEEPSLATE_PITCHSTONE_ORE_ID to DEEPSLATE_PITCHSTONE_ORE,
        PITCHSTONE_BLOCK_ID to PITCHSTONE_BLOCK,
        PITCHSTONE_PLATE_ID to PITCHSTONE_PLATE,
        PITCHSTONE_HELMET_ID to PITCHSTONE_HELMET,
        PITCHSTONE_CHESTPLATE_ID to PITCHSTONE_CHESTPLATE,
        PITCHSTONE_LEGGINGS_ID to PITCHSTONE_LEGGINGS,
        PITCHSTONE_BOOTS_ID to PITCHSTONE_BOOTS,
        RIME_CRYSTAL_ID to RIME_CRYSTAL,
        RIME_SKATES_ID to RIME_SKATES,
        TEMPERSTONE_ID to TEMPERSTONE,
        SCORCHED_TEMPERSTONE_ID to SCORCHED_TEMPERSTONE,
        RAW_TEMPERSTONE_ID to RAW_TEMPERSTONE,
        LAVA_TUBE_ID to LAVA_TUBE,
        TEMPERSTONE_CLIMBERS_ID to TEMPERSTONE_CLIMBERS,
        TOOLBOX_ID to TOOLBOX,
        GEOLOGISTS_TOOLS_ID to GEOLOGISTS_TOOLS,
    )

    /** Chunk-generator codecs — a level's generator is serialised when it is saved, so it needs one. */
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
        "write_found_book".location() to WriteFoundBookFunction.MAP_CODEC,
    )

    /**
     * Our own carvers. The configured instances that use them are datapack JSON under
     * `data/agesandtheart/worldgen/configured_carver/`; this registers the carver *kinds* those refer to.
     */
    val carvers: List<Pair<Identifier, WorldCarver<*>>> = listOf(
        "porosity".location() to RuleCarver(CarverConfiguration.CODEC.codec(), Porosity.VUGS),
    )

    /**
     * Our own features. As with [carvers], this registers the *kind*; what is made of it is built in code
     * rather than authored, there being one caller and no reason for a pack to name it.
     */
    private val BLIZZARD_SHELTERED_ID: Identifier = "blizzard_sheltered".location()
    private val BLIZZARD_EXPOSED_ID: Identifier = "blizzard_exposed".location()

    /**
     * The two faces of a blizzard, and which one you hear is the whole of what they are for.
     *
     * **Sheltered is the storm going on without you** — what a roof or a hillside between you and it sounds
     * like, and what makes a dugout feel like one. **Exposed is standing in it**, and it plays while the
     * cold is actually on you, so the sound and the harm arrive together and a player learns one from the
     * other rather than from a tooltip.
     */
    val BLIZZARD_SHELTERED: SoundEvent = SoundEvent.createVariableRangeEvent(BLIZZARD_SHELTERED_ID)
    val BLIZZARD_EXPOSED: SoundEvent = SoundEvent.createVariableRangeEvent(BLIZZARD_EXPOSED_ID)

    val soundEvents: List<Pair<Identifier, SoundEvent>> = listOf(
        BLIZZARD_SHELTERED_ID to BLIZZARD_SHELTERED,
        BLIZZARD_EXPOSED_ID to BLIZZARD_EXPOSED,
    )

    val features: List<Pair<Identifier, Feature<*>>> = listOf(
        "spilled_spring".location() to SpilledSpring,
        "formation".location() to Formation,
        "rime_crystal".location() to RimeCrystal,
        "tempered_ground".location() to TemperedGround,
        "volcano_vents".location() to VolcanoVents,
    )
}
