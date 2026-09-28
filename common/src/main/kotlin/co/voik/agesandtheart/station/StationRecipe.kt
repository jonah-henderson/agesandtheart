package co.voik.agesandtheart.station

import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeBookCategory
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SingleItemRecipe

/**
 * One input to one result, at the station that names it — a stonecutter recipe's shape, so a pack writes
 * `agesandtheart:grinding` or `agesandtheart:pulping` exactly as it would `minecraft:stonecutting`.
 */
class StationRecipe(
    val station: Station,
    commonInfo: Recipe.CommonInfo,
    ingredient: Ingredient,
    result: ItemStackTemplate,
) : SingleItemRecipe(commonInfo, ingredient, result) {

    override fun getSerializer(): RecipeSerializer<StationRecipe> = StationRecipes.serializerFor(station)

    override fun getType(): RecipeType<StationRecipe> = StationRecipes.typeFor(station)

    override fun group(): String = ""

    override fun recipeBookCategory(): RecipeBookCategory = StationRecipes.bookCategoryFor(station)
}

/** A recipe type, serializer and recipe-book category per [Station], for the loaders to register. */
object StationRecipes {

    private val TYPES: Map<Station, RecipeType<StationRecipe>> =
        Station.entries.associateWith { station -> namedRecipeType(idOf(station)) }

    private val SERIALIZERS: Map<Station, RecipeSerializer<StationRecipe>> =
        Station.entries.associateWith { station ->
            val factory = SingleItemRecipe.Factory { info, ingredient, result ->
                StationRecipe(station, info, ingredient, result)
            }
            RecipeSerializer(SingleItemRecipe.simpleMapCodec(factory), SingleItemRecipe.simpleStreamCodec(factory))
        }

    private val BOOK_CATEGORIES: Map<Station, RecipeBookCategory> =
        Station.entries.associateWith { RecipeBookCategory() }

    fun typeFor(station: Station): RecipeType<StationRecipe> = TYPES.getValue(station)

    fun serializerFor(station: Station): RecipeSerializer<StationRecipe> = SERIALIZERS.getValue(station)

    fun bookCategoryFor(station: Station): RecipeBookCategory = BOOK_CATEGORIES.getValue(station)

    val types: List<Pair<Identifier, RecipeType<*>>> = Station.entries.map { idOf(it) to typeFor(it) }

    val serializers: List<Pair<Identifier, RecipeSerializer<*>>> = Station.entries.map { idOf(it) to serializerFor(it) }

    val bookCategories: List<Pair<Identifier, RecipeBookCategory>> =
        Station.entries.map { idOf(it) to bookCategoryFor(it) }

    private fun idOf(station: Station): Identifier = station.recipeName.location()

    /** Vanilla's own recipe types are anonymous objects named by `toString`; ours are the same. */
    private fun namedRecipeType(id: Identifier): RecipeType<StationRecipe> = object : RecipeType<StationRecipe> {
        override fun toString(): String = id.toString()
    }
}
