package co.voik.agesandtheart.station

import co.voik.agesandtheart.age.reward.DryingSky
import co.voik.agesandtheart.location
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeBookCategory
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.level.Level

/**
 * What the drying rack turns one thing into, and under what sky (design §7.1.2) — the masterwork grades'
 * last step: an ink cake cured under a black sun, a wet sheet dried in an Age with a lava sea. Each item in the
 * rack dries whole, however many there are in its stack.
 */
class DryingRecipe(
    val ingredient: Ingredient,
    val result: ItemStackTemplate,
    val under: DryingSky,
) : Recipe<SingleRecipeInput> {

    override fun matches(input: SingleRecipeInput, level: Level): Boolean = ingredient.test(input.item())

    override fun assemble(input: SingleRecipeInput): ItemStack = result.create()

    override fun showNotification(): Boolean = false

    override fun group(): String = ""

    override fun getSerializer(): RecipeSerializer<DryingRecipe> = Drying.SERIALIZER

    override fun getType(): RecipeType<DryingRecipe> = Drying.TYPE

    override fun placementInfo(): PlacementInfo = PlacementInfo.create(ingredient)

    override fun recipeBookCategory(): RecipeBookCategory = Drying.BOOK_CATEGORY
}

/** The recipe type, its serializer and its book category, for the loaders to register. */
object Drying {
    val ID: Identifier = "drying".location()

    val TYPE: RecipeType<DryingRecipe> = object : RecipeType<DryingRecipe> {
        override fun toString(): String = ID.toString()
    }

    private val MAP_CODEC: MapCodec<DryingRecipe> = RecordCodecBuilder.mapCodec { instance ->
        instance.group(
            Ingredient.CODEC.fieldOf("ingredient").forGetter { it.ingredient },
            ItemStackTemplate.CODEC.fieldOf("result").forGetter { it.result },
            DryingSky.CODEC.fieldOf("under").forGetter { it.under },
        ).apply(instance, ::DryingRecipe)
    }

    private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, DryingRecipe> = StreamCodec.composite(
        Ingredient.CONTENTS_STREAM_CODEC, DryingRecipe::ingredient,
        ItemStackTemplate.STREAM_CODEC, DryingRecipe::result,
        DryingSky.STREAM_CODEC, DryingRecipe::under,
        ::DryingRecipe,
    )

    val SERIALIZER: RecipeSerializer<DryingRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)

    val BOOK_CATEGORY: RecipeBookCategory = RecipeBookCategory()
}
