package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.vehicle.boat.Boat
import net.minecraft.world.entity.vehicle.boat.ChestBoat
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BoatItem
import net.minecraft.world.item.DoubleHighBlockItem
import net.minecraft.world.item.HangingSignItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.StandingAndWallBlockItem
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CeilingHangingSignBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.PressurePlateBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.ShelfBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.StandingSignBlock
import net.minecraft.world.level.block.TintedParticleLeavesBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.WallHangingSignBlock
import net.minecraft.world.level.block.WallSignBlock
import net.minecraft.world.level.block.grower.TreeGrower
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockSetType
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.block.state.properties.WoodType
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders
import net.minecraft.world.phys.AABB

/**
 * The palm's wood, a full set as vanilla gives every tree (Jonah, 2026-09-30) — `notes/palm-beach-design.md`.
 *
 * **Birch's numbers throughout**, block for block and item for item, read off the 26.3 jar: the same
 * strengths, fuel times, compost chances and flammability. A palm is not meant to be a better wood, only a
 * different one.
 *
 * **Birch's types under it too, until the asset pass.** 26.3 draws a sign as a block model, which takes a
 * tint like any other, but a boat is drawn from a texture keyed on its model layer, which none reaches — so
 * the palm's boats are plain birch for now, while everything else wears birch's textures under the tints
 * `AgeTints` lays on them. A `WoodType` and `BlockSetType` of our own go in with the art; until then the
 * hanging sign's edit screen is birch's too, and the sounds are birch's either way.
 */
object PalmWood {

    // First, because Kotlin initialises an object's properties in order and every block below reads them.
    private val PLANKS_COLOUR = MapColor.TERRACOTTA_ORANGE
    private val BARK_COLOUR = MapColor.TERRACOTTA_LIGHT_GRAY

    private val registeredBlocks = mutableListOf<Pair<Identifier, Block>>()
    private val registeredItems = mutableListOf<Pair<Identifier, Item>>()

    private fun <B : Block> block(path: String, properties: BlockBehaviour.Properties, make: (BlockBehaviour.Properties) -> B): B {
        val id = path.location()
        return make(properties.setId(ResourceKey.create(Registries.BLOCK, id))).also { registeredBlocks += id to it }
    }

    private fun <I : Item> item(path: String, properties: Item.Properties, make: (Item.Properties) -> I): I {
        val id = path.location()
        return make(properties.setId(ResourceKey.create(Registries.ITEM, id))).also { registeredItems += id to it }
    }

    private fun blockItem(block: Block, path: String, properties: Item.Properties = Item.Properties()): Item =
        item(path, properties.useBlockDescriptionPrefix()) { BlockItem(block, it) }

    // --- Properties, as vanilla's helpers in `Blocks` lay them ---

    private fun planksLike(): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .mapColor(PLANKS_COLOUR)
        .instrument(NoteBlockInstrument.BASS)
        .ignitedByLava()

    private fun logLike(sideColour: MapColor): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .mapColor { state -> if (state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y) PLANKS_COLOUR else sideColour }
        .instrument(NoteBlockInstrument.BASS)
        .strength(LOG_STRENGTH)
        .sound(SoundType.WOOD)
        .ignitedByLava()

    private fun signLike(): BlockBehaviour.Properties = planksLike().forceSolidOn().noCollision().strength(SIGN_STRENGTH)

    /** What vanilla's `wallVariant` makes: the standing block's loot and name, on the wall. */
    private fun onTheWall(standing: Block): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
        .overrideLootTable(standing.lootTable)
        .overrideDescription(standing.descriptionId)

    /** Vanilla's `NEAR_PLANE_INTERSECTS_OUTLINE`, which stairs and slabs block the view by. */
    private fun nearPlaneIntersectsOutline(state: BlockState, level: BlockGetter, pos: BlockPos, nearPlane: AABB): Boolean =
        state.getOcclusionShape().toAabbs().any { box -> box.move(pos).intersects(nearPlane) }

    // --- Blocks ---
    //
    // Stairs, doors, trapdoors, plates and buttons are anonymous subclasses because 26.3 made their
    // constructors protected: vanilla builds them inside `Blocks`, and nothing else is expected to.

    val PLANKS: Block = block("palm_planks", planksLike().strength(PLANKS_STRENGTH, PLANKS_RESISTANCE).sound(SoundType.WOOD), ::Block)

