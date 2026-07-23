package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.component.DataComponentType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item

/**
 * The mod's registered content, defined loader-agnostically.
 *
 * Instances are built eagerly here (constructing them needs no registry); the *registration*
 * is driven per loader — Fabric registers directly during init, NeoForge via `RegisterEvent` —
 * with each loader simply iterating [components] then [items]. See each loader's entrypoint.
 */
object AgeContent {
    /**
     * Data component stored on a Descriptive Book stack: the id of the Age it links to.
     * `persistent` = saved to disk; `networkSynchronized` = sent to the client.
     */
    val AGE_ID: DataComponentType<ResourceLocation> = DataComponentType.builder<ResourceLocation>()
        .persistent(ResourceLocation.CODEC)
        .networkSynchronized(ResourceLocation.STREAM_CODEC)
        .build()

    /** Unstackable so each book keeps its own [AGE_ID] identity. */
    val DESCRIPTIVE_BOOK: Item = DescriptiveBookItem(Item.Properties().stacksTo(1))

    val components: List<Pair<ResourceLocation, DataComponentType<*>>> = listOf(
        "age_id".location() to AGE_ID,
    )

    val items: List<Pair<ResourceLocation, Item>> = listOf(
        "descriptive_book".location() to DESCRIPTIVE_BOOK,
    )
}
