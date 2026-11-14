package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Drawn
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The landform words, and the words of the sky's bodies and its turning, as we ship them (`notes/the-word-rework.md`). */
@Tags(NEEDS_REGISTRIES)
class LandformsCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }
    val seeds = 0L..<60L

    fun drawn(row: String, seed: Long = 0L): Drawn {
        val reading = Reader.read(words, row.split(" "), membership, seed)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, seed)
    }

    fun effects(row: String, seed: Long = 0L): List<Effect> = drawn(row, seed).landed.flatMap { it.effects }

    fun set(row: String, part: Aspect, parameter: String, seed: Long = 0L): List<String> = effects(row, seed)
        .mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    val craters = Effect.Chooses(Aspect.FEATURES, "agesandtheart:craters")

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("no landform stands alone: every word offering a member of the land is said of something, and alone it dangles") {
        val standing = words.all.filter { word ->
            word.senses.any { sense -> sense.endsAPhrase && sense.offers.any { it.concept == CoreConcepts.MEMBER && membership.isMemberOf(Aspect.TERRAIN, (it.value as? Value.Text)?.text.orEmpty()) } }
        }
        check(standing.isEmpty()) { "landforms that stand alone: ${standing.map { it.name }}" }
        for (landform in listOf("windswept", "cratered", "pillared", "pyramidal")) {
            check(drawn("age $landform").dangling.any { it.written == landform }) { "$landform stood alone" }
        }
    }

    test("`cratered` is the landform said of the land, the craters said of the features, and of the Age both, the craters half the time") {
        check(Effect.Chooses(Aspect.TERRAIN, "cratered") in effects("cratered landmass age") && craters !in effects("cratered landmass age"))
        check(craters in effects("cratered features age") && Effect.Chooses(Aspect.TERRAIN, "cratered") !in effects("cratered features age")) {
            "cratered features: ${effects("cratered features age")}"
        }
        val withCraters = seeds.count { craters in effects("cratered age", it) }
        check(seeds.all { Effect.Chooses(Aspect.TERRAIN, "cratered") in effects("cratered age", it) }) { "cratered age lost its landform" }
        check(withCraters in 15..45) { "cratered age brought craters at $withCraters seeds of ${seeds.count()}" }
    }

    test("a shape word is the geometric landmass built of its formation, laid out as `gridded` says") {
        val pyramids = effects("gridded pyramidal landmass age")
        check(Effect.Chooses(Aspect.TERRAIN, "geometric") in pyramids) { "pyramidal landmass: $pyramids" }
        check(set("gridded pyramidal landmass age", Aspect.TERRAIN, "shape") == listOf("agesandtheart:pyramids")) { "the pyramids were not its shape" }
        check(set("gridded pyramidal landmass age", Aspect.TERRAIN, "arrangement") == listOf("grid")) { "the pyramids were not in a grid" }
        check(drawn("pillared landmass age").dangling.isEmpty()) { "a derived landform did not read" }
    }

    test("`torn`, `floating`, `shifting` and `unfading`") {
        check(set("torn sky age", Aspect.SKY, "torn") == listOf("true")) { "torn sky was not torn" }
        check(effects("floating landmass age").any { it is Effect.Restricts && "floating" in it.bars }) { "floating landmass did not float" }
        check(set("shifting sky age", Aspect.SKY, "shifting") == listOf("true")) { "shifting sky did not wander" }
        check(set("shifting weather age", Aspect.AIR, "wind") == listOf("0.2..0.5")) { "shifting weather brought no wind" }
        check(set("unfading stars age", Aspect.STARS, "fades") == listOf("never")) { "unfading stars faded" }
    }

    test("`tidal moon` is a tide and a larger moon; `eclipsing moon` crosses the sun; neither is said of the whole Age") {
        check(set("tidal moon age", Aspect.MOON, "pull") == listOf("0..1") && set("tidal moon age", Aspect.MOON, "size") == listOf("0.1..0.3")) {
            "tidal moon: ${effects("tidal moon age")}"
        }
        check(seeds.none { set("tidal age", Aspect.MOON, "pull", it).isNotEmpty() }) { "tidal age moved the tide" }
        check(effects("tidal sea age").any { it is Effect.Restricts && "watery" in it.bars }) { "tidal sea was not of water" }
        check(set("eclipsing moon age", Aspect.MOON, "path") == listOf("eclipsing")) { "eclipsing moon did not cross the sun" }
    }
})
