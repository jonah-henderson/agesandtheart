package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.FillNotebookFunction
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.PageWordFunction
import co.voik.agesandtheart.age.word.grammar.Said
import co.voik.agesandtheart.age.consequence.WoundBlock
import co.voik.agesandtheart.book.BindLinkingBookFunction
import co.voik.agesandtheart.book.WriteFoundBookFunction
import co.voik.agesandtheart.age.phenomena.SandColumn
import co.voik.agesandtheart.age.phenomena.LavaDroplet
import co.voik.agesandtheart.age.phenomena.CaveIn
import co.voik.agesandtheart.age.phenomena.DriftingOre
import co.voik.agesandtheart.age.phenomena.Meteor
import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.agesandtheart.age.phenomena.VolcanicBomb
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
import co.voik.agesandtheart.desk.GeologistsToolsMenu
import co.voik.agesandtheart.desk.SeismographMenu
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
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.effect.MobEffect
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
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.carver.CarverConfiguration
import co.voik.agesandtheart.worldgen.feature.Formation
import co.voik.agesandtheart.worldgen.feature.Algae
import co.voik.agesandtheart.worldgen.feature.RimeCrystal
import co.voik.agesandtheart.worldgen.feature.SpilledSpring
import co.voik.agesandtheart.worldgen.feature.TemperedGround
import co.voik.agesandtheart.worldgen.feature.ImpactCrater
import co.voik.agesandtheart.worldgen.feature.LavaPuddles
import co.voik.agesandtheart.worldgen.feature.VolcanoVents
import co.voik.agesandtheart.worldgen.field.StandingFluid
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
            .strength(WOODEN_STRENGTH)
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
            .strength(IRON_STRENGTH)
            .sound(SoundType.LANTERN)
            .requiresCorrectToolForDrops(),
    )

    val SEISMOGRAPH: Item = SeismographItem(
        SEISMOGRAPH_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, SEISMOGRAPH_ID)).useBlockDescriptionPrefix(),
    )

    /** Iron's, which is what the frame is made of. */
    private const val IRON_STRENGTH = 5.0f

    val GEOLOGISTS_TOOLS: Item = BlockItem(
        GEOLOGISTS_TOOLS_BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, GEOLOGISTS_TOOLS_ID))
            .useBlockDescriptionPrefix(),
    )

    /** A cabinet's, which is what both of these are. */
    private const val WOODEN_STRENGTH = 2.5f

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
     * Molten rock thrown out of a volcano. See [co.voik.agesandtheart.age.phenomena.VolcanicBomb].
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
     * A gobbet of molten rock thrown off a bomb's impact.
     * See [co.voik.agesandtheart.age.phenomena.LavaDroplet].
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
     * Charged rock adrift at altitude — see [co.voik.agesandtheart.age.phenomena.DriftingOre].
     *
     * **Tracked far and updated slowly**, which is the pair its design asks for: the whole point of the
     * lowest band is to be *seen from the ground* so that up reads as the direction to explore, and a body
     * that drifts at a fifth of a block a second has nothing worth sending twenty times a second.
     */
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
     * Sixteen chunks, which is vanilla's own longest (the lightning bolt's) and what the bands need: the
     * highest sits at the build limit, a quarter of a kilometre over a player at sea level, and a body
     * nobody is told about cannot be a signpost.
     */
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

    val entities: List<Pair<Identifier, EntityType<*>>> = listOf(
        ASTRITE_GOLEM_ID to ASTRITE_GOLEM,
        HADALFISH_ID to HADALFISH,
        "cave_in".location() to CAVE_IN,
        "descriptive_book".location() to BOOK_ENTITY,
        "sand_column".location() to SAND_COLUMN,
        "volcanic_bomb".location() to VOLCANIC_BOMB,
        "lava_droplet".location() to LAVA_DROPLET,
        Meteor.ID to METEOR,
        MeteorStorm.ID to METEOR_STORM,
        "drifting_ore".location() to DRIFTING_ORE,
        "arc_bolt".location() to ARC_BOLT,
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
            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY),
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
        WOUND_ID to WOUND_BLOCK,
        WRITERS_DESK_ID to WRITERS_DESK_BLOCK,
        STAR_FISSURE_ID to STAR_FISSURE_BLOCK,
        COLLAPSING_FISSURE_ID to COLLAPSING_FISSURE_BLOCK,
        ANALYSIS_MACHINE_ID to ANALYSIS_MACHINE_BLOCK,
        SURVEYING_DEVICE_ID to SURVEYING_DEVICE_BLOCK,
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

    /** The geologist's tools' own screen, built the way the seismograph's is and for the same reasons. */
    val GEOLOGISTS_TOOLS_MENU: MenuType<GeologistsToolsMenu> = MenuType(
        { containerId, inventory -> GeologistsToolsMenu(containerId, inventory, ContainerLevelAccess.NULL) },
        FeatureFlags.VANILLA_SET,
    )

    val menus: List<Pair<Identifier, MenuType<*>>> = listOf(
        WRITERS_DESK_ID to WRITERS_DESK_MENU,
        "ink_case".location() to INK_CASE_MENU,
        "supply_bin".location() to SUPPLY_BIN_MENU,
        TOOLBOX_ID to TOOLBOX_MENU,
        SEISMOGRAPH_ID to SEISMOGRAPH_MENU,
        GEOLOGISTS_TOOLS_ID to GEOLOGISTS_TOOLS_MENU,
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
        ARC_CRYSTAL_ID to ARC_CRYSTAL,
        ARC_CRYSTAL_BLOCK_ID to ARC_CRYSTAL_BLOCK,
        ASTRITE_SHARD_ID to ASTRITE_SHARD,
        ALGAE_ID to ALGAE,
        ASTRITE_BLOCK_ID to ASTRITE_BLOCK,
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

    private val PRESSURE_EFFECT_ID: Identifier = "pressure".location()

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

    val features: List<Pair<Identifier, Feature<*>>> = listOf(
        "algae".location() to Algae,
        "spilled_spring".location() to SpilledSpring,
        "formation".location() to Formation,
        "rime_crystal".location() to RimeCrystal,
        "tempered_ground".location() to TemperedGround,
        // The same routine twice, once per body of lava an Age may carry — see [VolcanoVents].
        "volcano_vents".location() to VolcanoVents(StandingFluid.CRATER_LAKES),
        "chamber_vents".location() to VolcanoVents(StandingFluid.CHAMBER_POOLS),
        "lava_puddles".location() to LavaPuddles,
        "impact_crater".location() to ImpactCrater,
    )
}
