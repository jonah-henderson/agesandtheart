package co.voik.agesandtheart.content

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec

/**
 * The survey reports' text is there to read. Whether what they teach writes a D'ni city needs the whole
 * vocabulary, and so a server: see `CavernRuinsOnServerCheck`.
 */
class SurveyReportCheck : FunSpec({

    test("every page of every report has English") {
        val english = shippedEnglish()
        val missing = SurveyReport.entries.flatMap { report ->
            (0..<report.pageCount).map(report::pageKey).filterNot(english::has)
        }
        check(missing.isEmpty()) { "these pages have no text: $missing" }
    }

    test("every report names the Age its book leads to") {
        val english = shippedEnglish()
        val unnamed = SurveyReport.entries.filterNot { report ->
            english.get(report.ageNameKey).asString in english.get(report.pageKey(0)).asString
        }
        check(unnamed.isEmpty()) { "these reports do not name their Age on the first page: $unnamed" }
    }

    test("no report has a page past the last it counts") {
        val english = shippedEnglish()
        val uncounted = SurveyReport.entries.map { it.pageKey(it.pageCount) }.filter(english::has)
        check(uncounted.isEmpty()) { "these pages are written and never shown: $uncounted" }
    }
}) {
    private companion object {
        fun shippedEnglish() =
            SurveyReportCheck::class.java.getResourceAsStream("/assets/agesandtheart/lang/en_us.json")
                .let { requireNotNull(it) { "no English shipped" } }
                .use { JsonParser.parseReader(it.reader()).asJsonObject }
    }
}
