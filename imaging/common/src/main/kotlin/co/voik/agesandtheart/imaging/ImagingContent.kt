package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.content.AgeCreativeTab
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item

/** Everything the companion registers, for each loader to register in its own way. */
object ImagingContent {

    private val PHOTOGRAPHIC_PLATE_ID: Identifier = Imaging.id("photographic_plate")
    private val IMAGE_RECORDER_ID: Identifier = Imaging.id("image_recorder")

    private const val PLATES_TO_A_STACK = 16

    /** What a picture is taken on, one a picture, and what the image recorder draws from the inventory. */
    val PHOTOGRAPHIC_PLATE: Item = Item(properties(PHOTOGRAPHIC_PLATE_ID).stacksTo(PLATES_TO_A_STACK))

    /** The camera. */
    val IMAGE_RECORDER: Item = Item(properties(IMAGE_RECORDER_ID).stacksTo(1))

    val items: List<Pair<Identifier, Item>> = listOf(
        IMAGE_RECORDER_ID to IMAGE_RECORDER,
        PHOTOGRAPHIC_PLATE_ID to PHOTOGRAPHIC_PLATE,
    )

    /** Ages and the Art's creative tab, where the companion's items are shown. */
    val TAB: ResourceKey<CreativeModeTab> = ResourceKey.create(Registries.CREATIVE_MODE_TAB, AgeCreativeTab.ID)

    private fun properties(id: Identifier): Item.Properties = Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
}
