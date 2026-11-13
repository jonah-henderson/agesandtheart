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

/** The qualities we ship: the bare, the fallen, the worn and the holed, the cavernous and the charged (step 6). */
@Tags(NEEDS_REGISTRIES)
class QualitiesCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }
    val seeds = 0L..<40L

    fun effects(row: String, seed: Long = 0L): List<Effect> {
        val reading = Reader.read(words, row.split(" "), membership, seed)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, seed).landed.flatMap { it.effects }
    }

    fun set(row: String, part: Aspect, parameter: String, seed: Long = 0L): List<String> = effects(row, seed)
        .mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("`bare` strips each thing its own way: leafless trees, the ground, no clouds; `bare rock` is the ground stripped") {
        check(set("bare trees age", Aspect.FEATURES, "bare") == listOf("true")) { "bare trees: ${effects("bare trees age")}" }
        check(set("bare clouds age", Aspect.CLOUD, "absent") == listOf("true")) { "bare clouds kept their clouds" }
        check(set("bare sky age", Aspect.CLOUD, "absent") == listOf("true")) { "a bare sky kept its clouds" }
        check(set("bare rock age", Aspect.SURFACE, "bare") == listOf("true")) { "bare rock did not strip the ground" }
    }

    test("`fallen` fells trees and stones; `fallen stars` are meteors, and only said of the stars") {
        check(set("fallen trees age", Aspect.FEATURES, "fallen") == listOf("true")) { "fallen trees stood" }
        check(Effect.Admits(Aspect.PHENOMENA, setOf("meteors")) in effects("fallen stars age")) { "fallen stars brought no meteors" }
        check(Effect.Admits(Aspect.PHENOMENA, setOf("meteors")) !in effects("fallen age")) { "fallen age brought meteors" }
    }

    test("`porous` is a way of wear: the land holed, `ancient porous` at a great amount, and of the whole Age only the rock") {
        check(set("porous landmass age", Aspect.TERRAIN, "porosity") == listOf("0.2..0.5")) { "porous landmass: ${effects("porous landmass age")}" }
        check(set("ancient porous landmass age", Aspect.TERRAIN, "porosity") == listOf("0.6..1.0")) { "ancient porous landmass: ${effects("ancient porous landmass age")}" }
        check(seeds.all { Effect.Chooses(Aspect.CARVERS, "porous") in effects("porous age", it) }) { "porous age did not hole the rock" }
        check(seeds.none { set("porous age", Aspect.TERRAIN, "porosity", it).isNotEmpty() }) { "porous age holed the land" }
    }

    test("wear and order: `eroded` the landform and crumbling, `worn` and `ancient` the land worn, `ordered` it kept") {
        check(Effect.Chooses(Aspect.TERRAIN, "eroded") in effects("eroded landmass age")) { "eroded landmass: ${effects("eroded landmass age")}" }
        check(set("eroded structures age", Aspect.STRUCTURES, "crumble") == listOf("0.45..1.0")) { "eroded structures stood whole" }
        check(set("worn landmass age", Aspect.TERRAIN, "wear") == listOf("0.45..1.0")) { "worn landmass: ${effects("worn landmass age")}" }
        check(set("ordered age", Aspect.TERRAIN, "wear") == listOf("-0.8..-0.3")) { "ordered age wore the land" }
    }

    test("the cavernous words: `riddled` rock and underground, `hollow` and `delved` their underground") {
        check(effects("riddled age").any { it is Effect.Restricts && it.aspect == Aspect.CARVERS && "cavernous" in it.bars }) { "riddled age: ${effects("riddled age")}" }
        check(Effect.Chooses(Aspect.UNDERGROUND, "hollow") in effects("hollow age")) { "hollow age: ${effects("hollow age")}" }
        check(Effect.Chooses(Aspect.UNDERGROUND, "great_halls") in effects("delved age")) { "delved age: ${effects("delved age")}" }
    }

    test("`charged`: thunder, the aurora and storms of the whole Age; a charged sky's aurora; a charged creeper, and only that") {
        val age = effects("charged age")
        check(set("charged age", Aspect.WEATHER, "thunder") == listOf("0.7..1.0")) { "charged age did not thunder: $age" }
        check(Effect.Admits(Aspect.PHENOMENA, setOf("aurora")) in age && Effect.Admits(Aspect.PHENOMENA, setOf("tempest")) in age) { "charged age: $age" }
        check(set("charged sky age", Aspect.AURORA, "frequency") == listOf("0.9..1")) { "a charged sky hung no aurora" }
        check(set("charged creeper age", Aspect.SPAWNS, "charged") == listOf("true")) { "a charged creeper was not charged: ${effects("charged creeper age")}" }
        check(set("charged age", Aspect.SPAWNS, "charged").isEmpty()) { "charged age charged the creepers" }
    }
})
