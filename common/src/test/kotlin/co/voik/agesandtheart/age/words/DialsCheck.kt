package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The sizes, amounts, frequencies, paces, heights, strengths and winds we ship (`notes/the-word-rework.md`, step 6). */
@Tags(NEEDS_REGISTRIES)
class DialsCheck : FunSpec({

    /** `north` is the directions batch's; this one is enough to say where a wind blows from. */
    val words by lazy { ShippedWords.with(mapOf("north" to """{ "offers": [ { "concept": "direction", "value": "north" } ] }""")) }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }
    val seeds = 0L..<100L

    fun effects(row: String, seed: Long = 0L): List<Effect> {
        val reading = Reader.read(words, row.split(" "), membership, seed)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, seed).landed.flatMap { it.effects }
    }

    fun set(row: String, part: Aspect, parameter: String, seed: Long = 0L): List<String> =
        effects(row, seed).mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    fun seedsSetting(row: String, part: Aspect, parameter: String): Int = seeds.count { set(row, part, parameter, it).isNotEmpty() }

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("a size is every part's own, but a creature is made a giant only when it is said of the creatures") {
        check(set("colossal sun age", Aspect.SUN, "size") == listOf("1.0..1.0")) { "colossal sun: ${effects("colossal sun age")}" }
        check(set("colossal inhabitants age", Aspect.SPAWNS, "size") == listOf("1.0..1.0")) { "colossal inhabitants were not giants" }
        check(set("colossal age", Aspect.SPAWNS, "size").isEmpty()) { "colossal age made giants" }
        check(effects("colossal landmass age").any { it is Effect.Restricts && "monumental" in it.bars }) { "colossal land leans to nothing monumental" }
    }

    test("an amount, a frequency and a pace: `teeming`, `sporadic` each part one Age in four, `swift` the pace and the colours'") {
        check(Effect.Abundance(4.0) in effects("teeming inhabitants age")) { "teeming inhabitants: ${effects("teeming inhabitants age")}" }
        check(set("sporadic weather age", Aspect.WEATHER, "frequency") == listOf("-1.0..-0.9")) { "sporadic weather was not sporadic" }
        val parts = seeds.map { seed -> effects("sporadic age", seed).filterIsInstance<Effect.Sets>().count { it.parameter.name == "frequency" } }
        check(parts.all { it >= 1 }) { "sporadic age landed nowhere at some seed" }
        check(parts.average() < 2.5) { "sporadic age reached ${parts.average()} parts on average, as if certain" }
        check(set("swift sun age", Aspect.SUN, "pace") == listOf("0.5..1.0") && set("swift sun age", Aspect.SUN, "colour_cycle_pace") == listOf("0.5..1.0")) {
            "swift sun: ${effects("swift sun age")}"
        }
    }

    test("height is each part's own; the sea's depth from the whole Age only a third of the time, and a sun's path only of the sun") {
        check(set("high clouds age", Aspect.CLOUD, "ceiling") == listOf("0.6..1.0")) { "high clouds: ${effects("high clouds age")}" }
        check(set("high sea age", Aspect.SEA, "depth") == listOf("0.6..1.0")) { "high sea did not stand high" }
        val deepFromTheAge = seedsSetting("high age", Aspect.SEA, "depth")
        check(deepFromTheAge in 15..55) { "high age raised the sea at $deepFromTheAge seeds of ${seeds.count()}" }
        check(set("high sun age", Aspect.SUN, "path") == listOf("overhead")) { "high sun did not climb overhead" }
        check(seeds.none { set("high age", Aspect.SUN, "path", it).isNotEmpty() }) { "high age moved the sun" }
    }

    test("depth and burial: `deep sea`, `deep underground`, and buried structures sunk and features lying low") {
        check(set("deep sea age", Aspect.SEA, "depth") == listOf("0.85..0.98")) { "deep sea: ${effects("deep sea age")}" }
        check(set("deep underground age", Aspect.UNDERGROUND, "depth") == listOf("0.85..0.98")) { "deep underground was not deep" }
        check(set("buried structures age", Aspect.STRUCTURES, "height") == listOf("-1.0..-0.5")) { "buried structures were not sunk" }
        check(set("buried features age", Aspect.FEATURES, "height") == listOf("-1.0..-0.5")) { "buried features did not lie low" }
    }

    test("strength and brightness are each thing's own, and a broad word never moves gravity") {
        check(set("strong gravity age", Aspect.GRAVITY, "pull") == listOf("0.3..1.0")) { "strong gravity was not heavy" }
        check(seeds.none { set("strong age", Aspect.GRAVITY, "pull", it).isNotEmpty() }) { "strong age moved gravity" }
        check(set("strong age", Aspect.SPAWNS, "strength") == listOf("0.3..1.0")) { "strong age did not strengthen the creatures" }
        check(set("bright sun age", Aspect.SUN, "light") == listOf("0.6..1.0") && set("bright sun age", Aspect.SUN, "size") == listOf("0.2..0.5")) {
            "bright sun: ${effects("bright sun age")}"
        }
        check(seeds.none { set("bright age", Aspect.SUN, "size", it).isNotEmpty() }) { "bright age enlarged the sun" }
    }

    test("`north wind` blows from the north, and `calm` stills the air and slows the clouds") {
        check(set("north wind age", Aspect.AIR, "from") == listOf("north") && set("north wind age", Aspect.AIR, "wind") == listOf("-0.2..0.6")) {
            "north wind: ${effects("north wind age")}"
        }
        check(set("calm age", Aspect.AIR, "wind") == listOf("-1.0..-1.0")) { "calm age left the wind" }
        check(set("calm age", Aspect.CLOUD, "pace") == listOf("-0.7..-0.4")) { "calm age did not slow the clouds" }
    }
})
