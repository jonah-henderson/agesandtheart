package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import kotlin.io.path.exists

/**
 * The temptation table (`art/temptations.json`) names only things that exist, and leaves vanilla's own taming alone.
 * A misspelt item is a creature nobody can ever tame, and nothing in a world would say so.
 */
@Tags(NEEDS_REGISTRIES)
class TemptationsCheck : FunSpec({

    val table by lazy { Temptations.load(MinecraftRegistries.shippedData()) }

    fun isOurs(id: Identifier) = id.namespace == Constants.MOD_ID

    fun ourFileExists(path: String) = MinecraftRegistries.resourceRoot().resolve(path).exists()

    fun vanillaFileExists(path: String) = Temptations::class.java.getResource("/$path") != null

    fun itemExists(id: Identifier): Boolean =
        if (isOurs(id)) ourFileExists("assets/${id.namespace}/items/${id.path}.json") else BuiltInRegistries.ITEM.containsKey(id)

    fun tagExists(id: Identifier): Boolean {
        val path = "data/${id.namespace}/tags/item/${id.path}.json"
        return if (isOurs(id)) ourFileExists(path) else vanillaFileExists(path)
    }

    test("the table loads, and names creatures") {
        check(table.byKind.size > 50) { "the table holds only ${table.byKind.size} creatures; did it fail to load?" }
    }

    test("every creature, item, tag and potion it names exists") {
        val missing = buildList {
            for ((kind, wants) in table.byKind) {
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(kind)) add("creature $kind")
                for (want in wants) {
                    val isATag = want.item.startsWith(Temptations.Want.TAG_MARK)
                    val id = Identifier.parse(want.item.removePrefix(Temptations.Want.TAG_MARK))
                    val exists = if (isATag) tagExists(id) else itemExists(id)
                    if (!exists) add("${if (isATag) "tag" else "item"} ${want.item}, wanted by $kind")
                    for (potion in want.potions) {
                        if (!BuiltInRegistries.POTION.containsKey(potion)) add("potion $potion, wanted by $kind")
                    }
                }
            }
        }
        check(missing.isEmpty()) { "the temptation table names what does not exist: $missing" }
    }

    test("a creature vanilla tames is left to vanilla") {
        val tamedByVanilla = listOf(
            "wolf", "cat", "parrot", "horse", "donkey", "mule", "llama", "trader_llama", "camel", "camel_husk",
            "skeleton_horse", "zombie_horse", "nautilus", "zombie_nautilus", "ocelot", "fox", "allay", "happy_ghast",
        ).map(Identifier::withDefaultNamespace)
        val listed = tamedByVanilla.filter { it in table.byKind }
        check(listed.isEmpty()) { "the table would tame what vanilla tames already: $listed" }
    }
})
