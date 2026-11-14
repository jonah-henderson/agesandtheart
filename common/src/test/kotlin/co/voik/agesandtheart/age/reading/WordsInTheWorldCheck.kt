package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.words.ShippedWords
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** Every word we ship is one the world can honour: what it could land is somewhere, takes what it is given, and exists. */
@Tags(NEEDS_REGISTRIES)
class WordsInTheWorldCheck : FunSpec({

    val vocabulary by lazy { ShippedCorpus.vocabulary }
    val membership by lazy { WorldMembership(vocabulary) }

    test("every word we ship, written and derived, is one the world can honour") {
        val words = ShippedWords.with()
        val problems = WordsInTheWorld(words, vocabulary, membership).problems
        check(problems.isEmpty()) { "${problems.size} problems:\n${problems.joinToString("\n")}" }
    }

    test("what a word could land is refused where the world has no such setting, value, member, tag or block") {
        val faulty = mapOf(
            "nowhere" to """{ "terminal": true, "part": "sky", "means": [ { "sets": { "loudness": "0.5..1.0" } } ] }""",
            "untaken" to """{ "terminal": true, "part": "sun", "means": [ { "sets": { "path": "sideways" } } ] }""",
            "unbendable" to """{ "terminal": true, "part": "sun", "means": [ { "bends": { "path": "polar" } } ] }""",
            "unknown_member" to """{ "terminal": true, "part": "landmass", "means": [ { "chooses": { "landmass": "teacup" } } ] }""",
            "untagged" to """{ "offers": [ { "concept": "lean", "value": { "#glittery": 1.0 } } ] }""",
            "unmade" to """{ "offers": [ { "concept": "block", "value": "#glittery" } ] }""",
            "misread" to """{ "offers": [ { "concept": "colour", "value": "teal" } ] }""",
            "misaimed" to """{ "terminal": true, "part": "sky", "accepts": [ { "concept": "colour", "becomes": [ { "sets": "loudness" } ] } ] }""",
        )
        val words = ShippedWords.with(faulty)
        val world = WordsInTheWorld(words, vocabulary, membership)
        fun said(name: String) = world.problemsOf(words.word(name) ?: error("no $name")).map { it.says }
        check(said("nowhere").any { "'sky.loudness' is no setting" in it }) { "a setting nowhere: ${said("nowhere")}" }
        check(said("untaken").any { "does not take 'sideways'" in it }) { "a value not taken: ${said("untaken")}" }
        check(said("unbendable").any { "no range to bend" in it }) { "a setting with no range bent: ${said("unbendable")}" }
        check(said("unknown_member").any { "has no 'teacup'" in it }) { "an unknown member: ${said("unknown_member")}" }
        check(said("untagged").any { "#glittery, which nothing carries" in it }) { "an unknown tag: ${said("untagged")}" }
        check(said("unmade").any { "#glittery" in it }) { "a material nothing answers: ${said("unmade")}" }
        check(said("misread").any { "does not take 'teal'" in it }) { "a colour some part cannot take: ${said("misread")}" }
        check(said("misaimed").any { "'sky.loudness' is no setting" in it }) { "an acceptance turning nothing: ${said("misaimed")}" }
    }
})
