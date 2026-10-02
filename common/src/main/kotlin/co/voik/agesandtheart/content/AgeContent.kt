package co.voik.agesandtheart.content

import co.voik.agesandtheart.page.FillNotebookFunction
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.reward.ScarabHabitat
import co.voik.agesandtheart.page.PageWordFunction
import co.voik.agesandtheart.age.consequence.WoundBlock
import co.voik.agesandtheart.book.BindLinkingBookFunction
import co.voik.agesandtheart.book.WriteFoundBookFunction
import co.voik.agesandtheart.book.WriteSurveyedBookFunction
import co.voik.agesandtheart.portal.LinkingBookReceptacleBlock
import co.voik.agesandtheart.portal.LinkingBookReceptacleBlockEntity
import co.voik.agesandtheart.portal.LinkingPortalBlock
import co.voik.agesandtheart.portal.LinkingPortalShape
import co.voik.agesandtheart.age.phenomena.SandColumn
import co.voik.agesandtheart.age.phenomena.CaveIn
import co.voik.agesandtheart.age.phenomena.Meteor
import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.agesandtheart.book.BookEntity
import co.voik.agesandtheart.book.DescriptiveBookItem
import co.voik.agesandtheart.book.LinkingBookItem
import co.voik.agesandtheart.book.RepatternBookRecipe
import co.voik.agesandtheart.age.consequence.CollapsingFissureBlock
import co.voik.agesandtheart.age.consequence.CrumblingColumn
import co.voik.agesandtheart.worldgen.fissure.StarFissureBlock
import co.voik.agesandtheart.worldgen.fissure.StarFissureBlockEntity
import co.voik.agesandtheart.desk.ArchiveBlock
import co.voik.agesandtheart.desk.ArchiveBlockEntity
import co.voik.agesandtheart.desk.ArchiveItem
import co.voik.agesandtheart.desk.ArchiveMenu
import co.voik.agesandtheart.desk.GeologistsToolsBlock
import co.voik.agesandtheart.desk.SeismographBlock
import co.voik.agesandtheart.desk.CrystalViewerBlock
import co.voik.agesandtheart.desk.CrystalViewerMenu
import co.voik.agesandtheart.desk.SeismographItem
import co.voik.agesandtheart.desk.WriterProfession
import co.voik.agesandtheart.desk.WritersDeskBlock
import co.voik.agesandtheart.desk.WritersDeskBlockEntity
import co.voik.agesandtheart.desk.InkCaseMenu
import co.voik.agesandtheart.desk.SupplyBinMenu
import co.voik.agesandtheart.desk.GeologistsToolsMenu
import co.voik.agesandtheart.desk.SeismographMenu
import co.voik.agesandtheart.desk.WritersDeskMenu
import co.voik.agesandtheart.location
import co.voik.agesandtheart.station.Station
import co.voik.agesandtheart.station.StationBlock
import co.voik.agesandtheart.station.Compounder
import co.voik.agesandtheart.station.Compounding
import co.voik.agesandtheart.station.CompoundingRecipeDisplay
import co.voik.agesandtheart.station.Drying
import co.voik.agesandtheart.station.DryingRack
import co.voik.agesandtheart.station.StationBlockEntity
import co.voik.agesandtheart.station.StationMenu
import co.voik.agesandtheart.station.StationRecipes
import co.voik.agesandtheart.worldgen.carver.Porosity
import com.mojang.serialization.MapCodec
import co.voik.agesandtheart.worldgen.fissure.StarFissurePiece
import co.voik.agesandtheart.worldgen.fissure.StarFissureStructure
import co.voik.agesandtheart.worldgen.dni.DniCityStructure
import co.voik.agesandtheart.worldgen.dni.DniDevicePiece
import co.voik.agesandtheart.worldgen.structure.LootTableSwap
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.MenuType
import net.minecraft.resources.Identifier
import net.minecraft.server.level.TicketType
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.SpawnPlacementType
import net.minecraft.world.entity.SpawnPlacementTypes
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeBookCategory
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
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
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import co.voik.agesandtheart.worldgen.feature.Formation
import co.voik.agesandtheart.worldgen.feature.Heap
import co.voik.agesandtheart.worldgen.feature.OreVein
import co.voik.agesandtheart.worldgen.feature.Algae
import co.voik.agesandtheart.worldgen.feature.RimeCrystal
import co.voik.agesandtheart.worldgen.feature.SpilledSpring
import co.voik.agesandtheart.worldgen.feature.TemperedGround
import co.voik.agesandtheart.worldgen.feature.ImpactCrater
import co.voik.agesandtheart.worldgen.feature.DeepSeaVent
import co.voik.agesandtheart.worldgen.feature.PaperTree
import co.voik.agesandtheart.worldgen.feature.PaperTreeGrove
import co.voik.agesandtheart.worldgen.feature.PitClearing
import co.voik.agesandtheart.worldgen.feature.ScarabColony
import co.voik.agesandtheart.worldgen.feature.PalmTree
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import co.voik.agesandtheart.worldgen.feature.LavaPuddles
import co.voik.agesandtheart.worldgen.feature.VolcanoVents
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.carver.WorldCarver
import net.minecraft.world.level.storage.loot.functions.LootItemFunction

/**
 * The mod's registered content, defined loader-agnostically.
 *
 * Instances are built eagerly here (constructing them needs no registry); the *registration*
 * is driven per loader — Fabric registers directly during init, NeoForge via `RegisterEvent` —
 * with each loader iterating [blocks], [items] and the rest. See each loader's entrypoint.
 *
 * **The data components are [AgeComponents] and the generation kinds
 * [co.voik.agesandtheart.generation.WorldgenCodecs]**, because neither holds an `Item` or a `Block`. This
 * object builds every one of those eagerly and so cannot be loaded once the registries have frozen, which
 * is why anything wanting only a component type or a codec should not have to reach through it.
 */
object AgeContent {
    /**
     * **An item must know its own id before it is constructed.** `Item.Properties.setId` is not optional:
     * the constructor derives the description id and the component initialisers from it and throws
     * "Item id not set" without one. So the id is named here and the registration below reuses it, rather
     * than being spelled twice.
     */
    private val DESCRIPTIVE_BOOK_ID: Identifier = "descriptive_book".location()

