package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The part pages and structure words we ship (`notes/the-word-rework.md`, step 6's first batch): every setting a word
 * may turn is reachable through its part's page, and an idea lands on each part as that part reads it.
 */
@Tags(NEEDS_REGISTRIES)
class PartPagesCheck : FunSpec({

    /** Describing words of the kind the later batches write, each offering one idea. */
    val describing = mapOf(
        "high" to """{ "offers": [ { "concept": "height", "value": "0.5..1.0" } ] }""",
        "weak" to """{ "offers": [ { "concept": "strength", "value": "-1.0..-0.3" } ] }""",
        "north" to """{ "offers": [ { "concept": "direction", "value": "north" } ] }""",
        "ancient" to """{ "offers": [ { "concept": "wear", "value": "0.45..1.0" } ] }""",
        "motionless" to """{ "offers": [ { "concept": "still" } ] }""",
        "rising" to """{ "offers": [ { "concept": "climbing" } ], "accepts": [ { "concept": "direction", "renames": "rising" } ] }""",
    )
    val words by lazy { ShippedWords.with(describing) }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }

    fun landed(row: String): List<Landing> {
        val reading = Reader.read(words, row.split(" "), membership, 0L)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, 0L).landed
    }

    fun sets(row: String, part: Aspect, parameter: String): List<String> = landed(row).flatMap { it.effects }
        .filterIsInstance<Effect.Sets>().filter { it.parameter == ParameterKey(parameter, part) }.map { it.value }

    test("the part pages, structure words and concepts we ship load, and fit together") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
        val missing = Aspect.entries.filter { words.partPage(it) == null }
        check(missing.isEmpty()) { "parts without a page: $missing" }
    }

    test("every setting a word may turn is reachable through its part's page") {
        val unreached = setOf("caps", "altitude", "wool")
        fun turns(sense: Sense, parameter: String) = sense.acceptances.any { acceptance ->
            val becomes = (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty()
            becomes.any { template ->
                when (template) {
                    is EffectTemplate.Taking -> ParameterKey.read(template.target).name == parameter
                    is EffectTemplate.Fixed -> (template.effect as? Effect.Sets)?.parameter?.name == parameter
                }
            }
        }
        val gaps = Aspect.entries.flatMap { part ->
            val page = words.partPage(part)?.defaultSense ?: return@flatMap listOf("${part.page}: no page")
            part.understood.map { it.name }.distinct()
                .filterNot { it.startsWith("former_") || it in unreached }
                .filterNot { turns(page, it) }
                .map { "${part.page}.$it" }
        }
        check(gaps.isEmpty()) { "settings no page turns: $gaps" }
    }

    test("one idea, each part's own reading: `high clouds` lift the deck, `high landmass` towers, `high sea` stands deep") {
        check(sets("high clouds age", Aspect.CLOUD, "ceiling") == listOf("0.5..1.0")) { "high clouds: ${landed("high clouds age")}" }
        check(sets("high landmass age", Aspect.TERRAIN, "relief") == listOf("0.5..1.0")) { "high landmass: ${landed("high landmass age")}" }
        check(sets("high sea age", Aspect.SEA, "depth") == listOf("0.5..1.0")) { "high sea: ${landed("high sea age")}" }
    }

    test("a compass point is a bearing to the land and a wind to the air; before `rising`, where a sun comes up") {
        check(sets("north landmass age", Aspect.TERRAIN, "bearing") == listOf("-1.0..-1.0")) { "north landmass: ${landed("north landmass age")}" }
        check(sets("north age", Aspect.AIR, "from") == listOf("north")) { "north age: ${landed("north age")}" }
        check(sets("north rising sun age", Aspect.SUN, "rising") == listOf("north")) { "north rising sun: ${landed("north rising sun age")}" }
        check(sets("rising sea age", Aspect.SEA, "climbing") == listOf("true")) { "rising sea: ${landed("rising sea age")}" }
    }

    test("what no broad word should give is said only of its part: `weak gravity` is light, `weak age` leaves it alone") {
        check(sets("weak gravity age", Aspect.GRAVITY, "pull") == listOf("-1.0..-0.3")) { "weak gravity: ${landed("weak gravity age")}" }
        val weakAge = landed("weak age")
        check(weakAge.none { it.place.aspect == Aspect.GRAVITY }) { "weak age lowered gravity: $weakAge" }
        check(weakAge.any { it.place.aspect == Aspect.SPAWNS }) { "weak age did not weaken the creatures: $weakAge" }
        check(landed("motionless age").none { it.place.aspect == Aspect.SUN }) { "motionless age stopped the sun" }
        check(sets("motionless sun age", Aspect.SUN, "still") == listOf("true")) { "motionless sun did not stand still" }
    }

    test("wear is the land weathered and what was built crumbled") {
        check(sets("ancient landmass age", Aspect.TERRAIN, "wear") == listOf("0.45..1.0"))
        check(sets("ancient features age", Aspect.FEATURES, "crumble") == listOf("0.45..1.0"))
    }

    test("a block is the sea it can be, and `aurora` brings one") {
        val lava = landed("lava sea age").flatMap { it.effects }
        check(Effect.Chooses(Aspect.SEA, "minecraft:lava") in lava) { "lava sea: $lava" }
        val aurora = landed("aurora age").flatMap { it.effects }
        check(Effect.Admits(Aspect.PHENOMENA, setOf("aurora")) in aurora) { "aurora age: $aurora" }
    }
})
