package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Whether anything ever says what an Age looks like.
 *
 * The regression this guards is **quiet by construction**. Deciding a look is a listener on the library's
 * open event, and a listener that throws is caught and warned rather than taking the level down with it — so
 * a broken pipeline leaves an Age that opens, generates and accepts visitors, and is simply the wrong colour.
 * Nothing fails.
 *
 * `/age sky` reports what the server *told* clients rather than recomputing it from the recipe, which is what
 * makes the question askable over RCON at all.
 */
@Tags(NEEDS_SERVER)
class AppearanceCheck : FunSpec({
    val server = DrivenServer.shared

    test("an Age is described as it is written") {
        // The Spire's sky, which its islands bring, because it is written down rather than resolved: its two
        // cloud decks are content no default could produce, so a look that arrived empty fails as loudly as
        // one that never arrived.
        server.run("age compose lookage 11 landmass=spire_islands sea=minecraft:water rock=caves")

        val report = server.run("age sky lookage")

        check("Nothing has said" !in report) {
            "Nothing described 'lookage', so every client would draw it with vanilla's sky:\n$report"
        }
        check("deck" in report) {
            "'lookage' was written with the Spire's sky and described without its cloud decks:\n$report"
        }
    }
})
