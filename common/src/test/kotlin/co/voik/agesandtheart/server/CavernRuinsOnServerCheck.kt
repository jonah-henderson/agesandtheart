package co.voik.agesandtheart.server

import co.voik.agesandtheart.content.SurveyReport
import com.google.gson.JsonObject
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The survey reports are the D'ni city's clues (design §7.6), so the words they teach, written out, have
 * to make an Age with a city in it — and each Age a report turned down, being a found Age, has to earn nothing.
 *
 * On a server because `algae` is a page minted from our own placed feature, which the offline corpus
 * does not have.
 */
@Tags(NEEDS_SERVER)
class CavernRuinsOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun write(name: String, seed: Long, sentence: String): JsonObject {
        val written = server.ask("write", "$name $seed $sentence")
        check(written.getAsJsonArray("moved").isEmpty) {
            "'$sentence' did not read as laid, and moved ${written.getAsJsonArray("moved")} to the Age"
        }
        return written
    }

    fun hasACity(name: String, sentence: String) = write(name, 7, sentence).get("cavernRuins").asBoolean

    test("the reports' words, written out, make an Age with a D'ni city") {
        val sentence = "age subterranean large chambered algae"
        val taught = SurveyReport.entries.flatMap { it.teaches }.map { it.path }
        val untaught = taught.filterNot { it in sentence.split(' ') }
        check(untaught.isEmpty()) { "the sentence leaves out words a report teaches: $untaught" }
        check(hasACity("ruinsreports", sentence)) { "no city for '$sentence'" }
    }

    test("an unsized chamber has no city") {
        check(!hasACity("ruinsunsized", "age subterranean chambered algae")) {
            "an unsized chamber qualified"
        }
    }

    test("no Age a report turned down earns anything") {
        val earning = SurveyReport.entries.associateWith { report ->
            val written = write(report.path, report.ageSeed, report.sentence.joinToString(" "))
            written.getAsJsonArray("rewards").map { it.asString }
        }.filterValues { it.isNotEmpty() }
        check(earning.isEmpty()) { "these surveyed Ages earn a reward after all: $earning" }
    }

    test("a report is found with its Age's book") {
        server.run("age write surveyloot 7 age gentle")
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
