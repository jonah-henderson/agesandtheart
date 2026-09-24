package co.voik.agesandtheart

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Whether every shipped loot table and loot modifier is written in 26.3's shape.
 *
 * 26.3 renamed `functions` to `modifier`, `conditions` to `condition` (one value, so several become
 * `all_of`), and the `function` and `condition` keys inside each one to `type`. The old keys are **ignored
 * without a word**: the table still loads, and
 * every function and condition in it is simply gone. That is how pages came out of chests with no word on
 * them, and how every NeoForge loot modifier fired on every table in the game.
 */
class LootShapeCheck : FunSpec({

    val directories = listOf(
        File("src/main/resources/data/agesandtheart/loot_table"),
        File("../neoforge/src/main/resources/data/agesandtheart/loot_modifiers"),
    )

    val retiredKeys = setOf("functions", "conditions", "function")

    fun retiredKeysIn(element: JsonElement, path: String): List<String> = when {
        element.isJsonObject -> element.asJsonObject.entrySet().flatMap { (key, value) ->
            val here = if (key in retiredKeys) listOf("$path/$key") else emptyList()
            here + retiredKeysIn(value, "$path/$key")
        }
        element.isJsonArray -> element.asJsonArray.flatMapIndexed { index, child -> retiredKeysIn(child, "$path[$index]") }
        else -> emptyList()
    }

    test("no loot file uses a key 26.3 ignores") {
        check(directories.all(File::isDirectory)) { "Missing one of ${directories.map { it.absolutePath }}" }

        val offenders = directories.flatMap { directory ->
            directory.walkTopDown().filter { it.extension == "json" }.flatMap { file ->
                val root = JsonParser.parseString(file.readText())
                retiredKeysIn(root, "").map { "${file.relativeTo(directory).invariantSeparatorsPath}: $it" }
            }
        }
        check(offenders.isEmpty()) { "Written in the pre-26.3 loot shape:\n  " + offenders.joinToString("\n  ") }
    }
})