    val STRIPPED_LOG: RotatedPillarBlock = block("stripped_palm_log", logLike(PLANKS_COLOUR), ::RotatedPillarBlock)
    val STRIPPED_WOOD: RotatedPillarBlock =
        block("stripped_palm_wood", planksLike().strength(LOG_STRENGTH).sound(SoundType.WOOD), ::RotatedPillarBlock)
    val LOG: StrippableLogBlock = block("palm_log", logLike(BARK_COLOUR)) { StrippableLogBlock(it) { STRIPPED_LOG } }
    val WOOD: StrippableLogBlock = block("palm_wood", planksLike().mapColor(BARK_COLOUR).strength(LOG_STRENGTH).sound(SoundType.WOOD)) {
        StrippableLogBlock(it) { STRIPPED_WOOD }
    }

    /** The fronds. Vanilla's leaves in every way but the shape the tree arranges them in. */
    val FRONDS: TintedParticleLeavesBlock = block(
        "palm_fronds",
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.PLANT)
            .strength(LEAVES_STRENGTH)
            .randomTicks()
            .sound(SoundType.GRASS)
            .noOcclusion()
            .isValidSpawn { _, _, _, type -> type == EntityTypes.OCELOT || type == EntityTypes.PARROT }
            .isSuffocating { _, _, _ -> false }
            .ignitedByLava()
            .pushReaction(PushReaction.POPPED)
            .isRedstoneConductor { _, _, _ -> false },
    ) { TintedParticleLeavesBlock(LEAF_PARTICLE_CHANCE, it) }

    /** The tree a sapling grows into — `worldgen/feature/palm_tree.json`, which is the same tree the beach grows. */
    val TREE: ResourceKey<Feature> = ResourceKey.create(Registries.FEATURE, "palm_tree".location())

    private val GROWER = TreeGrower("agesandtheart:palm", WeightedList.of(TREE), WeightedList.of(), WeightedList.of(), TREE)

    val SAPLING: PalmSaplingBlock = block(
        "palm_sapling",
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.PLANT)
            .noCollision()
            .randomTicks()
            .instabreak()
            .sound(SoundType.GRASS)
            .pushReaction(PushReaction.POPPED),
    ) { PalmSaplingBlock(GROWER, it) }

    val POTTED_SAPLING: FlowerPotBlock = block(
        "potted_palm_sapling",
        BlockBehaviour.Properties.of().instabreak().noOcclusion().pushReaction(PushReaction.POPPED),
    ) { FlowerPotBlock(SAPLING, it) }

    val COCONUT: CoconutBlock = block(
        "coconut",
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.TERRACOTTA_BROWN)
            .randomTicks()
            .strength(COCONUT_STRENGTH, COCONUT_RESISTANCE)
            .sound(SoundType.WOOD)
            .noOcclusion()
            .pushReaction(PushReaction.POPPED),
        ::CoconutBlock,
    )

    val STAIRS: StairBlock = block(
        "palm_stairs",
        BlockBehaviour.Properties.ofLegacyCopy(PLANKS).isViewBlocking(::nearPlaneIntersectsOutline),
    ) { object : StairBlock(PLANKS.defaultBlockState(), it) {} }

    val SLAB: SlabBlock =
        block("palm_slab", BlockBehaviour.Properties.ofLegacyCopy(PLANKS).isViewBlocking(::nearPlaneIntersectsOutline), ::SlabBlock)

    val FENCE: FenceBlock = block("palm_fence", planksLike().strength(PLANKS_STRENGTH, PLANKS_RESISTANCE).sound(SoundType.WOOD), ::FenceBlock)

    val FENCE_GATE: FenceGateBlock = block("palm_fence_gate", planksLike().forceSolidOn().strength(PLANKS_STRENGTH, PLANKS_RESISTANCE)) {
        FenceGateBlock(WoodType.BIRCH, it)
    }

    val DOOR: DoorBlock = block("palm_door", planksLike().strength(DOOR_STRENGTH).noOcclusion().pushReaction(PushReaction.POPPED)) {
        object : DoorBlock(BlockSetType.BIRCH, it) {}
    }

    val TRAPDOOR: TrapDoorBlock = block(
        "palm_trapdoor",
        planksLike().strength(DOOR_STRENGTH).noOcclusion().isValidSpawn { _, _, _, _ -> false },
    ) { object : TrapDoorBlock(BlockSetType.BIRCH, it) {} }

    val PRESSURE_PLATE: PressurePlateBlock = block(
        "palm_pressure_plate",
        planksLike().forceSolidOn().noCollision().strength(PLATE_STRENGTH).pushReaction(PushReaction.POPPED),
    ) { object : PressurePlateBlock(BlockSetType.BIRCH, it) {} }

    val BUTTON: ButtonBlock = block(
        "palm_button",
        BlockBehaviour.Properties.of().noCollision().strength(PLATE_STRENGTH).pushReaction(PushReaction.POPPED),
    ) { object : ButtonBlock(BlockSetType.BIRCH, BUTTON_TICKS, it) {} }

    val SIGN: StandingSignBlock = block("palm_sign", signLike()) { StandingSignBlock(WoodType.BIRCH, it) }
    val WALL_SIGN: WallSignBlock = block("palm_wall_sign", onTheWall(SIGN).mapColor(PLANKS_COLOUR).forceSolidOn()
        .instrument(NoteBlockInstrument.BASS).noCollision().strength(SIGN_STRENGTH).ignitedByLava()) { WallSignBlock(WoodType.BIRCH, it) }
    val HANGING_SIGN: CeilingHangingSignBlock = block("palm_hanging_sign", signLike()) { CeilingHangingSignBlock(WoodType.BIRCH, it) }
    val WALL_HANGING_SIGN: WallHangingSignBlock = block("palm_wall_hanging_sign", onTheWall(HANGING_SIGN).mapColor(PLANKS_COLOUR)
        .forceSolidOn().instrument(NoteBlockInstrument.BASS).noCollision().strength(SIGN_STRENGTH).ignitedByLava()) {
        WallHangingSignBlock(WoodType.BIRCH, it)
    }

    val SHELF: ShelfBlock = block("palm_shelf", planksLike().sound(SoundType.SHELF).strength(PLANKS_STRENGTH, PLANKS_RESISTANCE), ::ShelfBlock)

    // --- Boats, which are entities of their own since 1.21.2 ---

    val BOAT: EntityType<Boat> = EntityType.Builder
        .of({ type, level -> Boat(type, level) { BOAT_ITEM } }, MobCategory.MISC)
        .noLootTable()
        .sized(BOAT_WIDTH, BOAT_HEIGHT)
        .eyeHeight(BOAT_HEIGHT)
        .clientTrackingRange(BOAT_TRACKING_CHUNKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "palm_boat".location()))

    val CHEST_BOAT: EntityType<ChestBoat> = EntityType.Builder
        .of({ type, level -> ChestBoat(type, level) { CHEST_BOAT_ITEM } }, MobCategory.MISC)
        .noLootTable()
        .sized(BOAT_WIDTH, BOAT_HEIGHT)
        .eyeHeight(BOAT_HEIGHT)
        .clientTrackingRange(BOAT_TRACKING_CHUNKS)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, "palm_chest_boat".location()))

    // --- Items ---

    private fun burnsAs(time: ResourceKey<ContextIntProvider>) =
        Item.Properties().cookingFuel(time)

    init {
        blockItem(PLANKS, "palm_planks", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(LOG, "palm_log", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(WOOD, "palm_wood", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(STRIPPED_LOG, "stripped_palm_log", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(STRIPPED_WOOD, "stripped_palm_wood", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(FRONDS, "palm_fronds", Item.Properties().compostable(ContextIntProviders.COMPOSTABLE_LOW))
        blockItem(
            SAPLING,
            "palm_sapling",
            Item.Properties().compostable(ContextIntProviders.COMPOSTABLE_LOW).cookingFuel(ContextIntProviders.COOKING_TIME_DRY_PLANTS),
        )
        blockItem(COCONUT, "coconut", Item.Properties().compostable(ContextIntProviders.COMPOSTABLE_MEDIUM))
        blockItem(STAIRS, "palm_stairs", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(SLAB, "palm_slab", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_SLABS))
        blockItem(FENCE, "palm_fence", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(FENCE_GATE, "palm_fence_gate", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        item("palm_door", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_ITEMS_LARGE).useBlockDescriptionPrefix()) {
            DoubleHighBlockItem(DOOR, it)
        }
        blockItem(TRAPDOOR, "palm_trapdoor", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(PRESSURE_PLATE, "palm_pressure_plate", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS))
        blockItem(BUTTON, "palm_button", burnsAs(ContextIntProviders.COOKING_TIME_WOOD_ITEMS_EXTRA_SMALL))
        item(
            "palm_sign",
            burnsAs(ContextIntProviders.COOKING_TIME_WOOD_ITEMS_LARGE).stacksTo(SIGN_STACK).signText().useBlockDescriptionPrefix(),
        ) { StandingAndWallBlockItem(SIGN, WALL_SIGN, Direction.DOWN, it) }
        item(
            "palm_hanging_sign",
            burnsAs(ContextIntProviders.COOKING_TIME_HANGING_SIGNS).stacksTo(SIGN_STACK).signText().useBlockDescriptionPrefix(),
        ) { HangingSignItem(HANGING_SIGN, WALL_HANGING_SIGN, it) }
        blockItem(
            SHELF,
            "palm_shelf",
            burnsAs(ContextIntProviders.COOKING_TIME_WOOD_BLOCKS).component(DataComponents.CONTAINER, ItemContainerContents.EMPTY),
        )
    }

    val BOAT_ITEM: Item = item("palm_boat", burnsAs(ContextIntProviders.COOKING_TIME_BOATS).stacksTo(1)) { BoatItem(BOAT, it) }
    val CHEST_BOAT_ITEM: Item =
        item("palm_chest_boat", burnsAs(ContextIntProviders.COOKING_TIME_BOATS).stacksTo(1)) { BoatItem(CHEST_BOAT, it) }

    // --- What the rest of the game is told ---

    /** The log, the wood and their stripped forms — `#agesandtheart:palm_logs`, which a coconut hangs from. */
    val LOGS: TagKey<Block> = TagKey.create(Registries.BLOCK, "palm_logs".location())

    val blocks: List<Pair<Identifier, Block>> get() = registeredBlocks
    val items: List<Pair<Identifier, Item>> get() = registeredItems

    val entities: List<Pair<Identifier, EntityType<*>>> = listOf(
        "palm_boat".location() to BOAT,
        "palm_chest_boat".location() to CHEST_BOAT,
    )

    /** What takes fire and how readily, as `FireBlock.bootStrap` has it for birch: (ignite, burn). */
    val flammable: List<Triple<Block, Int, Int>> = listOf(
        Triple(PLANKS, 5, 20), Triple(SLAB, 5, 20), Triple(FENCE_GATE, 5, 20), Triple(FENCE, 5, 20), Triple(STAIRS, 5, 20),
        Triple(LOG, 5, 5), Triple(STRIPPED_LOG, 5, 5), Triple(STRIPPED_WOOD, 5, 5), Triple(WOOD, 5, 5),
        Triple(FRONDS, 30, 60), Triple(SHELF, 30, 20),
    )

    /** The blocks vanilla's sign, hanging sign and shelf block entities must be told to accept. */
    val signs: Set<Block> = setOf(SIGN, WALL_SIGN)
    val hangingSigns: Set<Block> = setOf(HANGING_SIGN, WALL_HANGING_SIGN)
    val shelves: Set<Block> = setOf(SHELF)

    /**
     * The colours the palm is drawn in until the asset pass, over birch's textures and a fern's — see
     * `AgeTints` for why a tint. Chosen against the eleven woods already in the game, measured 2026-09-30:
     *
     * - **[PLANKS_TINT]**, over birch planks (192, 175, 121), comes out about (191, 133, 74): a warm caramel
     *   at hue 30, lighter and more saturated than oak and jungle, more orange than birch and bamboo, less red
     *   than acacia. Coconut timber's own colour, and the one gap in the palette near it.
     * - **[BARK_TINT]**, over birch bark (216, 215, 210), comes out a light grey-tan, about (165, 145, 120):
     *   a palm's trunk, with birch's dark horizontal marks reading as the rings a palm's fallen fronds leave.
     * - **[FRONDS_TINT]**, over jungle leaves' greys, a bright yellow-green lighter than any foliage the game
     *   tints — the colour a frond is with the sun through it.
     * - **[COCONUT_TINT]** darkens cocoa's orange pod to a husk.
     */
    const val PLANKS_TINT = 0xFDC29C
    const val BARK_TINT = 0xC3AC92
    const val FRONDS_TINT = 0xB4F05A
    const val COCONUT_TINT = 0xC8A878

    private const val PLANKS_STRENGTH = 2.0f
    private const val PLANKS_RESISTANCE = 3.0f
    private const val LOG_STRENGTH = 2.0f
    private const val LEAVES_STRENGTH = 0.2f
    private const val LEAF_PARTICLE_CHANCE = 0.01f
    private const val DOOR_STRENGTH = 3.0f
    private const val PLATE_STRENGTH = 0.5f
    private const val SIGN_STRENGTH = 1.0f
    private const val BUTTON_TICKS = 30
    private const val SIGN_STACK = 16
    private const val COCONUT_STRENGTH = 0.2f
    private const val COCONUT_RESISTANCE = 3.0f
    private const val BOAT_WIDTH = 1.375f
    private const val BOAT_HEIGHT = 0.5625f
    private const val BOAT_TRACKING_CHUNKS = 10
}
