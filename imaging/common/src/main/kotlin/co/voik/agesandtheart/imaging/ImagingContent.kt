package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.content.AgeCreativeTab
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.PushReaction

/** Everything the companion registers, for each loader to register in its own way. */
object ImagingContent {

    private val PHOTOGRAPHIC_PLATE_ID: Identifier = Imaging.id("photographic_plate")
    private val IMAGE_RECORDER_ID: Identifier = Imaging.id("image_recorder")
    private val RECORDED_IMAGE_ID: Identifier = Imaging.id("recorded_image")
    private val HUNG_PICTURE_ID: Identifier = Imaging.id("hung_picture")
    private val SHUTTER_ID: Identifier = Imaging.id("image_recorder.shutter")
    private val SLIDE_CAROUSEL_ID: Identifier = Imaging.id("slide_carousel")
    private val TRIPOD_ID: Identifier = Imaging.id("tripod")

    private const val PLATES_TO_A_STACK = 64
    private const val CAROUSEL_STRENGTH = 1.5f
    private const val TRIPOD_STRENGTH = 0.5f

    /** A painting's footprint and tracking range, before its bounds are worked out from its wall. */
    private const val HANGING_SIZE = 0.5f
    private const val HANGING_TRACKING_CHUNKS = 10

    /** What a picture is taken on, one a picture, and what the image recorder draws from the inventory. */
    val PHOTOGRAPHIC_PLATE: Item = Item(properties(PHOTOGRAPHIC_PLATE_ID).stacksTo(PLATES_TO_A_STACK))

    /** The camera set down. */
    val IMAGE_RECORDER_BLOCK: Block = ImageRecorderBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, IMAGE_RECORDER_ID))
            .instabreak()
            .noOcclusion()
            .sound(SoundType.LANTERN)
            .pushReaction(PushReaction.POPPED),
    )

    /** The camera. */
    val IMAGE_RECORDER: Item = ImageRecorderItem(IMAGE_RECORDER_BLOCK, properties(IMAGE_RECORDER_ID).stacksTo(1))

    /** A picture taken, one to a stack. */
    val RECORDED_IMAGE: Item = RecordedImageItem(properties(RECORDED_IMAGE_ID).stacksTo(1))

    /** A recorded image on a wall, tracked and sent as a painting is. */
    val HUNG_PICTURE: EntityType<HungPicture> = EntityType.Builder
        .of({ type, level -> HungPicture(type, level) }, MobCategory.MISC)
        .noLootTable()
        .sized(HANGING_SIZE, HANGING_SIZE)
        .clientTrackingRange(HANGING_TRACKING_CHUNKS)
        .updateInterval(Int.MAX_VALUE)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, HUNG_PICTURE_ID))

    val entities: List<Pair<Identifier, EntityType<*>>> = listOf(HUNG_PICTURE_ID to HUNG_PICTURE)

    /** The shutter going; vanilla's click stands in until a shutter of its own is recorded (`sounds.json`). */
    val SHUTTER: SoundEvent = SoundEvent.createVariableRangeEvent(SHUTTER_ID)

    val soundEvents: List<Pair<Identifier, SoundEvent>> = listOf(SHUTTER_ID to SHUTTER)

    /** The projector's slide carousel, which the light behind it throws through the glass ahead of it. */
    val SLIDE_CAROUSEL_BLOCK: Block = SlideCarouselBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, SLIDE_CAROUSEL_ID))
            .strength(CAROUSEL_STRENGTH)
            .noOcclusion()
            .sound(SoundType.WOOD),
    )

    val SLIDE_CAROUSEL: Item = SlideCarouselItem(SLIDE_CAROUSEL_BLOCK, properties(SLIDE_CAROUSEL_ID).useBlockDescriptionPrefix())

    /** What a set-down recorder is mounted on to be aimed at anything ([TripodBlock]); two blocks tall, as a door is. */
    val TRIPOD_BLOCK: Block = TripodBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, TRIPOD_ID))
            .strength(TRIPOD_STRENGTH)
            .noOcclusion()
            .sound(SoundType.WOOD)
            .pushReaction(PushReaction.POPPED),
    )

    val TRIPOD: Item = BlockItem(TRIPOD_BLOCK, properties(TRIPOD_ID).useBlockDescriptionPrefix())

    val SLIDE_CAROUSEL_ENTITY: BlockEntityType<SlideCarouselBlockEntity> =
        BlockEntityType({ pos, state -> SlideCarouselBlockEntity(pos, state) }, setOf(SLIDE_CAROUSEL_BLOCK))

    val IMAGE_RECORDER_ENTITY: BlockEntityType<ImageRecorderBlockEntity> =
        BlockEntityType({ pos, state -> ImageRecorderBlockEntity(pos, state) }, setOf(IMAGE_RECORDER_BLOCK))

    val IMAGE_RECORDER_MENU: MenuType<ImageRecorderMenu> = MenuType(::ImageRecorderMenu, FeatureFlags.VANILLA_SET)

    val menus: List<Pair<Identifier, MenuType<*>>> = listOf(IMAGE_RECORDER_ID to IMAGE_RECORDER_MENU)

    val blockEntities: List<Pair<Identifier, BlockEntityType<*>>> =
        listOf(IMAGE_RECORDER_ID to IMAGE_RECORDER_ENTITY, SLIDE_CAROUSEL_ID to SLIDE_CAROUSEL_ENTITY)

    val blocks: List<Pair<Identifier, Block>> = listOf(
        IMAGE_RECORDER_ID to IMAGE_RECORDER_BLOCK,
        SLIDE_CAROUSEL_ID to SLIDE_CAROUSEL_BLOCK,
        TRIPOD_ID to TRIPOD_BLOCK,
    )

    val items: List<Pair<Identifier, Item>> = listOf(
        IMAGE_RECORDER_ID to IMAGE_RECORDER,
        PHOTOGRAPHIC_PLATE_ID to PHOTOGRAPHIC_PLATE,
        RECORDED_IMAGE_ID to RECORDED_IMAGE,
        SLIDE_CAROUSEL_ID to SLIDE_CAROUSEL,
        TRIPOD_ID to TRIPOD,
    )

    /** Ages and the Art's creative tab, where the companion's items are shown. */
    val TAB: ResourceKey<CreativeModeTab> = ResourceKey.create(Registries.CREATIVE_MODE_TAB, AgeCreativeTab.ID)

    /** What the creative tab shows: a recorded image means nothing without a picture taken. */
    val shownInTheTab: List<Item> = listOf(IMAGE_RECORDER, PHOTOGRAPHIC_PLATE, TRIPOD, SLIDE_CAROUSEL)

    private fun properties(id: Identifier): Item.Properties = Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
}
