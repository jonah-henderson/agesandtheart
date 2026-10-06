package co.voik.agesandtheart.content

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.location
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack

/**
 * The mod's creative tab: every item of ours, in the order they were registered. Read off the registry
 * rather than listed, so an item added anywhere, a loader's fluid buckets included, turns up without
 * anyone remembering to add it here. Each loader supplies its own builder, which is all that differs.
 */
object AgeCreativeTab {
    val ID: Identifier = Constants.MOD_ID.location()

    private const val TITLE_KEY = "itemGroup.${Constants.MOD_ID}"

    fun built(builder: CreativeModeTab.Builder): CreativeModeTab = builder
        .title(Component.translatable(TITLE_KEY))
        .icon { ItemStack(AgeContent.DESCRIPTIVE_BOOK) }
        .displayItems { _, output ->
            BuiltInRegistries.ITEM.entrySet()
                .filter { (key, _) -> key.identifier().namespace == Constants.MOD_ID }
                .sortedBy { (_, item) -> BuiltInRegistries.ITEM.getId(item) }
                .forEach { (_, item) -> output.accept(item) }
        }
        .build()
}
