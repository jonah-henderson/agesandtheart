package co.voik.agesandtheart.page

import co.voik.agesandtheart.server.DrivenServer
import co.voik.agesandtheart.server.NEEDS_SERVER
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * No chest hands out a page or notebook naming a word found pages are kept from — a progression block, a rare
 * material, a withheld word. Asked on a server because `cannot_appear_in_loot` is a tag, and offline has none.
 */
@Tags(NEEDS_SERVER)
class FencedPagesOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    test("no chest table puts a fenced word on a page or in a notebook") {
        val answer = server.ask("fenced", "$TIMES")
        check(answer["tables"].asInt > 0) { "no chest tables were opened" }
        // Most of the corpus turns up somewhere, or the fence was never really asked.
        check(answer["words"].asInt > ENOUGH_WORDS) { "only ${answer["words"].asInt} different words were found" }
        check(answer["fence"].asInt >= FEWEST_FENCED) { "only ${answer["fence"].asInt} things are tagged cannot_appear_in_loot" }
        val leaks = answer.getAsJsonArray("leaks")?.map { it.asJsonObject["word"].asString }.orEmpty()
        check(leaks.isEmpty()) { "tagged cannot_appear_in_loot, yet a found page may carry: ${leaks.joinToString(", ")}" }
        val fenced = answer.getAsJsonArray("fenced")?.map { it.asJsonObject }.orEmpty()
        check(fenced.isEmpty()) {
            "fenced words found: " + fenced.joinToString("; ") { "${it["table"].asString} → ${it["words"].asString}" }
        }
    }
}) {
    private companion object {
        const val TIMES = 100
        const val ENOUGH_WORDS = 500
        const val FEWEST_FENCED = 30
    }
}
