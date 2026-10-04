package co.voik.agesandtheart

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Whether every recipe a recipe book shows has an advancement that teaches it.
 *
 * A recipe with none is learned only by vanilla's fallback -- obtaining its result -- so it appears in the
 * book *after* the player has made one, which is no hint at all. Nothing in the log says so. Stations that
 * list everything they accept, as the stonecutter does, need no teaching and are not asked about here.
 */
class RecipeUnlocksCheck : FunSpec({

    val recipeDirectories = listOf(
        File("src/main/resources/data/agesandtheart/recipe"),
        File("../fabric/src/main/resources/data/agesandtheart/recipe"),
        File("../neoforge/src/main/resources/data/agesandtheart/recipe"),
    )
    val advancements = File("src/main/resources/data/agesandtheart/advancement")

    /** The types a recipe book draws: the crafting table's, the furnaces', and the compounder's. */
    val shownInABook = setOf(
        "minecraft:crafting_shaped",
        "minecraft:crafting_shapeless",
        "agesandtheart:crafting_shapeless_into_one_bottle",
        "minecraft:smelting",
        "minecraft:blasting",
        "minecraft:smoking",
        "minecraft:campfire_cooking",
        "agesandtheart:compounding",
    )

    fun jsonIn(directory: File): Sequence<Pair<File, JsonObject>> =
        directory.walkTopDown().filter { it.extension == "json" }.map { it to JsonParser.parseString(it.readText()).asJsonObject }

    test("every recipe shown in a book is taught by an advancement") {
        check(recipeDirectories.all(File::isDirectory) && advancements.isDirectory) { "Missing a recipe or advancement directory" }

        val taught = jsonIn(advancements).flatMap { (_, advancement) ->
            advancement.getAsJsonObject("rewards")?.getAsJsonArray("recipes")?.map { it.asString }.orEmpty()
        }.toSet()

        val untaught = recipeDirectories.flatMap { directory ->
            jsonIn(directory)
                .filter { (_, recipe) -> recipe.get("type").asString in shownInABook }
                .map { (file, _) -> "agesandtheart:" + file.relativeTo(directory).invariantSeparatorsPath.removeSuffix(".json") }
                .toList()
        }.toSet() - taught

        check(untaught.isEmpty()) { "Recipes no advancement teaches: ${untaught.sorted()}" }
    }
})
