package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.RUNS_OCCASIONALLY_TAG
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.language.WordFormat
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.Pairings.Verdict
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The pairings tool files a phrase as it should, held against made-up words whose answers are known: a word choosing
 * what its part already holds is hollow, a word offered only to the clouds says nothing of the sea, and its mirror is
 * named where the mirror means something there. And, now and then, no authored word is hollow before any part of the world.
 */
@Tags(NEEDS_REGISTRIES)
class PairingsCheck : FunSpec({

    fun made(name: String, json: String): Word =
        WordFormat.mapCodec(name.location()).codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json))
            .getOrThrow { IllegalArgumentException("'$name' does not read: $it") }

    fun pairingOf(word: Word, noun: String): Pairings.Pairing =
        Pairings(vocabulary, standIn = word).phrase(word, noun) ?: error("'$noun' is no word")

    test("a word choosing what its part already holds is hollow") {
        val restating = made("pairing_fixture_restating", """{"meanings":[{"chooses":{"rock":"caves"},"must_always_apply":true}]}""")
        val pairing = pairingOf(restating, "rock")
        check(pairing.verdict == Verdict.HOLLOW) { "choosing the rock it had came out ${pairing.verdict.said}: ${pairing.said}" }
    }

    test("a word raising the land and the clouds says nothing of the sea, and its mirror is named") {
        val raising = made(
            "pairing_fixture_raising",
            """{"meanings":[{"sets":{"ceiling":"0.85..1.0"},"must_always_apply":true},{"sets":{"relief":"0.5..1.0"},"must_always_apply":true},""" +
                """{"sets":{"clouds.ceiling":"0.85..1.0"},"must_always_apply":true,"offered_to":["clouds"]}]}""",
        )
        val beforeTheClouds = pairingOf(raising, "clouds")
        check(beforeTheClouds.verdict == Verdict.MEANS) { "said of the clouds, it came out ${beforeTheClouds.verdict.said}" }
        val beforeTheSea = pairingOf(raising, "sea")
        check(beforeTheSea.verdict == Verdict.SILENT || beforeTheSea.verdict == Verdict.STRAYS) {
            "said of nothing on the sea, it came out ${beforeTheSea.verdict.said}: ${beforeTheSea.said}"
        }
        check(beforeTheSea.hints.any { it.startsWith("low sea (its mirror)") }) { "low's meaning on the sea went unnamed: ${beforeTheSea.hints}" }
    }

    test("no authored word is hollow before any part of the world").config(tags = setOf(RUNS_OCCASIONALLY_TAG)) {
        val hollow = Pairings(vocabulary).hollowBeforeTheParts()
        check(hollow.isEmpty()) {
            "${hollow.size} hollow phrases:\n  ${hollow.sortedBy { it.phrase }.joinToString("\n  ") { "${it.phrase} — ${it.said}" }}"
        }
    }
})
