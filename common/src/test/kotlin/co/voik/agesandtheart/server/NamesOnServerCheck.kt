package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** That nothing of ours shows a player a raw translation key where its name should be (`/age unnamed`). */
@Tags(NEEDS_SERVER)
class NamesOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    test("every item, block, entity and effect of ours has an English name") {
        val unnamed = server.ask("unnamed")["unnamed"].asJsonArray.map { it.asString }
        check(unnamed.isEmpty()) { "${unnamed.size} have no name in en_us.json:\n" + unnamed.joinToString("\n") { "  $it" } }
    }
})
