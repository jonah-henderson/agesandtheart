package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A found book comes covered or dyed: a cover a base colour and, for an advanced or unstable book, two or three layers
 * of a loom's own patterns. Only a server holds the pattern registry and its tags, so only a server can say the cover is
 * drawn at all.
 */
@Tags(NEEDS_SERVER)
class FoundCoverOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    /** What a found book of [kind] written in a chest carries, as `data get` says it. */
    fun foundBook(kind: String, slot: Int): String {
        server.run("forceload add 0 0")
        server.run("setblock $slot $CHECK_Y 0 minecraft:chest")
        server.run("item replace block $slot $CHECK_Y 0 container.0 with agesandtheart:descriptive_book")
        server.run("""item modify block $slot $CHECK_Y 0 container.0 {type:"agesandtheart:write_found_book",kind:"$kind"}""")
        return server.run("data get block $slot $CHECK_Y 0 Items[0].components")
    }

    fun layersIn(components: String): Int = Regex(""""?pattern"?:""").findAll(components).count()

    fun isCovered(components: String) = "minecraft:base_color" in components
    fun isDyed(components: String) = "minecraft:dyed_color" in components

    test("a found book is covered or dyed, never both, and covers wear as many layers as their kind") {
        for ((kind, layers) in listOf("basic" to 0..1, "advanced" to 2..3, "unstable" to 2..3)) {
            val books = (0..<BOOKS).map { foundBook(kind, it) }
            check(books.none { isCovered(it) && isDyed(it) }) { "a $kind book was both covered and dyed" }
            check(books.any(::isCovered) && books.any(::isDyed)) { "of $BOOKS $kind books, not one was covered and one dyed" }
            val covers = books.filter(::isCovered)
            check(covers.all { layersIn(it) in layers }) { "a covered $kind book wore ${covers.map(::layersIn)} layers" }
            check(covers.all { "agesandtheart:cover_layout" in it }) { "a covered $kind book was given no layout" }
        }
    }
})

private const val CHECK_Y = 200
private const val BOOKS = 40
