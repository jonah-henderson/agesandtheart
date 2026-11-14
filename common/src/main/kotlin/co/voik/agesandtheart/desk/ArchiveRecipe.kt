package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.NotebookItem
import co.voik.agesandtheart.location
import co.voik.agesandtheart.station.ShownAsShaped
import com.mojang.serialization.MapCodec
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.level.Level

/**
 * The archive's recipe — a bookshelf's, with notebooks where the books go — and **the pages those notebooks held
 * are filed into the archive it makes**. A notebook is the folder, not part of the contents, so crafting it into an
 * archive files it just as using it on one does ([ArchiveIntake]); the result slot already shows the count.
 *
 * Everything else is the wrapped [ShapedRecipe]'s, and the JSON is a shaped recipe's under this type.
 */
class ArchiveRecipe(private val shaped: ShapedRecipe) : CraftingRecipe, ShownAsShaped {

    override val shown: ShapedRecipe get() = shaped

    override fun matches(input: CraftingInput, level: Level): Boolean = shaped.matches(input, level)

    override fun assemble(input: CraftingInput): ItemStack {
        val archive = shaped.assemble(input)
        val filed = input.items().flatMap(NotebookItem::pagesIn).fold(PageArchive.EMPTY) { filed, page ->
            page.get(AgeComponents.PAGE_WORD)?.let { filed.with(it, page.count) } ?: filed
        }
        if (!filed.isEmpty) archive.set(AgeComponents.ARCHIVE_PAGES, filed)
        return archive
    }

    override fun showNotification(): Boolean = shaped.showNotification()

    override fun group(): String = shaped.group()

    override fun category(): CraftingBookCategory = shaped.category()

    override fun placementInfo(): PlacementInfo = shaped.placementInfo()

    override fun display(): List<RecipeDisplay> = shaped.display()

    override fun getSerializer(): RecipeSerializer<ArchiveRecipe> = SERIALIZER

    companion object {
        val ID: Identifier = "crafting_archive".location()

        private val MAP_CODEC: MapCodec<ArchiveRecipe> = ShapedRecipe.MAP_CODEC.xmap(::ArchiveRecipe) { it.shaped }

        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ArchiveRecipe> =
            ShapedRecipe.STREAM_CODEC.map(::ArchiveRecipe) { it.shaped }

        val SERIALIZER: RecipeSerializer<ArchiveRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)
    }
}