    /** Unstackable so each book keeps its own [AgeComponents.AGE_ID] identity. */
    val DESCRIPTIVE_BOOK: Item = DescriptiveBookItem(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, DESCRIPTIVE_BOOK_ID))
            .stacksTo(1),
    )

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
     * **Writable, but only in masterwork ink** (`#agesandtheart:requires_masterwork_ink`): an Age full of
     * this is priced like a sea of diamonds rather than refused.
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

    /**
     * The crystal a frozen Age grows on its cliffs — the first of §7.1.2's **character materials**, the
     * class that pays for having written many unlike Ages.
     *
     * **"Rime" is the English word for frost**, which is what makes it usable as an id at all: the Age it
     * also nods to is a coincidence we are allowed to enjoy, where a name that were *only* the Age's would
     * belong in the language file like every other. See CLAUDE.md's convention.
     *
     * **An amethyst cluster in shape and in blockstate**, because that is what it is: a crystal growing out
     * of a face, at any of six orientations, and vanilla's own block already knows how to be one. Its
     * colour is a tint over vanilla's texture rather than a texture of ours — the mod ships no art, and a
     * recoloured copy of Mojang's would be their art in our jar.
     *
     * **Eight of them, one per [RimeColour]** (Jonah, 2026-09-07). Separate blocks rather than one block
     * carrying a colour, for the reason [RimeCrystalBlock] gives: a comparator reads the state, so a colour
     * in a component would cost a block entity apiece across thousands of them. What they share is the
     * `#agesandtheart:rime_crystals` tag, which is what every recipe actually asks for.
     */
    val RIME_CRYSTAL_BLOCKS: Map<RimeColour, RimeCrystalBlock> = RimeColour.entries.associateWith { colour ->
        RimeCrystalBlock(
            colour,
            BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, colour.id.location()))
                .mapColor(MapColor.ICE)
                .forceSolidOn()
                .noOcclusion()
                .randomTicks()
                .sound(SoundType.AMETHYST_CLUSTER)
                .strength(CRYSTAL_STRENGTH)
                // Faint standing on a cliff so a face of them reads at distance without lighting the Age,
                // and properly lit with a signal on it. **The light is not coloured and cannot be**: block
                // light is one channel with no hue, so what is tinted is the crystal, not what it shines on.
                .lightLevel { state ->
                    if (state.getValue(BlockStateProperties.POWERED)) {
                        RimeCrystalBlock.POWERED_GLOW
                    } else {
                        RimeCrystalBlock.RESTING_GLOW
                    }
                },
        )
    }

    val RIME_CRYSTALS: Map<RimeColour, Item> = RIME_CRYSTAL_BLOCKS.mapValues { (colour, block) ->
        BlockItem(
            block,
            Item.Properties().setId(ResourceKey.create(Registries.ITEM, colour.id.location()))
                .useBlockDescriptionPrefix(),
        )
    }

    private const val CRYSTAL_STRENGTH = 1.5f

    private const val GLOOMGRIT_HEIGHT = 4.0f
    private const val GLOOMGRIT_WIDTH = 3.0f
    private const val VENT_LINING_STRENGTH = 2.0f
    private const val VENT_LINING_RESISTANCE = 6.0f

    private val VENT_LINING_ID: Identifier = "vent_lining".location()
    private val DEEP_BUBBLE_COLUMN_ID: Identifier = "deep_bubble_column".location()
    private val GLOOMGRIT_ID: Identifier = "gloomgrit".location()
    private val PHASMIUM_GRAINS_ID: Identifier = "phasmium_grains".location()
    private val PHASMIUM_ID: Identifier = "phasmium".location()
    private val PHASMIUM_BLOCK_ID: Identifier = "phasmium_block".location()

    /**
     * The hot skin inside a deep-sea vent — see [VentLiningBlock] for the one rule that makes it a design.
     *
     * **Nothing drops it**, so its loot table is empty rather than absent: budding amethyst's arrangement,
     * and for budding amethyst's reason. It keeps its block item so the feature can be tested and built
     * against in creative, which is also what vanilla does with the block it is modelled on.
     */
    val VENT_LINING: VentLiningBlock = VentLiningBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, VENT_LINING_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .requiresCorrectToolForDrops()
            .randomTicks()
            .sound(SoundType.BASALT)
            .strength(VENT_LINING_STRENGTH, VENT_LINING_RESISTANCE),
    )

    /**
     * The whirlpool a vent's magma raises through the abyss — see [DeepBubbleColumnBlock] for why vanilla's
     * own could not be used.
     *
     * Vanilla's `bubble_column` properties exactly, which is the point: nothing about this differs except
     * the fluid it reports and that it presses. **No block item and no loot table**, like the block it
     * copies — a column is something a liquid raises, never something a player holds.
     */
    val DEEP_BUBBLE_COLUMN: DeepBubbleColumnBlock = DeepBubbleColumnBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, DEEP_BUBBLE_COLUMN_ID))
            .mapColor(MapColor.WATER)
            .replaceable()
            .noCollision()
            .noLootTable()
            .pushReaction(PushReaction.POPPED)
            .liquid()
            .sound(SoundType.EMPTY),
    )

    val VENT_LINING_ITEM: Item = BlockItem(
        VENT_LINING,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, VENT_LINING_ID)).useBlockDescriptionPrefix(),
    )

    /**
     * Gloomgrit, growing out of a vent's lining — the deep-ocean **character material** of §7.1.2, and the
     * first half of phasmium.
     *
     * **An amethyst cluster in shape and blockstate**, the bargain the rime crystals already struck: the
     * mod ships no art, so this is Mojang's cluster under a tint of ours until the asset pass. It is
     * placeable as well as pickable for the same reason a crystal is — an item that is also a little mound
     * reads as something you chipped off a wall rather than as an ore drop.
     *
     * **It does not shine, and it is meant to look like nothing** (Jonah, 2026-09-12). A light would be
     * useless anyway inside the close confines of a chamber you are already standing in — what a diver
     * navigates by is the vent's own lanterns, and those are what carry `shines_through_the_deep`. More
     * than that, the *refining* is where this material's interest lives: gloomgrit mixed with glowstone
     * dust and smelted is what becomes phasmium, and a drab grit that reads as worthless until it is
     * worked says that better than a pretty crystal ever would.
     *
     * Its second use is fine ink, where it is the pigment — §7.1.2's two-uses rule, and the reason the ink
     * takes the grit rather than the grains, which are meant to have only the one.
     */
    val GLOOMGRIT_CLUSTER: AmethystClusterBlock = AmethystClusterBlock(
        GLOOMGRIT_HEIGHT,
        GLOOMGRIT_WIDTH,
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, GLOOMGRIT_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .forceSolidOn()
            .noOcclusion()
            .sound(SoundType.AMETHYST_CLUSTER)
            .strength(CRYSTAL_STRENGTH),
    )

    val GLOOMGRIT: Item = BlockItem(
        GLOOMGRIT_CLUSTER,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, GLOOMGRIT_ID)).useBlockDescriptionPrefix(),
    )

    /**
     * Gloomgrit and glowstone dust, fused but not yet worked — the one thing in the pack whose only purpose
     * is to become something else.
     *
     * **The deliberate exception to §7.1.2's two-uses rule** (Jonah, 2026-09-12). Everything else gets a
     * second use that is fun on its own, rime's skates setting the bar; this gets none, and that is what
     * keeps the *refining* the interesting part of this material rather than the finding. A second use
     * here would be asking for the chain to be shorter than it is meant to be.
     *
     * **Four gloomgrit to one glowstone dust, and the ratio lives in the crafting step rather than in the
     * furnace.** `AbstractCookingRecipe extends SingleItemRecipe` — one ingredient, one item consumed per
     * operation — so no furnace recipe can ever eat four of anything. Crafting can eat any number, which
     * makes the mixing the right place for the arithmetic and leaves the furnace to be the slow gate it is
     * good at being.
     */
    val PHASMIUM_GRAINS: Item = Item(
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, PHASMIUM_GRAINS_ID)),
    )

    /**
     * Phasmium — what the whole descent was for (design §7.1.2).
     *
     * The name is the ghost three times over: the lights on a vent, the hadal zone that is named for Hades,
     * and the Nether its glowstone came out of. What it builds is a **linking portal** — a frame in the
     * manner of a nether portal with a linking book set into it, which is §7.1.2's settled answer to
     * entities crossing between Ages and which has been waiting for a material since 2026-09-07.
     */
    val PHASMIUM: Item = Item(
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, PHASMIUM_ID)),
    )

    /**
     * Nine phasmium worked together — the block a linking portal's frame is built out of, by the nether
     * portal's rules (see [LinkingPortalShape]).
     *
     * **Iron's numbers, as astrite has**: this is a worked metal, and a frame is something you assemble
     * rather than something you pile up.
     */
    val PHASMIUM_BLOCK_BLOCK: Block = Block(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, PHASMIUM_BLOCK_ID))
            .mapColor(MapColor.TERRACOTTA_WHITE)
            .strength(PHASMIUM_STRENGTH, PHASMIUM_RESISTANCE)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops(),
    )

    val PHASMIUM_BLOCK: Item = BlockItem(
        PHASMIUM_BLOCK_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, PHASMIUM_BLOCK_ID)).useBlockDescriptionPrefix(),
    )

    /** Iron's, which is what a block of worked metal should cost to get through. */
    private const val PHASMIUM_STRENGTH = 5.0f
    private const val PHASMIUM_RESISTANCE = 6.0f

    private val LINKING_PORTAL_ID: Identifier = "linking_portal".location()
    private val LINKING_BOOK_RECEPTACLE_ID: Identifier = "linking_book_receptacle".location()

    /** A lit linking portal's opening, with a nether portal's properties: no collision, no breaking, no drop. */
    val LINKING_PORTAL: LinkingPortalBlock = LinkingPortalBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, LINKING_PORTAL_ID))
            .noCollision()
            .strength(UNBREAKABLE)
            .sound(SoundType.GLASS)
            .lightLevel { PORTAL_LIGHT }
            .pushReaction(PushReaction.IMMOVEABLE)
            .noLootTable(),
    )

    /** What lights a phasmium frame — see [LinkingBookReceptacleBlock]. */
    val LINKING_BOOK_RECEPTACLE_BLOCK: LinkingBookReceptacleBlock = LinkingBookReceptacleBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, LINKING_BOOK_RECEPTACLE_ID))
            .mapColor(MapColor.METAL)
            .strength(PHASMIUM_STRENGTH, PHASMIUM_RESISTANCE)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops(),
    )

    val LINKING_BOOK_RECEPTACLE_ENTITY: BlockEntityType<LinkingBookReceptacleBlockEntity> =
        BlockEntityType(
            { pos, state -> LinkingBookReceptacleBlockEntity(pos, state) },
            setOf(LINKING_BOOK_RECEPTACLE_BLOCK),
        )

    val LINKING_BOOK_RECEPTACLE: Item = BlockItem(
        LINKING_BOOK_RECEPTACLE_BLOCK,
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, LINKING_BOOK_RECEPTACLE_ID))
            .useBlockDescriptionPrefix(),
    )

    /** A nether portal's glow, and its strength: nothing but a command breaks it. */
    private const val PORTAL_LIGHT = 11
    private const val UNBREAKABLE = -1.0f

    /**
     * Drab, and drab deliberately — a dull warm grey with no hue worth naming.
     *
     * It has to read as *grit*: the stuff you scrape off a wall and would walk past in a chest. Everything
     * this material is worth is on the far side of the glowstone and the furnace, and the block and the
     * item are held at the same number on purpose — `items/gloomgrit.json` carries it as a constant tint.
     */
    const val GLOOMGRIT_TINT = 0x6E6B63


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
     * **Read against what [co.voik.agesandtheart.content.RimeSkates] then does with it**, which is where
     * the speed actually comes from: this is the push, and the drag a skater keeps is what turns it into
     * about three times a walk once running. Left where it was when those were retuned, because it is the
     * half of the arrangement a player can read off the boots.
     */
    private const val SKATE_HURRY = 0.35

    /** A boot's, and they take the wear of one. */
    private const val SKATE_DURABILITY = 195

    private val TEMPERSTONE_ID: Identifier = "temperstone".location()
    private val SCORCHED_TEMPERSTONE_ID: Identifier = "scorched_temperstone".location()
    private val RAW_TEMPERSTONE_ID: Identifier = "raw_temperstone".location()
    private val TEMPERSTONE_CLIMBERS_ID: Identifier = "temperstone_climbers".location()
    private val LAVA_TUBE_ID: Identifier = "lava_tube".location()

    private val ASTRITE_SHARD_ID: Identifier = "astrite_shard".location()

    /**
     * What a meteor breaks into (design §7.1.2).
     *
     * **The only thing in the pack that is not of any Age**, which is the whole of what it is for: it did
     * not grow here and it was not written here, it arrived. A body that is caught rather than shattered
     * keeps the metal, and breaking the body open is what yields this.
     */
    val ASTRITE_SHARD_BLOCK: AstriteShardBlock = AstriteShardBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ASTRITE_SHARD_ID))
            .mapColor(MapColor.COLOR_PURPLE)
            .forceSolidOn()
            .noOcclusion()
            .sound(SoundType.AMETHYST_CLUSTER)
            .strength(SHARD_STRENGTH),
    )

    /**
     * **A `BlockItem`, so a shard is set down as readily as it is carried.** Amethyst splits the two —
     * the cluster is a block and the shard it drops is not — but a shard of astrite is one object that
     * happens to be sharp, and a player who has collected a pile of them should be able to build with it.
     */
    val ASTRITE_SHARD: Item = BlockItem(
        ASTRITE_SHARD_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, ASTRITE_SHARD_ID)),
    )

    /** A cluster's, which is what it is: brittle, and no tier asked for. */
    private const val SHARD_STRENGTH = 1.5f

    /** The pack's violet over vanilla's amethyst texture, on the same terms as a rime crystal's. */
    const val ASTRITE_TINT = 0x9E72FF

    private val ASTRITE_BLOCK_ID: Identifier = "astrite_block".location()

    /**
     * Nine shards worked together, and what the golem is built out of (design §7.1.2).
     *
     * **Iron's numbers rather than stone's**, because this is a metal and because the thirty-six shards a
     * companion costs should feel like something you assembled rather than something you piled up. Any
     * pickaxe will do it — the material asks for no tier, on the reasoning that it fell out of the sky.
     */
    val ASTRITE_BLOCK_BLOCK: Block = AstriteBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ASTRITE_BLOCK_ID))
            .mapColor(MapColor.COLOR_PURPLE)
            .strength(ASTRITE_STRENGTH, ASTRITE_RESISTANCE)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops(),
    )

    val ASTRITE_BLOCK: Item = BlockItem(
        ASTRITE_BLOCK_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, ASTRITE_BLOCK_ID)).useBlockDescriptionPrefix(),
    )

    /** Iron's, which is what a block of worked metal should cost to get through. */
    private const val ASTRITE_STRENGTH = 5.0f
    private const val ASTRITE_RESISTANCE = 6.0f

    private val ARC_CRYSTAL_ID: Identifier = "arc_crystal".location()
    private val ARC_CRYSTAL_BLOCK_ID: Identifier = "arc_crystal_block".location()

    /**
     * **Arc crystal** — what a charged Age's drifting ore comes apart into (design §7.1.2).
     *
     * Amethyst's numbers: it is a crystal, it comes away in the hand, and the work was getting it out of
     * the sky rather than out of the ground. It lights faintly on its own, which is what the whole set is
     * green for — a pile of it in a chest reads as charged before anything has been built with it.
     */
    val ARC_CRYSTAL_CLUSTER: Block = Block(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ARC_CRYSTAL_ID))
            .mapColor(MapColor.EMERALD)
            .forceSolidOn()
            .noOcclusion()
            .sound(SoundType.AMETHYST_CLUSTER)
            .lightLevel { ARC_CRYSTAL_GLOW }
            .strength(ARC_CRYSTAL_STRENGTH),
    )

    /** A `BlockItem` for the reason astrite's shard is one: it is one object that can also be set down. */
    val ARC_CRYSTAL: Item = BlockItem(
        ARC_CRYSTAL_CLUSTER,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, ARC_CRYSTAL_ID)).useBlockDescriptionPrefix(),
    )

    /**
     * Nine of them, and **the unit the machines are actually reckoned in**.
     *
     * Force is crystal *per metal block*, so an ambitious array is paid for in these rather than in
     * loose crystal — which is the whole of what makes building the sink.
     */
    val ARC_CRYSTAL_BLOCK_BLOCK: Block = ArcCrystalBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ARC_CRYSTAL_BLOCK_ID))
            .mapColor(MapColor.EMERALD)
            .strength(ARC_CRYSTAL_BLOCK_STRENGTH, ARC_CRYSTAL_BLOCK_RESISTANCE)
            .sound(SoundType.AMETHYST)
            // **A charged pile says so from across a cavern**, which is the only warning it gives before
            // you walk up to collect it.
            .lightLevel { state ->
                if (state.getValue(ArcCrystalBlock.CHARGE) > ArcCrystalBlock.FLAT) CHARGED_CRYSTAL_GLOW
                else ARC_CRYSTAL_BLOCK_GLOW
            }
            .requiresCorrectToolForDrops(),
    )

    /** Settable in midair, which is the material saying what it is — see [MidairBlockItem]. */
    val ARC_CRYSTAL_BLOCK: Item = MidairBlockItem(
        ARC_CRYSTAL_BLOCK_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, ARC_CRYSTAL_BLOCK_ID)).useBlockDescriptionPrefix(),
    )

    /** Faint on its own and brighter in bulk, so a stack of blocks reads as the power supply it is. */
    private const val ARC_CRYSTAL_GLOW = 4
    private const val ARC_CRYSTAL_BLOCK_GLOW = 7
    private const val CHARGED_CRYSTAL_GLOW = 14

    private const val ARC_CRYSTAL_STRENGTH = 1.5f
    private const val ARC_CRYSTAL_BLOCK_STRENGTH = 5.0f
    private const val ARC_CRYSTAL_BLOCK_RESISTANCE = 6.0f

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
            // So heat that reaches it can spoil it — see TemperstoneBlock.randomTick.
            .randomTicks()
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
            .strength(SET_DOWN_OFTEN)
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
    val GEOLOGISTS_TOOLS_BLOCK: GeologistsToolsBlock = GeologistsToolsBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, GEOLOGISTS_TOOLS_ID))
            .mapColor(MapColor.WOOD)
            .strength(STUDY_STRENGTH)
            .sound(SoundType.WOOD),
    )

    val TOOLBOX: Item = BlockItem(
        TOOLBOX_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, TOOLBOX_ID)).useBlockDescriptionPrefix(),
    )

    private val SEISMOGRAPH_ID: Identifier = "seismograph".location()

    /**
     * Reads whether an Age's ground will hold, where the geologist's tools read what is in it (design §7.3).
     *
     * **A mass that does not belong to the ground it measures**, which is the instrument's real physics
     * rather than a pun: a seismometer works only because its bob stays put while the frame moves with the
     * world. Astrite is the one thing in the pack that is not of any Age, so the recipe hangs a shard of it
     * from a chain over a clock-driven drum.
     */
    val SEISMOGRAPH_BLOCK: SeismographBlock = SeismographBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, SEISMOGRAPH_ID))
            .mapColor(MapColor.METAL)
            .strength(STUDY_STRENGTH)
            .sound(SoundType.LANTERN),
    )

    val SEISMOGRAPH: Item = SeismographItem(
        SEISMOGRAPH_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, SEISMOGRAPH_ID)).useBlockDescriptionPrefix(),
    )

    /**
     * The brewing stand's, for the study's blocks: they drop to any tool or none, and the `mineable` tags
     * make the fitting one faster.
     */
    const val STUDY_STRENGTH = 0.5f

    private val CRYSTAL_VIEWER_ID: Identifier = "crystal_viewer".location()

    /**
     * Shows the Age being written at a desk in the room, as its linking panel will, before it is bound
     * (design §7.4). Rime crystals round a spyglass, lit by a copper bulb, on an iron-barred stand.
     */
    val CRYSTAL_VIEWER_BLOCK: CrystalViewerBlock = CrystalViewerBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, CRYSTAL_VIEWER_ID))
            .mapColor(MapColor.COLOR_ORANGE)
            .strength(STUDY_STRENGTH)
            .sound(SoundType.COPPER_BULB),
    )

    val CRYSTAL_VIEWER: Item = BlockItem(
        CRYSTAL_VIEWER_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, CRYSTAL_VIEWER_ID)).useBlockDescriptionPrefix(),
    )

    val GEOLOGISTS_TOOLS: Item = BlockItem(
        GEOLOGISTS_TOOLS_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, GEOLOGISTS_TOOLS_ID))
            .useBlockDescriptionPrefix(),
    )

    /**
     * A leaf's, and the toolbox has it because **it is luggage rather than furniture** (Jonah,
     * 2026-09-09).
     *
     * Everything else made of wood here is built once and stands; this one is carried, set down where the
     * work is and picked up again, so a wooden block's two and a half seconds of chopping is paid over and
     * over for nothing. It keeps its contents when it comes away, so there is nothing to protect either.
     */
    private const val SET_DOWN_OFTEN = 0.2f

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
            .pushReaction(PushReaction.IMMOVEABLE),
    )

    val SURVEYING_DEVICE: Item = BlockItem(
        SURVEYING_DEVICE_BLOCK,
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, SURVEYING_DEVICE_ID))
            .useBlockDescriptionPrefix(),
    )

    /** Lighter than the machine: an instrument you expect to pick up and carry on is worth less digging. */
    private const val SURVEYING_DEVICE_STRENGTH = 2.5f

    private val OBSERVATION_DEVICE_ID: Identifier = "observation_device".location()

    /** See [ObservationDeviceBlock] — set on a phasmium cage, it names the creatures inside. */
    val OBSERVATION_DEVICE_BLOCK: ObservationDeviceBlock = ObservationDeviceBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, OBSERVATION_DEVICE_ID))
            .mapColor(MapColor.COLOR_PURPLE)
            .strength(ANALYSIS_MACHINE_STRENGTH)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops(),
    )

    val OBSERVATION_DEVICE: Item = BlockItem(
        OBSERVATION_DEVICE_BLOCK,
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, OBSERVATION_DEVICE_ID))
            .useBlockDescriptionPrefix(),
    )

    /** The study in progress — see [ObservationDeviceBlockEntity]. */
    val OBSERVATION_DEVICE_ENTITY: BlockEntityType<ObservationDeviceBlockEntity> =
        BlockEntityType({ pos, state -> ObservationDeviceBlockEntity(pos, state) }, setOf(OBSERVATION_DEVICE_BLOCK))

    private val GRINDER_ID: Identifier = "grinder".location()
    private val PULPER_ID: Identifier = "pulper".location()
    private val STATION_ID: Identifier = "station".location()

    /** See [StationBlock] — grinds deretheni to dust for fine ink, and scorched temperstone to gunpowder. */
    val GRINDER_BLOCK: StationBlock = StationBlock(
        Station.GRINDER,
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, GRINDER_ID))
            .mapColor(MapColor.COLOR_BLACK)
            .strength(STATION_STRENGTH)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops(),
    )

    val GRINDER: Item = BlockItem(
        GRINDER_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, GRINDER_ID)).useBlockDescriptionPrefix(),
    )

    /** See [StationBlock] — pulps stripped logs for fine paper. */
    val PULPER_BLOCK: StationBlock = StationBlock(
        Station.PULPER,
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, PULPER_ID))
            .mapColor(MapColor.METAL)
            .strength(STATION_STRENGTH)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops(),
    )

    val PULPER: Item = BlockItem(
        PULPER_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, PULPER_ID)).useBlockDescriptionPrefix(),
    )

    /** One type for both stations; each entity reads its [Station] off its block. */
    val STATION_ENTITY: BlockEntityType<StationBlockEntity> =
        BlockEntityType({ pos, state -> StationBlockEntity(pos, state) }, setOf(GRINDER_BLOCK, PULPER_BLOCK))

    private const val STATION_STRENGTH = 3.5f

    private val PITCHSTONE_DUST_ID: Identifier = "pitchstone_dust".location()
    private val PULP_ID: Identifier = "pulp".location()

    /** What the grinder makes of deretheni, and what fine ink is coloured with. */
    val PITCHSTONE_DUST: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, PITCHSTONE_DUST_ID)))

    /** What the pulper makes of a stripped log, and what fine paper is pressed from. */
    val PULP: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, PULP_ID)))

    private val SCARAB_MEDALLION_ID: Identifier = "scarab_medallion".location()

    /**
     * The scarab detector of design §7.1.2 — salvage from a D'ni ink workshop, since a scarab is what an
     * ink workshop was for. See [ScarabMedallionItem] for what it reads.
     */
    val SCARAB_MEDALLION: Item = ScarabMedallionItem(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, SCARAB_MEDALLION_ID))
            .stacksTo(1),
    )

    private val SCARAB_ID: Identifier = "scarab".location()

    /**
     * The beetle D'ni ink was made from — see [Scarab]. `CREATURE`, being an animal, though no biome lists
     * it: [ScarabArrivals] places every one, so the category decides only what caps and pauses it.
     */
    val SCARAB: EntityType<Scarab> = EntityType.Builder
        .of({ type, level -> Scarab(type, level) }, MobCategory.CREATURE)
        .sized(BEE_WIDTH, BEE_HEIGHT)
        .clientTrackingRange(SCARAB_TRACKING_CHUNKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, SCARAB_ID))

    /** A bee's footprint, since it is drawn as one. */
    private const val BEE_WIDTH = 0.7f
    private const val BEE_HEIGHT = 0.6f
    private const val SCARAB_TRACKING_CHUNKS = 8

    private val SCARAB_NEST_ID: Identifier = ScarabHabitat.NEST.identifier()

    /**
     * The claimed base of a scarab's pillar — see [ScarabNestBlock]. Packed mud's feel, and **mud's drop**:
     * breaking a nest gives back the mud it was made from rather than a nest, which only a scarab makes.
     */
    val SCARAB_NEST_BLOCK: ScarabNestBlock = ScarabNestBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, SCARAB_NEST_ID))
            .mapColor(MapColor.DIRT)
            .strength(NEST_STRENGTH, NEST_RESISTANCE)
            .sound(SoundType.PACKED_MUD),
    )

    private const val NEST_STRENGTH = 1.0f
    private const val NEST_RESISTANCE = 3.0f

    val SCARAB_NEST_ENTITY: BlockEntityType<ScarabNestBlockEntity> =
        BlockEntityType({ pos, state -> ScarabNestBlockEntity(pos, state) }, setOf(SCARAB_NEST_BLOCK))

    /** One scarab to a nest, and it must be at the nest to be home — a bed's numbers. */
    val SCARAB_NEST_POI: PoiType =
        PoiType(SCARAB_NEST_BLOCK.stateDefinition.possibleStates.toSet(), ONE_SCARAB_TO_A_NEST, AT_THE_NEST)

    private const val ONE_SCARAB_TO_A_NEST = 1
    private const val AT_THE_NEST = 1

    private val GRAZED_TORCHFLOWER_ID: Identifier = "grazed_torchflower".location()

    /** A torchflower a scarab ate, growing back — see [GrazedTorchflowerBlock]. The torchflower crop's feel. */
    val GRAZED_TORCHFLOWER_BLOCK: GrazedTorchflowerBlock = GrazedTorchflowerBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, GRAZED_TORCHFLOWER_ID))
            .mapColor(MapColor.PLANT)
            .noCollision()
            .randomTicks()
            .instabreak()
            .sound(SoundType.CROP)
            .pushReaction(PushReaction.POPPED),
    )

    private val SCARAB_CARAPACE_ID: Identifier = "scarab_carapace".location()
    private val ROASTED_CARAPACE_ID: Identifier = "roasted_carapace".location()
    private val CARAPACE_POWDER_ID: Identifier = "carapace_powder".location()

    /**
     * The top rung's chain (design §7.1.2): a scarab killed for its carapace, the carapace smelted, the
     * roasted shell ground to powder. What the powder goes into is the masterwork ink, whose other
     * ingredients are still open.
     */
    val SCARAB_CARAPACE: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, SCARAB_CARAPACE_ID)))
    val ROASTED_CARAPACE: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, ROASTED_CARAPACE_ID)))
    val CARAPACE_POWDER: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, CARAPACE_POWDER_ID)))

    private val PAPER_TREE_LOG_ID: Identifier = "paper_tree_log".location()
    private val STRIPPED_PAPER_TREE_LOG_ID: Identifier = "stripped_paper_tree_log".location()
    private val DEAD_PAPER_TREE_LOG_ID: Identifier = "dead_paper_tree_log".location()
    private val PAPER_TREE_LEAVES_ID: Identifier = "paper_tree_leaves".location()
    private val PAPER_TREE_ROOTS_ID: Identifier = "paper_tree_roots".location()
    private val PAPER_TREE_ROOT_ID: Identifier = "paper_tree_root".location()
    private val PAPER_TREE_SAPLING_ID: Identifier = "paper_tree_sapling".location()
    private val PAPER_TREE_PULP_ID: Identifier = "paper_tree_pulp".location()

    /** Wood's feel, on vanilla's own numbers for a log. */
    private fun woodProperties(id: Identifier, colour: MapColor): BlockBehaviour.Properties =
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, id))
            .mapColor(colour)
            .instrument(NoteBlockInstrument.BASS)
            .strength(LOG_STRENGTH)
            .sound(SoundType.WOOD)
            .ignitedByLava()

    private const val LOG_STRENGTH = 2.0f

    /** Stripped yema — what the pulper makes yema pulp of, the top rung of the paper ladder. */
    val STRIPPED_PAPER_TREE_LOG_BLOCK: RotatedPillarBlock =
        RotatedPillarBlock(woodProperties(STRIPPED_PAPER_TREE_LOG_ID, MapColor.WOOL))

    /** Living yema — see [PaperTreeLogBlock], which knows whether the tree grew it. */
    val PAPER_TREE_LOG_BLOCK: PaperTreeLogBlock =
        PaperTreeLogBlock(woodProperties(PAPER_TREE_LOG_ID, MapColor.COLOR_LIGHT_GRAY)) { STRIPPED_PAPER_TREE_LOG_BLOCK }

    /** Dead yema: building wood and nothing else. It will not pulp into masterwork paper (design §7.1.2). */
    val DEAD_PAPER_TREE_LOG_BLOCK: RotatedPillarBlock =
        RotatedPillarBlock(woodProperties(DEAD_PAPER_TREE_LOG_ID, MapColor.COLOR_GRAY))

    /** See [PaperTreeLeavesBlock] — vanilla's leaves, that turn as the tree fails. */
    val PAPER_TREE_LEAVES_BLOCK: PaperTreeLeavesBlock = PaperTreeLeavesBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, PAPER_TREE_LEAVES_ID))
            .mapColor(MapColor.PLANT)
            .strength(LEAVES_STRENGTH)
            .randomTicks()
            .sound(SoundType.GRASS)
            .noOcclusion()
            .isSuffocating { _, _, _ -> false }
            .isViewBlocking { _, _, _, _ -> false }
            .ignitedByLava()
            .pushReaction(PushReaction.POPPED)
            .isRedstoneConductor { _, _, _ -> false },
    )

    private const val LEAVES_STRENGTH = 0.2f

    /** A pale lavender over vanilla's mangrove leaves, so a grove reads apart from every other tree. */
    const val YEMA_LEAF_TINT = 0xBFA8E0

    /** See [PaperTreeRootsBlock] — the spread of roots that feels the water. Mangrove roots' feel. */
    val PAPER_TREE_ROOTS_BLOCK: PaperTreeRootsBlock = PaperTreeRootsBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, PAPER_TREE_ROOTS_ID))
            .mapColor(MapColor.PODZOL)
            .instrument(NoteBlockInstrument.BASS)
            .strength(ROOTS_STRENGTH)
            .sound(SoundType.MANGROVE_ROOTS)
            .noOcclusion()
            .isSuffocating { _, _, _ -> false }
            .isViewBlocking { _, _, _, _ -> false }
            .ignitedByLava(),
    )

    private const val ROOTS_STRENGTH = 0.7f

    /**
     * The heart — see [PaperTreeRootBlock]. **No item**: the root is the plant and stays where it grew, and
     * breaking it gives back a root, not a heart.
     */
    val PAPER_TREE_ROOT_BLOCK: PaperTreeRootBlock = PaperTreeRootBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, PAPER_TREE_ROOT_ID))
            .mapColor(MapColor.PODZOL)
            .instrument(NoteBlockInstrument.BASS)
            .strength(LOG_STRENGTH)
            .sound(SoundType.MUDDY_MANGROVE_ROOTS),
    )

    val PAPER_TREE_ROOT_ENTITY: BlockEntityType<PaperTreeRootBlockEntity> =
        BlockEntityType({ pos, state -> PaperTreeRootBlockEntity(pos, state) }, setOf(PAPER_TREE_ROOT_BLOCK))

    /** See [PaperTreeSaplingBlock] — cheap to lose, and it keeps the root's rule from the day it is planted. */
    val PAPER_TREE_SAPLING_BLOCK: PaperTreeSaplingBlock = PaperTreeSaplingBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, PAPER_TREE_SAPLING_ID))
            .mapColor(MapColor.PLANT)
            .noCollision()
            .randomTicks()
            .instabreak()
            .sound(SoundType.GRASS)
            .pushReaction(PushReaction.POPPED),
    )

    private fun blockItem(block: Block, id: Identifier): Item =
        BlockItem(block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix())

    val PAPER_TREE_LOG: Item = blockItem(PAPER_TREE_LOG_BLOCK, PAPER_TREE_LOG_ID)
    val STRIPPED_PAPER_TREE_LOG: Item = blockItem(STRIPPED_PAPER_TREE_LOG_BLOCK, STRIPPED_PAPER_TREE_LOG_ID)
    val DEAD_PAPER_TREE_LOG: Item = blockItem(DEAD_PAPER_TREE_LOG_BLOCK, DEAD_PAPER_TREE_LOG_ID)
    val PAPER_TREE_LEAVES: Item = blockItem(PAPER_TREE_LEAVES_BLOCK, PAPER_TREE_LEAVES_ID)
    val PAPER_TREE_ROOTS: Item = blockItem(PAPER_TREE_ROOTS_BLOCK, PAPER_TREE_ROOTS_ID)
    val PAPER_TREE_SAPLING: Item = blockItem(PAPER_TREE_SAPLING_BLOCK, PAPER_TREE_SAPLING_ID)

    /**
     * What the pulper makes of stripped yema: the top rung's pulp, as a carapace's powder is the ink's. The
     * masterwork paper it is pressed into waits on its other ingredients (design §7.1.2, "Open").
     */
    val PAPER_TREE_PULP: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, PAPER_TREE_PULP_ID)))

    /** The three D'ni survey reports, one item each so a plain recipe can ask for all three. */
    val SURVEY_REPORTS: Map<SurveyReport, Item> = SurveyReport.entries.associateWith { report ->
        SurveyReportItem(
            Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, report.id))
                .stacksTo(SURVEY_REPORT_STACK),
            report,
        )
    }

    private const val SURVEY_REPORT_STACK = 16

    private val GRAMMAR_GUIDE_ID: Identifier = "grammar_guide".location()

    /** The D'ni grammar guide (design §7.4), an implement the desk counts; built from the survey reports. */
    val GRAMMAR_GUIDE_BLOCK: Block = Block(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, GRAMMAR_GUIDE_ID))
            .mapColor(MapColor.WOOD)
            .strength(STUDY_STRENGTH)
            .sound(SoundType.WOOD),
    )

    val GRAMMAR_GUIDE: Item = BlockItem(
        GRAMMAR_GUIDE_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, GRAMMAR_GUIDE_ID)).useBlockDescriptionPrefix(),
    )

    private val LINKING_BOOK_ID: Identifier = "linking_book".location()

    /** Unstackable: each one is a different door, even before it is written in. */
    val LINKING_BOOK: Item = LinkingBookItem(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, LINKING_BOOK_ID))
            .stacksTo(1),
    )

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

    /** Fine ink by the bottle — the unit a recipe can actually name, where the tank holds a fluid. */
    val FINE_INK_BOTTLE: Item = Item(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, FINE_INK_BOTTLE_ID))
            .craftRemainder(Items.GLASS_BOTTLE)
            .stacksTo(16),
    )

    private val MASTERWORK_INK_BOTTLE_ID: Identifier = "masterwork_ink_bottle".location()

    /** Nothing crafts with it; what it is for is being found in a D'ni ink workshop and poured into a desk. */
    val MASTERWORK_INK_BOTTLE: Item = Item(
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, MASTERWORK_INK_BOTTLE_ID))
            .craftRemainder(Items.GLASS_BOTTLE)
            .stacksTo(16),
    )

    /** Which ink a bottle holds. One place, so the desk and the recipes cannot disagree. */
    val INK_BOTTLES: Map<InkTier, Item> = mapOf(
        InkTier.COMMON to INK_BOTTLE,
        InkTier.FINE to FINE_INK_BOTTLE,
        InkTier.MASTERWORK to MASTERWORK_INK_BOTTLE,
    )

    private val WRITERS_DESK_ID: Identifier = "writers_desk".location()

    /** Three blocks wide; see [co.voik.agesandtheart.desk.WritersDeskBlock]. */
    val WRITERS_DESK_BLOCK: WritersDeskBlock = WritersDeskBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, WRITERS_DESK_ID))
            .mapColor(MapColor.WOOD)
            .strength(STUDY_STRENGTH)
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

    private val ARCHIVE_ID: Identifier = "archive".location()

    /** Every page filed, without limit — see [co.voik.agesandtheart.desk.ArchiveBlock]. */
    val ARCHIVE_BLOCK: ArchiveBlock = ArchiveBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ARCHIVE_ID))
            .mapColor(MapColor.WOOD)
            .strength(STUDY_STRENGTH)
            .sound(SoundType.CHISELED_BOOKSHELF),
    )

    val ARCHIVE: Item = ArchiveItem(
        ARCHIVE_BLOCK,
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, ARCHIVE_ID))
            .useBlockDescriptionPrefix()
            .stacksTo(1),
    )

    val ARCHIVE_ENTITY: BlockEntityType<ArchiveBlockEntity> =
        BlockEntityType({ pos, state -> ArchiveBlockEntity(pos, state) }, setOf(ARCHIVE_BLOCK))

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
            .pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE),
    )

    private val COLLAPSING_FISSURE_ID: Identifier = "collapsing_fissure".location()

    /**
     * A star fissure that is still opening — see [co.voik.agesandtheart.age.consequence.CollapsingFissureBlock].
     *
     * **The rate is its own, and the random tick is only the safety net.** It books its own next turn at a
     * delay that falls as the Age's instability rises, because vanilla's is one number for the whole world
     * and an Age written to come apart should not spread at the pace of one barely past the threshold;
     * `randomTicks()` stays so that a column something once covered is not inert for ever. Everything else
     * it is, it is by being a star fissure.
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
            .pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE),
    )

    /** The tear itself, and the piece that cuts it — see the fissure package. */
    val STAR_FISSURE_STRUCTURE: StructureType<StarFissureStructure> = StructureType { StarFissureStructure.CODEC }

    val STAR_FISSURE_PIECE: StructurePieceType = StructurePieceType.ContextlessType(::StarFissurePiece)

    /** A machine a D'ni city holds one of — see [DniDevicePiece]. */
    val DNI_DEVICE_PIECE: StructurePieceType = StructurePieceType.ContextlessType(::DniDevicePiece)

    /** The D'ni city — see the `dni` package. */
    val DNI_CITY_STRUCTURE: StructureType<DniCityStructure> = StructureType { DniCityStructure.CODEC }

    val structureTypes: List<Pair<Identifier, StructureType<*>>> = listOf(
        STAR_FISSURE_ID to STAR_FISSURE_STRUCTURE,
        "dni_city".location() to DNI_CITY_STRUCTURE,
    )

    /**
     * 26.2 keeps the codec in the registry itself, where `StructureProcessorType` used to wrap one — so a
     * processor kind *is* its map codec now, and there is nothing left to declare beside it.
     */
    val structureProcessors: List<Pair<Identifier, MapCodec<out StructureProcessor>>> = listOf(
        "loot_table_swap".location() to LootTableSwap.CODEC,
    )

    val structurePieces: List<Pair<Identifier, StructurePieceType>> = listOf(
        STAR_FISSURE_ID to STAR_FISSURE_PIECE,
        "dni_device".location() to DNI_DEVICE_PIECE,
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
     * Molten rock thrown out of a volcano. See [VolcanicBomb].
     *
     * Updated every tick, unlike the sand column: this one arcs, and the whole of its counterplay is
     * watching where it is going to land.
     */
    val VOLCANIC_BOMB: EntityType<VolcanicBomb> = EntityType.Builder
        .of({ type, level -> VolcanicBomb(type, level) }, MobCategory.MISC)
        .sized(BOMB_SIZE, BOMB_SIZE)
        .clientTrackingRange(BOMB_TRACKING_CHUNKS)
        .updateInterval(BOMB_UPDATE_TICKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "volcanic_bomb".location()))

    private const val BOMB_SIZE = 0.98f
    private const val BOMB_TRACKING_CHUNKS = 8
    private const val BOMB_UPDATE_TICKS = 1

    /**
     * A gobbet of molten rock thrown off a bomb's impact. See [LavaDroplet].
     *
     * Tracked less far than a bomb: it lives a second or two and never leaves the crater that made it, so
     * anybody near enough to care is near enough to see it.
     */
    val LAVA_DROPLET: EntityType<LavaDroplet> = EntityType.Builder
        .of({ type, level -> LavaDroplet(type, level) }, MobCategory.MISC)
        .sized(DROPLET_SIZE, DROPLET_SIZE)
        .clientTrackingRange(DROPLET_TRACKING_CHUNKS)
        .updateInterval(BOMB_UPDATE_TICKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "lava_droplet".location()))

    private const val DROPLET_SIZE = 0.4f
    private const val DROPLET_TRACKING_CHUNKS = 4

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

    /**
     * A body out of a meteor storm. See [co.voik.agesandtheart.age.phenomena.Meteor].
     *
     * Updated every tick and tracked far: it arrives fast enough that a client hearing about it late would
     * see it already landed, and the streak is most of what it is.
     */
    val METEOR: EntityType<Meteor> = EntityType.Builder
        .of({ type, level -> Meteor(type, level) }, MobCategory.MISC)
        .sized(METEOR_SIZE, METEOR_SIZE)
        .clientTrackingRange(METEOR_TRACKING_CHUNKS)
        .updateInterval(BOMB_UPDATE_TICKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, Meteor.ID))

    /**
     * A collapse in progress — see [co.voik.agesandtheart.age.phenomena.CaveIn].
     *
     * **Nothing is drawn and nothing is hit**: it is a place where something is happening, and what a
     * player sees is the cracks it puts on the ground. Tracked only as far as its own swathe reaches, since
     * a client that cannot see the blocks has no use for the marker either.
     */
    val CAVE_IN: EntityType<CaveIn> = EntityType.Builder
        .of({ type, level -> CaveIn(type, level) }, MobCategory.MISC)
        .sized(CAVE_IN_SIZE, CAVE_IN_SIZE)
        // **Summonable on purpose**, which is how this gets walked at all: the phenomenon only fires near a
        // player in a written Age, so `/summon agesandtheart:cave_in` is the instrument for looking at one.
        .clientTrackingRange(CAVE_IN_TRACKING_CHUNKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "cave_in".location()))

    /** It has no body; the box is only what an entity must have. */
    private const val CAVE_IN_SIZE = 0.5f
    private const val CAVE_IN_TRACKING_CHUNKS = 6

    /** One column a collapse tear is taking — see [co.voik.agesandtheart.age.consequence.CrumblingColumn]. */
    val CRUMBLING_COLUMN: EntityType<CrumblingColumn> = EntityType.Builder
        .of({ type, level -> CrumblingColumn(type, level) }, MobCategory.MISC)
        .sized(CAVE_IN_SIZE, CAVE_IN_SIZE)
        .clientTrackingRange(CRUMBLING_COLUMN_TRACKING_CHUNKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "crumbling_column".location()))

    private const val CRUMBLING_COLUMN_TRACKING_CHUNKS = 2

    val DRIFTING_ORE: EntityType<DriftingOre> = EntityType.Builder
        .of({ type, level -> DriftingOre(type, level) }, MobCategory.MISC)
        .sized(DRIFTING_ORE_SIZE, DRIFTING_ORE_SIZE)
        .clientTrackingRange(DRIFTING_ORE_TRACKING_CHUNKS)
        .updateInterval(DRIFTING_ORE_UPDATE_TICKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "drifting_ore".location()))

    /**
     * The smallest a body ever is. Every other size comes from `DriftingOre.getDimensions`, because a
     * tier is a width; this is only what a body measures before it has been told which tier it is.
     */
    private const val DRIFTING_ORE_SIZE = 2.0f

    /**
     * The arc a charged machine throws when it bites — see [ArcBolt], which explains why vanilla's own
     * lightning could not be spawned instead.
     *
     * **Tiny, unsaved, and tracked no further than it can be seen.** It is a visual with a three-tick life,
     * so what matters is that one costs almost nothing to send and nothing at all to keep.
     */
    val ARC_BOLT: EntityType<ArcBolt> = EntityType.Builder
        .of({ type, level -> ArcBolt(type, level) }, MobCategory.MISC)
        .sized(ARC_BOLT_SIZE, ARC_BOLT_SIZE)
        .clientTrackingRange(ARC_BOLT_TRACKING_CHUNKS)
        .noSave()
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "arc_bolt".location()))

    private const val ARC_BOLT_SIZE = 0.1f
    private const val ARC_BOLT_TRACKING_CHUNKS = 4

    /**
     * Sixteen chunks, which is vanilla's own longest (the lightning bolt's) and what the bands need: the
     * highest sits at the build limit, a quarter of a kilometre over a player at sea level, and a body
     * nobody is told about cannot be a signpost.
     */
    const val DRIFTING_ORE_TRACKING_CHUNKS = 16
    /**
     * How often a body's position is sent. Read with `DriftingOre.CATCHING_UP_MARGIN`, which has to stay
     * larger — a client that finishes catching up before the next word arrives stands still until it does.
     */
    const val DRIFTING_ORE_UPDATE_TICKS = 2

    /**
     * The storm itself, which is a clock standing in the sky.
     *
     * **Tracked much further than it is big**, because it is nothing to look at and everything to look
     * *for*: the client draws the approach in the sky from this, and a telegraph you can only see once you
     * are already underneath is not one.
     */
    val METEOR_STORM: EntityType<MeteorStorm> = EntityType.Builder
        .of({ type, level -> MeteorStorm(type, level) }, MobCategory.MISC)
        .sized(STORM_SIZE, STORM_SIZE)
        .clientTrackingRange(STORM_TRACKING_CHUNKS)
        .updateInterval(STORM_UPDATE_TICKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, MeteorStorm.ID))

    private const val METEOR_SIZE = 0.7f

    /** Far, because one is thrown from a long way out and crosses that ground in about a second. */
    private const val METEOR_TRACKING_CHUNKS = 16
    private const val STORM_SIZE = 0.5f
    private const val STORM_TRACKING_CHUNKS = 32

    /** It only ever hangs there; what changes about it is its own clock, which a client counts itself. */
    private const val STORM_UPDATE_TICKS = 20

    private val ASTRITE_GOLEM_ID: Identifier = "astrite_golem".location()

    /**
     * The companion assembled out of astrite blocks — see [AstriteGolem].
     *
     * `MISC` rather than `CREATURE`, which is the iron golem's own category and carries the two things
     * that matter: it counts against no spawn cap, and nothing despawns it.
     */
    val ASTRITE_GOLEM: EntityType<AstriteGolem> = EntityType.Builder
        .of({ type, level -> AstriteGolem(type, level) }, MobCategory.MISC)
        .sized(GOLEM_WIDTH, GOLEM_HEIGHT)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, ASTRITE_GOLEM_ID))

    /** An iron golem's footprint, since it is drawn as one. */
    private const val GOLEM_WIDTH = 1.4f
    private const val GOLEM_HEIGHT = 2.7f

    private val HADALFISH_ID: Identifier = "hadalfish".location()

    /**
     * What hunts in the abyss — see [Hadalfish].
     *
     * `MONSTER`, which is what it is, and the footprint is a guardian's *unscaled*: `Attributes.SCALE`
     * multiplies the type's dimensions rather than replacing them, so declaring the big size here would
     * square it into a fish the width of a house.
     */
    val HADALFISH: EntityType<Hadalfish> = EntityType.Builder
        .of({ type, level -> Hadalfish(type, level) }, MobCategory.MONSTER)
        .sized(GUARDIAN_WIDTH, GUARDIAN_HEIGHT)
        .clientTrackingRange(SEEN_FROM_CHUNKS_AWAY)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, HADALFISH_ID))

    /**
     * How far away a client is told this exists, in chunks — and it is a **look** decision, not a
     * networking one.
     *
     * The lure is meant to be seen from across an abyss (`HadalfishRenderer`), and the builder's default
     * of 5 would have put a hard edge at eighty blocks that no falloff of ours could reach past. Ten is the
     * elder guardian's, which is the widest vanilla uses, and it sits above the ~136 blocks at which
     * `Entity.shouldRenderAtSqrDistance` stops drawing something this size — so what ends the light is the
     * fade written for it rather than a pop.
     */
    private const val SEEN_FROM_CHUNKS_AWAY = 10

    private const val GUARDIAN_WIDTH = 0.85f
    private const val GUARDIAN_HEIGHT = 0.85f

    private val HADALFISH_LURE_ID: Identifier = "hadalfish_lure".location()

    /** The light a hadalfish hunts by, dropped when one dies; the observation device is built around it. */
    val HADALFISH_LURE: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, HADALFISH_LURE_ID)))

    val entities: List<Pair<Identifier, EntityType<*>>> = listOf(
        ASTRITE_GOLEM_ID to ASTRITE_GOLEM,
        HADALFISH_ID to HADALFISH,
        SCARAB_ID to SCARAB,
        "cave_in".location() to CAVE_IN,
        "crumbling_column".location() to CRUMBLING_COLUMN,
        "descriptive_book".location() to BOOK_ENTITY,
        "sand_column".location() to SAND_COLUMN,
        "volcanic_bomb".location() to VOLCANIC_BOMB,
        "lava_droplet".location() to LAVA_DROPLET,
        Meteor.ID to METEOR,
        MeteorStorm.ID to METEOR_STORM,
        "drifting_ore".location() to DRIFTING_ORE,
        "arc_bolt".location() to ARC_BOLT,
    ) + PalmWood.entities

    /**
     * Each mob's attributes. A mob is the one kind of entity whose attributes are declared apart from its
     * type, and one with none has no health and is refused at spawn — so this is the second half of
     * registering it, which each loader does from its own attribute hook.
     */
    val mobAttributes: List<Pair<EntityType<out LivingEntity>, () -> AttributeSupplier.Builder>> = listOf(
        ASTRITE_GOLEM to { AstriteGolem.createAttributes() },
        HADALFISH to { Hadalfish.createAttributes() },
        SCARAB to { Scarab.createAttributes() },
    )

    /**
     * Where each of our mobs may be spawned — **the third half of registering one**, beside the type and
     * [mobAttributes].
     *
     * A placement is registered per `EntityType`, so a mob of ours that does not declare one is placed by
     * vanilla's default: on the ground, on the surface heightmap. That default is silent and it is wrong
     * for anything that does not live there, which is how a hadalfish came to hunt dry land — see
     * [Hadalfish.spawnsHere]. A mob of ours that spawns naturally belongs on this list.
     *
     * **A visitor rather than a list of tuples**, because the registration is generic in the mob's own type
     * and the loaders reach it differently: Fabric calls `SpawnPlacements.register` and NeoForge has an
     * event that must be used instead. Only [ASTRITE_GOLEM] and [SCARAB] are absent, and deliberately — we
     * place both ourselves ([ScarabArrivals] the scarab) rather than offering them to a biome, so no
     * placement of vanilla's is ever consulted.
     */
    fun placeWhereTheyBelong(placing: SpawnPlacing) {
        placing.of(
            HADALFISH,
            SpawnPlacementTypes.IN_WATER,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Hadalfish::spawnsHere,
        )
    }

    /**
     * How a loader registers one placement — see [placeWhereTheyBelong].
     *
     * A plain interface rather than a `fun interface`, which Kotlin does not allow a generic method on.
     */
    interface SpawnPlacing {
        fun <T : Mob> of(
            type: EntityType<T>,
            placement: SpawnPlacementType,
            heightmap: Heightmap.Types,
            rule: SpawnPlacements.SpawnPredicate<T>,
        )
    }

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
            .pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE),
    )

    private val ALGAE_ID: Identifier = "algae".location()

    /**
     * **What the D'ni lived on** (design §7.6) — a red mat lying in the top of sunless water, glowing.
     *
     * Grass's numbers: it is a growth, it comes away in the hand, and nothing about harvesting it should
     * be work. Its light is read off the state rather than fixed, because the glow keeps the hour — see
     * [AlgaeBlock.LIT].
     */
    val ALGAE_BLOCK: AlgaeBlock = AlgaeBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ALGAE_ID))
            .mapColor(MapColor.COLOR_RED)
            .replaceable()
            .noCollision()
            .instabreak()
            .sound(SoundType.WET_GRASS)
            .lightLevel(AlgaeBlock::lightAt)
            .randomTicks()
            .noOcclusion()
            // A growth on the water, and nothing about it should survive being shoved: a piston takes it
            // rather than carrying it, the way it takes grass.
            .pushReaction(net.minecraft.world.level.material.PushReaction.POPPED),
    )

    /**
     * Sown or eaten, on one gesture — see [AlgaeItem].
     *
     * **Deliberately poor food.** It is subsistence: what a people shut under the ground ate because it
     * was there, and the reason §7.6's reward ladder has somewhere to go when the processing that makes it
     * worth eating is built.
     */
    val ALGAE: Item = AlgaeItem(
        ALGAE_BLOCK,
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, ALGAE_ID))
            .food(FoodProperties.Builder().nutrition(ALGAE_NUTRITION).saturationModifier(ALGAE_SATURATION).build()),
    )

    private const val ALGAE_NUTRITION = 2
    private const val ALGAE_SATURATION = 0.1f

    /** The pack's red over vanilla's glow lichen, on the same terms as a crystal's — see `AgeTints`. */
    const val ALGAE_TINT = 0xC4384A

    val blocks: List<Pair<Identifier, Block>> = listOf(
        VENT_LINING_ID to VENT_LINING,
        DEEP_BUBBLE_COLUMN_ID to DEEP_BUBBLE_COLUMN,
        PHASMIUM_BLOCK_ID to PHASMIUM_BLOCK_BLOCK,
        LINKING_PORTAL_ID to LINKING_PORTAL,
        LINKING_BOOK_RECEPTACLE_ID to LINKING_BOOK_RECEPTACLE_BLOCK,
        GLOOMGRIT_ID to GLOOMGRIT_CLUSTER,
        WOUND_ID to WOUND_BLOCK,
        WRITERS_DESK_ID to WRITERS_DESK_BLOCK,
        ARCHIVE_ID to ARCHIVE_BLOCK,
        STAR_FISSURE_ID to STAR_FISSURE_BLOCK,
        COLLAPSING_FISSURE_ID to COLLAPSING_FISSURE_BLOCK,
        ANALYSIS_MACHINE_ID to ANALYSIS_MACHINE_BLOCK,
        SURVEYING_DEVICE_ID to SURVEYING_DEVICE_BLOCK,
        OBSERVATION_DEVICE_ID to OBSERVATION_DEVICE_BLOCK,
        GRINDER_ID to GRINDER_BLOCK,
        PULPER_ID to PULPER_BLOCK,
        Compounder.ID to Compounder.BLOCK,
        AdvancedAnalysisMachine.ID to AdvancedAnalysisMachine.BLOCK,
        DryingRack.ID to DryingRack.BLOCK,
        PITCHSTONE_ORE_ID to PITCHSTONE_ORE_BLOCK,
        DEEPSLATE_PITCHSTONE_ORE_ID to DEEPSLATE_PITCHSTONE_ORE_BLOCK,
        PITCHSTONE_BLOCK_ID to PITCHSTONE_BLOCK_BLOCK,
        ASTRITE_BLOCK_ID to ASTRITE_BLOCK_BLOCK,
        ARC_CRYSTAL_ID to ARC_CRYSTAL_CLUSTER,
        ARC_CRYSTAL_BLOCK_ID to ARC_CRYSTAL_BLOCK_BLOCK,
        ASTRITE_SHARD_ID to ASTRITE_SHARD_BLOCK,
        ALGAE_ID to ALGAE_BLOCK,
        *RIME_CRYSTAL_BLOCKS.map { (colour, block) -> colour.id.location() to block }.toTypedArray(),
        TEMPERSTONE_ID to TEMPERSTONE_BLOCK,
        SCORCHED_TEMPERSTONE_ID to SCORCHED_TEMPERSTONE_BLOCK,
        RAW_TEMPERSTONE_ID to RAW_TEMPERSTONE_BLOCK,
        LAVA_TUBE_ID to LAVA_TUBE_BLOCK,
        TOOLBOX_ID to TOOLBOX_BLOCK,
        GEOLOGISTS_TOOLS_ID to GEOLOGISTS_TOOLS_BLOCK,
        SEISMOGRAPH_ID to SEISMOGRAPH_BLOCK,
        CRYSTAL_VIEWER_ID to CRYSTAL_VIEWER_BLOCK,
        GRAMMAR_GUIDE_ID to GRAMMAR_GUIDE_BLOCK,
        SCARAB_NEST_ID to SCARAB_NEST_BLOCK,
        GRAZED_TORCHFLOWER_ID to GRAZED_TORCHFLOWER_BLOCK,
        PAPER_TREE_LOG_ID to PAPER_TREE_LOG_BLOCK,
        STRIPPED_PAPER_TREE_LOG_ID to STRIPPED_PAPER_TREE_LOG_BLOCK,
        DEAD_PAPER_TREE_LOG_ID to DEAD_PAPER_TREE_LOG_BLOCK,
        PAPER_TREE_LEAVES_ID to PAPER_TREE_LEAVES_BLOCK,
        PAPER_TREE_ROOTS_ID to PAPER_TREE_ROOTS_BLOCK,
        PAPER_TREE_ROOT_ID to PAPER_TREE_ROOT_BLOCK,
        PAPER_TREE_SAPLING_ID to PAPER_TREE_SAPLING_BLOCK,
    ) + PalmBeach.blocks + PalmWood.blocks + CompoundedStone.blocks

    /**
     * **Each loader registers these its own way**, and that is the whole platform cost of the profession.
     * NeoForge maps a point of interest's block states off the registry itself; Fabric's `PoiTypes` keeps
     * that map private, so its API rebuilds the type from these three values.
     */
    val poiTypes: List<Pair<Identifier, PoiType>> = listOf(
        WriterProfession.ID to WRITERS_DESK_POI,
        SCARAB_NEST_ID to SCARAB_NEST_POI,
    )

    val villagerProfessions: List<Pair<Identifier, VillagerProfession>> = listOf(
        WriterProfession.ID to WRITER_PROFESSION,
    )

    val blockEntities: List<Pair<Identifier, BlockEntityType<*>>> = listOf(
        TOOLBOX_ID to TOOLBOX_ENTITY,
        WRITERS_DESK_ID to WRITERS_DESK_ENTITY,
        ARCHIVE_ID to ARCHIVE_ENTITY,
        STAR_FISSURE_ID to STAR_FISSURE_ENTITY,
        ANALYSIS_MACHINE_ID to ANALYSIS_MACHINE_ENTITY,
        OBSERVATION_DEVICE_ID to OBSERVATION_DEVICE_ENTITY,
        SCARAB_NEST_ID to SCARAB_NEST_ENTITY,
        PAPER_TREE_ROOT_ID to PAPER_TREE_ROOT_ENTITY,
        STATION_ID to STATION_ENTITY,
        Compounder.ID to Compounder.ENTITY,
        AdvancedAnalysisMachine.ID to AdvancedAnalysisMachine.ENTITY,
        DryingRack.ID to DryingRack.ENTITY,
        LINKING_BOOK_RECEPTACLE_ID to LINKING_BOOK_RECEPTACLE_ENTITY,
    )

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

    /**
     * The seismograph's, which has no slots at all — its whole content is two synced data slots.
     *
     * The client builds its own with a `SimpleContainerData` and the server hands the menu a live one, so
     * the reading arrives through vanilla's own data syncing rather than a payload of ours.
     */
    val SEISMOGRAPH_MENU: MenuType<SeismographMenu> = MenuType(
        { containerId, inventory -> SeismographMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    /** The crystal viewer's, which has no slots either: one synced int, and the panel on its own payloads. */
    val CRYSTAL_VIEWER_MENU: MenuType<CrystalViewerMenu> = MenuType(
        { containerId, inventory -> CrystalViewerMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    /** The geologist's tools' own screen, built the way the seismograph's is and for the same reasons. */
    val GEOLOGISTS_TOOLS_MENU: MenuType<GeologistsToolsMenu> = MenuType(
        { containerId, inventory -> GeologistsToolsMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    /** An archive's: the player's inventory in slots, and its pages on a payload. */
    val ARCHIVE_MENU: MenuType<ArchiveMenu> = MenuType(
        { containerId, inventory -> ArchiveMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    /** Both stations', which differ only in what their block entity works. */
    val STATION_MENU: MenuType<StationMenu> = MenuType(
        { containerId, inventory -> StationMenu(containerId, inventory) },
        FeatureFlags.VANILLA_SET,
    )

    val menus: List<Pair<Identifier, MenuType<*>>> = listOf(
        WRITERS_DESK_ID to WRITERS_DESK_MENU,
        "ink_case".location() to INK_CASE_MENU,
        "supply_bin".location() to SUPPLY_BIN_MENU,
        TOOLBOX_ID to TOOLBOX_MENU,
        SEISMOGRAPH_ID to SEISMOGRAPH_MENU,
        CRYSTAL_VIEWER_ID to CRYSTAL_VIEWER_MENU,
        GEOLOGISTS_TOOLS_ID to GEOLOGISTS_TOOLS_MENU,
        ARCHIVE_ID to ARCHIVE_MENU,
        STATION_ID to STATION_MENU,
        Compounder.ID to Compounder.MENU,
    )

    val recipeSerializers: List<Pair<Identifier, RecipeSerializer<*>>> =
        listOf("repattern_descriptive_book".location() to RepatternBookRecipe.SERIALIZER) + StationRecipes.serializers +
            (Compounding.ID to Compounding.SERIALIZER) + (Drying.ID to Drying.SERIALIZER)

    val recipeTypes: List<Pair<Identifier, RecipeType<*>>> = StationRecipes.types + (Compounding.ID to Compounding.TYPE) + (Drying.ID to Drying.TYPE)

    val recipeBookCategories: List<Pair<Identifier, RecipeBookCategory>> =
        StationRecipes.bookCategories + (Compounding.ID to Compounding.BOOK_CATEGORY) + (Drying.ID to Drying.BOOK_CATEGORY)

    val recipeDisplays: List<Pair<Identifier, RecipeDisplay.Type<*>>> =
        listOf(CompoundingRecipeDisplay.ID to CompoundingRecipeDisplay.TYPE)

    val items: List<Pair<Identifier, Item>> = listOf(
        VENT_LINING_ID to VENT_LINING_ITEM,
        GLOOMGRIT_ID to GLOOMGRIT,
        PHASMIUM_GRAINS_ID to PHASMIUM_GRAINS,
        PHASMIUM_ID to PHASMIUM,
        PHASMIUM_BLOCK_ID to PHASMIUM_BLOCK,
        LINKING_BOOK_RECEPTACLE_ID to LINKING_BOOK_RECEPTACLE,
        DESCRIPTIVE_BOOK_ID to DESCRIPTIVE_BOOK,
        PAGE_ID to PAGE,
        NOTEBOOK_ID to NOTEBOOK,
        WRITERS_DESK_ID to WRITERS_DESK,
        ARCHIVE_ID to ARCHIVE,
        LINKING_BOOK_ID to LINKING_BOOK,
        INK_BOTTLE_ID to INK_BOTTLE,
        FINE_INK_BOTTLE_ID to FINE_INK_BOTTLE,
        MASTERWORK_INK_BOTTLE_ID to MASTERWORK_INK_BOTTLE,
        FINE_PAPER_ID to FINE_PAPER,
        MASTERWORK_PAPER_ID to MASTERWORK_PAPER,
        ANALYSIS_MACHINE_ID to ANALYSIS_MACHINE,
        SURVEYING_DEVICE_ID to SURVEYING_DEVICE,
        OBSERVATION_DEVICE_ID to OBSERVATION_DEVICE,
        HADALFISH_LURE_ID to HADALFISH_LURE,
        GRINDER_ID to GRINDER,
        PULPER_ID to PULPER,
        Compounder.ID to Compounder.ITEM,
        AdvancedAnalysisMachine.ID to AdvancedAnalysisMachine.ITEM,
        AdvancedAnalysisMachine.PROBES_ID to AdvancedAnalysisMachine.PROBES,
        DryingRack.ID to DryingRack.ITEM,
        PITCHSTONE_DUST_ID to PITCHSTONE_DUST,
        PULP_ID to PULP,
        SCARAB_MEDALLION_ID to SCARAB_MEDALLION,
        SCARAB_CARAPACE_ID to SCARAB_CARAPACE,
        ROASTED_CARAPACE_ID to ROASTED_CARAPACE,
        CARAPACE_POWDER_ID to CARAPACE_POWDER,
        PAPER_TREE_LOG_ID to PAPER_TREE_LOG,
        STRIPPED_PAPER_TREE_LOG_ID to STRIPPED_PAPER_TREE_LOG,
        DEAD_PAPER_TREE_LOG_ID to DEAD_PAPER_TREE_LOG,
        PAPER_TREE_LEAVES_ID to PAPER_TREE_LEAVES,
        PAPER_TREE_ROOTS_ID to PAPER_TREE_ROOTS,
        PAPER_TREE_SAPLING_ID to PAPER_TREE_SAPLING,
        PAPER_TREE_PULP_ID to PAPER_TREE_PULP,
        *SURVEY_REPORTS.map { (report, item) -> report.id to item }.toTypedArray(),
        GRAMMAR_GUIDE_ID to GRAMMAR_GUIDE,
        PITCHSTONE_ID to PITCHSTONE,
        PITCHSTONE_ORE_ID to PITCHSTONE_ORE,
        DEEPSLATE_PITCHSTONE_ORE_ID to DEEPSLATE_PITCHSTONE_ORE,
        PITCHSTONE_BLOCK_ID to PITCHSTONE_BLOCK,
        PITCHSTONE_PLATE_ID to PITCHSTONE_PLATE,
        PITCHSTONE_HELMET_ID to PITCHSTONE_HELMET,
        PITCHSTONE_CHESTPLATE_ID to PITCHSTONE_CHESTPLATE,
        PITCHSTONE_LEGGINGS_ID to PITCHSTONE_LEGGINGS,
        PITCHSTONE_BOOTS_ID to PITCHSTONE_BOOTS,
        *RIME_CRYSTALS.map { (colour, item) -> colour.id.location() to item }.toTypedArray(),
        RIME_SKATES_ID to RIME_SKATES,
        TEMPERSTONE_ID to TEMPERSTONE,
        SCORCHED_TEMPERSTONE_ID to SCORCHED_TEMPERSTONE,
        RAW_TEMPERSTONE_ID to RAW_TEMPERSTONE,
        LAVA_TUBE_ID to LAVA_TUBE,
        TEMPERSTONE_CLIMBERS_ID to TEMPERSTONE_CLIMBERS,
        TOOLBOX_ID to TOOLBOX,
        GEOLOGISTS_TOOLS_ID to GEOLOGISTS_TOOLS,
        SEISMOGRAPH_ID to SEISMOGRAPH,
        CRYSTAL_VIEWER_ID to CRYSTAL_VIEWER,
        ARC_CRYSTAL_ID to ARC_CRYSTAL,
        ARC_CRYSTAL_BLOCK_ID to ARC_CRYSTAL_BLOCK,
        ASTRITE_SHARD_ID to ASTRITE_SHARD,
        ALGAE_ID to ALGAE,
        ASTRITE_BLOCK_ID to ASTRITE_BLOCK,
    ) + PalmBeach.items + PalmWood.items + CompoundedStone.items + MasterworkCrafts.items

    /**
     * Loot-function kinds. What makes pages ordinary loot: a pack puts
     * `{ "function": "agesandtheart:roll_page_word" }` on an item entry in any table it authors.
     */
    val lootFunctions: List<Pair<Identifier, MapCodec<out LootItemFunction>>> = listOf(
        "roll_page_word".location() to PageWordFunction.MAP_CODEC,
        "fill_notebook".location() to FillNotebookFunction.MAP_CODEC,
        "bind_linking_book".location() to BindLinkingBookFunction.MAP_CODEC,
        "write_found_book".location() to WriteFoundBookFunction.MAP_CODEC,
        "write_surveyed_book".location() to WriteSurveyedBookFunction.MAP_CODEC,
    )

    /**
     * Our own carvers. The instances that use them are datapack JSON under
     * `data/agesandtheart/worldgen/carver/`; this registers the codecs those dispatch on.
     */
    val carvers: List<Pair<Identifier, MapCodec<out WorldCarver>>> = listOf(
        "porosity".location() to Porosity.CODEC,
    )

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
    ) + PalmBeach.soundEvents

    private val PRESSURE_EFFECT_ID: Identifier = "crushing_pressure".location()

    /**
     * The abyss on a body — see [PressureEffect], and `DeepWater.press` for what hands it out.
     *
     * **The pack's first `MobEffect`**, and it is held as a `Holder` because that is what an effect instance
     * takes: a bare `MobEffect` cannot be applied to anything. The delegate is resolved lazily against the
     * registry this list puts it in, so declaring it here does not depend on registration order.
     */
    val PRESSURE_EFFECT_INSTANCE: PressureEffect = PressureEffect()

    val PRESSURE_EFFECT: Holder<MobEffect> by lazy {
        BuiltInRegistries.MOB_EFFECT.wrapAsHolder(PRESSURE_EFFECT_INSTANCE)
    }

    val mobEffects: List<Pair<Identifier, MobEffect>> = listOf(
        PRESSURE_EFFECT_ID to PRESSURE_EFFECT_INSTANCE,
    )

    /**
     * Our own features. As with [carvers], this registers the *kind*; what is made of it is built in code
     * rather than authored, there being one caller and no reason for a pack to name it.
     */
    val features: List<Pair<Identifier, MapCodec<out Feature>>> = listOf(
        "algae".location() to Algae.CODEC,
        "spilled_spring".location() to SpilledSpring.CODEC,
        "formation".location() to Formation.CODEC,
        "pit_clearing".location() to PitClearing.CODEC,
        "heap".location() to Heap.CODEC,
        "ore_vein".location() to OreVein.CODEC,
        "rime_crystal".location() to RimeCrystal.CODEC,
        "tempered_ground".location() to TemperedGround.CODEC,
        // One kind now: the body of lava is a field, so the two an Age may carry are two entries in
        // `worldgen/feature/` rather than the same code registered under two ids — see [VolcanoVents].
        "volcano_vents".location() to VolcanoVents.CODEC,
        "lava_puddles".location() to LavaPuddles.CODEC,
        "impact_crater".location() to ImpactCrater.CODEC,
        "deep_sea_vent".location() to DeepSeaVent.CODEC,
        "paper_tree".location() to PaperTree.CODEC,
        "paper_tree_grove".location() to PaperTreeGrove.CODEC,
        "scarab_colony".location() to ScarabColony.CODEC,
        "palm_tree".location() to PalmTree.CODEC,
    )
}
