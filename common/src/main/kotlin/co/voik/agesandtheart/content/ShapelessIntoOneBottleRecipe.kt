package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import com.mojang.serialization.MapCodec
import net.minecraft.core.NonNullList
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.level.Level

/**
 * A shapeless recipe whose bottled ingredients are poured into one bottle: the result keeps one of the
 * empty bottles they leave, so one fewer comes back than vanilla's remainders would give.
 *
 * Everything else is the wrapped [ShapelessRecipe]'s, and the JSON is a shapeless recipe's under this type.
 */
class ShapelessIntoOneBottleRecipe(private val shapeless: ShapelessRecipe) : CraftingRecipe {

    override fun matches(input: CraftingInput, level: Level): Boolean = shapeless.matches(input, level)

    override fun assemble(input: CraftingInput): ItemStack = shapeless.assemble(input)

    override fun getRemainingItems(input: CraftingInput): NonNullList<ItemStack> {
        val remaining = shapeless.getRemainingItems(input)
        val keptBottle = remaining.indexOfFirst { it.`is`(Items.GLASS_BOTTLE) }
        if (keptBottle >= 0) remaining[keptBottle] = ItemStack.EMPTY
        return remaining
    }

    override fun showNotification(): Boolean = shapeless.showNotification()

    override fun group(): String = shapeless.group()

    override fun category(): CraftingBookCategory = shapeless.category()

    override fun placementInfo(): PlacementInfo = shapeless.placementInfo()

    override fun display(): List<RecipeDisplay> = shapeless.display()

    override fun getSerializer(): RecipeSerializer<ShapelessIntoOneBottleRecipe> = SERIALIZER

    companion object {
        val ID: Identifier = "crafting_shapeless_into_one_bottle".location()

        private val MAP_CODEC: MapCodec<ShapelessIntoOneBottleRecipe> =
            ShapelessRecipe.MAP_CODEC.xmap(::ShapelessIntoOneBottleRecipe) { it.shapeless }

        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ShapelessIntoOneBottleRecipe> =
            ShapelessRecipe.STREAM_CODEC.map(::ShapelessIntoOneBottleRecipe) { it.shapeless }

        val SERIALIZER: RecipeSerializer<ShapelessIntoOneBottleRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)
    }
}
