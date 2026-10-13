package co.voik.agesandtheart.page

import co.voik.agesandtheart.server.DrivenServer
import co.voik.agesandtheart.server.NEEDS_SERVER
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Every container pages are put in holds one about as often as it was given, opened through vanilla's own
 * table on a real server — which is the injection reaching it on this loader, not just being listed.
 */
@Tags(NEEDS_SERVER)
class PageLootOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    test("every page container holds pages at its chance, one or more at a time") {
        val opened = server.ask("loot", "$TIMES").getAsJsonArray("containers").map { it.asJsonObject }
        check(opened.size == PageLoot.TARGETS.count { it.injected in PageLoot.PAGE_TABLES }) {
            "opened ${opened.size} containers"
        }
        for (container in opened) {
            val table = container["table"].asString
            val chance = container["chance"].asDouble
            val share = container["found"].asDouble / TIMES
            val spread = SIGMAS * sqrt(chance * (1 - chance) / TIMES)
            check(abs(share - chance) <= spread) { "$table held a page ${share * 100}% of the time, given ${chance * 100}%" }
            val perFind = container["pages"].asDouble / container["found"].asDouble
            check(perFind > 1.0) { "$table never held more than one page at a time" }
        }
    }
}) {
    private companion object {
        const val TIMES = 1_000
        /** Wide enough that a fair table fails about once in sixteen thousand runs. */
        const val SIGMAS = 4.0
    }
}
