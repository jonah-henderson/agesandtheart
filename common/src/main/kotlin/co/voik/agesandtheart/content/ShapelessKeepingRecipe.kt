package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.NonNullList
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.level.Level

/**
 * A shapeless recipe that hands back what [keeps] matches, as it was, rather than using it up — the survey reports
 * a grammar guide is written from.
 *
 * Everything else is the wrapped [ShapelessRecipe]'s, and the JSON is a shapeless recipe's under this type with a
 * `keeps` ingredient beside it.
 */
class ShapelessKeepingRecipe(private val shapeless: ShapelessRecipe, private val keeps: Ingredient) : CraftingRecipe {

    override fun matches(input: CraftingInput, level: Level): Boolean = shapeless.matches(input, level)

    override fun assemble(input: CraftingInput): ItemStack = shapeless.assemble(input)

    override fun getRemainingItems(input: CraftingInput): NonNullList<ItemStack> {
        val remaining = shapeless.getRemainingItems(input)
        for (slot in 0..<input.size()) {
            val held = input.getItem(slot)
            if (keeps.test(held)) remaining[slot] = held.copyWithCount(1)
        }
        return remaining
    }

    override fun showNotification(): Boolean = shapeless.showNotification()

    override fun group(): String = shapeless.group()

    override fun category(): CraftingBookCategory = shapeless.category()

    override fun placementInfo(): PlacementInfo = shapeless.placementInfo()

    override fun display(): List<RecipeDisplay> = shapeless.display()

    override fun getSerializer(): RecipeSerializer<ShapelessKeepingRecipe> = SERIALIZER

    companion object {
        val ID: Identifier = "crafting_shapeless_keeping".location()

        private val MAP_CODEC: MapCodec<ShapelessKeepingRecipe> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                ShapelessRecipe.MAP_CODEC.forGetter { it.shapeless },
                Ingredient.CODEC.fieldOf("keeps").forGetter { it.keeps },
            ).apply(instance, ::ShapelessKeepingRecipe)
        }

        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ShapelessKeepingRecipe> = StreamCodec.composite(
            ShapelessRecipe.STREAM_CODEC,
            { it.shapeless },
            Ingredient.CONTENTS_STREAM_CODEC,
            { it.keeps },
            ::ShapelessKeepingRecipe,
        )

        val SERIALIZER: RecipeSerializer<ShapelessKeepingRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)
    }
}
