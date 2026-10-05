package co.voik.agesandtheart.server

import co.voik.agesandtheart.content.SurveyReport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The survey reports are the D'ni city's clues (design §7.6), so the words they teach, written out, have
 * to make an Age with a city in it — and each Age a report turned down has to be missing what its report
 * says it was missing.
 *
 * On a server because `algae` is a page minted from our own placed feature, which the offline corpus
 * does not have.
 */
@Tags(NEEDS_SERVER)
class CavernRuinsOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun hasACity(name: String, sentence: String): Boolean {
        val written = server.ask("write", "$name 7 $sentence")
        check(written.getAsJsonArray("supplied").isEmpty) {
            "'$sentence' did not parse and was filled in: ${written.getAsJsonArray("supplied")}"
        }
        return written.get("cavernRuins").asBoolean
    }

    test("the reports' words, written out, make an Age with a D'ni city") {
        val sentence = "age subterranean landmass colossal chambered underground algae features"
        val taught = SurveyReport.entries.flatMap { it.teaches }.map { it.path }
        val untaught = taught.filterNot { it in sentence.split(' ') }
        check(untaught.isEmpty()) { "the sentence leaves out words a report teaches: $untaught" }
        check(hasACity("ruinsreports", sentence)) { "no city for '$sentence'" }
    }

    test("an unsized chamber has no city") {
        check(!hasACity("ruinsunsized", "age subterranean landmass chambered underground algae features")) {
            "an unsized chamber qualified"
        }
    }

    test("no Age a report turned down has a city") {
        val qualified = SurveyReport.entries.filter { hasACity(it.path, it.sentence.joinToString(" ")) }
        check(qualified.isEmpty()) { "these surveyed Ages have a city after all: $qualified" }
    }

    /** Each lacks exactly what its report says, so writing that one thing in is the whole of the fix. */
    test("the lightless and cramped Ages are one fix from a city") {
        val lit = SurveyReport.LIGHTLESS_AGE.sentence.joinToString(" ") + " algae features"
        check(hasACity("gomurlit", lit)) { "'$lit' has no city" }
        val chambered = SurveyReport.CRAMPED_AGE.sentence.joinToString(" ").replace("fissured", "chambered")
        check(hasACity("reshanchambered", chambered)) { "'$chambered' has no city" }
    }

    test("a report is found with its Age's book") {
        server.run("age write surveyloot 7 age gentle landmass")
        server.run("execute in agesandtheart:surveyloot run forceload add -16 -16 16 16")
        // Several rolls, so each of the three reports is likely to have been drawn.
        val rolls = List(ROLLS) {
            server.run("execute in agesandtheart:surveyloot run loot spawn 0 150 0 loot agesandtheart:inject/survey_report")
        }
        val alone = rolls.filterNot { it.startsWith("Dropped 2 ") }
        check(alone.isEmpty()) { "a roll did not drop the report and the book together: $alone" }
    }
}) {
    private companion object {
        const val ROLLS = 9
    }
}
