package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A found book comes covered: a base colour and, for an advanced or unstable book, two or three layers of a loom's own
 * patterns. Only a server holds the pattern registry and its tags, so only a server can say the cover is drawn at all.
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

    test("a basic book comes covered, plain or under one layer") {
        val books = (0..<BOOKS).map { foundBook("basic", it) }
        check(books.all { "minecraft:base_color" in it }) { "a basic book came uncovered: ${books.first { "minecraft:base_color" !in it }}" }
        check(books.all { layersIn(it) <= 1 }) { "a basic book wore more than one layer: ${books.first { layersIn(it) > 1 }}" }
        check(books.all { "agesandtheart:cover_layout" in it }) { "a basic book was given no layout" }
    }

    test("an advanced or unstable book comes under two or three layers") {
        for (kind in listOf("advanced", "unstable")) {
            val books = (0..<BOOKS).map { foundBook(kind, it) }
            check(books.all { layersIn(it) in 2..3 }) { "an $kind book wore ${books.map(::layersIn)} layers" }
        }
    }
})

private const val CHECK_Y = 200
private const val BOOKS = 6
