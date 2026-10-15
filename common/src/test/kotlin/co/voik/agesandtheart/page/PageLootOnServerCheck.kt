package co.voik.agesandtheart.page

import co.voik.agesandtheart.server.DrivenServer
import co.voik.agesandtheart.server.NEEDS_SERVER
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Every container pages are put in holds writing, opened through vanilla's own table on a real server — which
 * is the injection reaching it on this loader, not just being listed. How often is tuning, and not checked.
 */
@Tags(NEEDS_SERVER)
class PageLootOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    test("every page container holds writing, several pages at a time, early ones now and then a notebook") {
        val opened = server.ask("loot", "$TIMES").getAsJsonArray("containers").map { it.asJsonObject }
        check(opened.size == PageLoot.TARGETS.count { it.injected in PageLoot.PAGE_TABLES }) {
            "opened ${opened.size} containers"
        }
        for (container in opened) {
            val table = container["table"].asString
            check(container["found"].asInt > 0) { "$table never held writing in $TIMES openings" }
            val perFind = container["pages"].asDouble / container["found"].asDouble
            check(perFind > 1.5) { "$table held only $perFind pages a time" }
        }
        val early = opened.filter { container -> PageLoot.TARGETS.any { "${it.table.identifier()}" == container["table"].asString && it.injected == PageLoot.EARLY_PAGES } }
        check(early.sumOf { it["notebooks"].asInt } > 0) { "no early container ever held a notebook in place of a page" }
    }
}) {
    private companion object {
        const val TIMES = 1_000
    }
}
