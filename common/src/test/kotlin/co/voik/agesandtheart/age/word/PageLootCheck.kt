package co.voik.agesandtheart.age.word

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Keeps the two halves of page loot in step.
 *
 * Fabric injects pools from [PageLoot.TARGETS] in code; NeoForge does the same with `neoforge:add_table`
 * global loot modifiers, which are data and cannot read a Kotlin list. So the same decision is written
 * twice, and this is what stops the copies drifting.
 */
class PageLootCheck : FunSpec({

    val modifierDirectory = File("../neoforge/src/main/resources/data/agesandtheart/loot_modifiers")

    /** One modifier per target, matching on both table and chance. */
    test("the NeoForge loot modifiers match the target list") {
        check(modifierDirectory.isDirectory) { "No loot modifiers at ${modifierDirectory.absolutePath}" }

        val fromData = modifierDirectory.listFiles { file -> file.extension == "json" }.orEmpty()
            .map { file ->
                val root = JsonParser.parseString(file.readText()).asJsonObject
                val conditions = root.getAsJsonArray("conditions").map { it.asJsonObject }
                val table = conditions.first { it["condition"].asString == "neoforge:loot_table_id" }
                val chance = conditions.first { it["condition"].asString == "minecraft:random_chance" }
                Triple(
                    table["loot_table_id"].asString,
                    chance["chance"].asFloat,
                    root["table"].asString,
                )
            }.toSet()

        val fromCode = PageLoot.TARGETS
            .map { Triple(it.table.identifier().toString(), it.chance, it.injected.identifier().toString()) }
            .toSet()

        check(fromData == fromCode) {
            val onlyInCode = fromCode - fromData
            val onlyInData = fromData - fromCode
            "Page loot targets have drifted.\n  only in PageLoot.TARGETS: $onlyInCode\n  only in loot_modifiers: $onlyInData"
        }
    }

    /** Every modifier adds one of ours, never something that happens to parse. */
    test("every modifier adds a shipped table") {
        val ours = setOf(PageLoot.PAGES, PageLoot.NOTEBOOK, PageLoot.LINKING_BOOK, PageLoot.DESCRIPTIVE_BOOK)
            .map { it.identifier().toString() }
        val tables = modifierDirectory.listFiles { file -> file.extension == "json" }.orEmpty()
            .map { JsonParser.parseString(it.readText()).asJsonObject["table"].asString }
        check(tables.all { it in ours }) { "A modifier adds something that is not ours: $tables" }
    }

    /**
     * A page is a word; a notebook is a hoard and a linking book is a way home. Both outrank a page.
     *
     * **Found books are exempt while they have nowhere to live.** They are scattered everywhere and
     * generously on purpose, because the grammar cannot be walked if the only thing that teaches it is
     * rare (`PageLoot.DESCRIPTIVE_BOOK`). When the library structure gives them a home the exemption goes
     * with the scatter — and this check is what will say so, by failing.
     */
    test("notebooks and linking books are rarer than pages wherever both appear") {
        val pages = PageLoot.TARGETS.filter { it.injected == PageLoot.PAGES }.associate { it.table to it.chance }
        val tooCommon = PageLoot.TARGETS
            .filter { it.injected != PageLoot.PAGES && it.injected != PageLoot.DESCRIPTIVE_BOOK }
            .filter { rarer -> pages[rarer.table]?.let { rarer.chance >= it } ?: false }
        check(tooCommon.isEmpty()) {
            "These are no rarer than pages: ${tooCommon.map { it.table.identifier() to it.injected.identifier() }}"
        }
    }
})
