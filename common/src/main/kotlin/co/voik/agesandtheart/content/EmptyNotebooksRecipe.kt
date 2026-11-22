package co.voik.agesandtheart.content

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
 * A shaped recipe that takes only empty notebooks, so one holding pages is never crafted away with them. The JSON is a
 * shaped recipe's under this type.
 */
class EmptyNotebooksRecipe(private val shaped: ShapedRecipe) : CraftingRecipe, ShownAsShaped {

    override val shown: ShapedRecipe get() = shaped

    override fun matches(input: CraftingInput, level: Level): Boolean {
        fun holdsPages(stack: ItemStack) = stack.item === AgeContent.NOTEBOOK && NotebookItem.pagesIn(stack).isNotEmpty()
        return shaped.matches(input, level) && input.items().none(::holdsPages)
    }

    override fun assemble(input: CraftingInput): ItemStack = shaped.assemble(input)

    override fun showNotification(): Boolean = shaped.showNotification()

    override fun group(): String = shaped.group()

    override fun category(): CraftingBookCategory = shaped.category()

    override fun placementInfo(): PlacementInfo = shaped.placementInfo()

    override fun display(): List<RecipeDisplay> = shaped.display()

    override fun getSerializer(): RecipeSerializer<EmptyNotebooksRecipe> = SERIALIZER

    companion object {
        val ID: Identifier = "crafting_empty_notebooks".location()

        private val MAP_CODEC: MapCodec<EmptyNotebooksRecipe> = ShapedRecipe.MAP_CODEC.xmap(::EmptyNotebooksRecipe) { it.shaped }

        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, EmptyNotebooksRecipe> =
            ShapedRecipe.STREAM_CODEC.map(::EmptyNotebooksRecipe) { it.shaped }

        val SERIALIZER: RecipeSerializer<EmptyNotebooksRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)
    }
}
