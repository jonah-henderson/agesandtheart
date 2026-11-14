package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.words.ShippedWords
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.words.Verdict.Standing
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** What Scrivener says of a word being written: loading's refusals for that word, and its own warnings and nudges. */
@Tags(NEEDS_REGISTRIES)
class VerdictCheck : FunSpec({

    val corpus by lazy { ShippedWords.corpus }

    fun candidate(name: String, json: String) = Candidate(ShippedWords.read(Word.mapCodec(name.location()).codec(), json))

    fun findings(name: String, json: String, quarrels: Boolean = false) = Verdict.on(candidate(name, json), corpus, quarrels)

    fun says(found: List<Verdict.Finding>, standing: Standing, fragment: String) = found.any { it.standing == standing && fragment in it.says }

    test("what loading refuses is refused for the word being written, and only for it") {
        val unknownConcept = findings("smelly", """{ "offers": [ { "concept": "smell", "value": "sweet" } ] }""")
        check(says(unknownConcept, Standing.REFUSED, "'smell'")) { "an unknown concept: $unknownConcept" }
        check(unknownConcept.filter { it.standing == Standing.REFUSED }.all { it.heldBy == "Words.of" }) { "held by: $unknownConcept" }
        val nowhere = findings("loud", """{ "terminal": true, "part": "sky", "means": [ { "sets": { "loudness": "0.5..1.0" } } ] }""")
        check(says(nowhere, Standing.REFUSED, "'sky.loudness' is no setting")) { "a setting nowhere: $nowhere" }
        val sound = findings("tinted", """{ "offers": [ { "concept": "colour", "value": "red" } ] }""")
        check(Verdict.refusals(sound).isEmpty()) { "a sound word was refused: $sound" }
    }

    test("an offer nothing takes, or one that can never apply, is warned of") {
        val untaken = findings("ageing", """{ "offers": [ { "concept": "colour", "value": "red", "for": "age" } ] }""")
        check(says(untaken, Standing.WARNED, "nothing takes its colour 'red' for the Age")) { "an untaken offer: $untaken" }
        val never = findings("never", """{ "offers": [ { "concept": "colour", "value": "red", "chance": 0.0, "chance_from_whole_age": 0.0 } ] }""")
        check(says(never, Standing.WARNED, "can never apply")) { "an offer with no chance: $never" }
    }

    test("a reading one concept reaches, a band of the whole axis and a fence naming the part said of are nudged") {
        val reading = findings("icy", """{ "offers": [ { "for": "rock", "means": [ { "sets": { "landmass.stone": "#frozen" } } ] } ] }""")
        check(says(reading, Standing.NUDGED, "takes as block")) { "a reading the block concept reaches: $reading" }
        val whole = findings("tepid", """{ "offers": [ { "concept": "temperature", "value": "-1.0..1.0" } ] }""")
        check(says(whole, Standing.NUDGED, "whole axis")) { "a band excluding nothing: $whole" }
        val fenced = findings("glowing", """{ "offers": [ { "for": "aurora", "means": [ { "sets": { "aurora.warmth": "0.5..1.0", "aurora.size": "0.5..1.0" } } ] } ] }""")
        check(says(fenced, Standing.NUDGED, "'aurora.warmth' does not need its part named")) { "a fence naming its own part: $fenced" }
        check(fenced.none { "turns only what" in it.says }) { "a reading through two concepts was taken for one: $fenced" }
        val redirected = findings("hotter", """{ "offers": [ { "for": "sea", "means": [ { "sets": { "climate.temperature": ">0.4" } } ] } ] }""")
        check(redirected.none { "does not need its part named" in it.says }) { "a fence carrying a reading to another part was nudged: $redirected" }
    }

    test("a word is noted with the words of ours it is charged beside") {
        val hot = WordFile.read("hot").getOrThrow()
        val found = Verdict.on(hot, corpus, quarrels = true).filter { it.standing == Standing.NOTED }
        check(found.any { "cold" in it.because.orEmpty().split(" ") }) { "hot was not charged beside cold: $found" }
    }
})
