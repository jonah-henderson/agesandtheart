package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The colours and the materials we ship (`notes/the-word-rework.md`, step 6's second batch), read as written. */
@Tags(NEEDS_REGISTRIES)
class ColoursCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }
    val seeds = 0L..<100L

    fun landed(row: String, seed: Long = 0L): List<Landing> {
        val reading = Reader.read(words, row.split(" "), membership, seed)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, seed).landed
    }

    fun effects(row: String, seed: Long = 0L): List<Effect> = landed(row, seed).flatMap { it.effects }

    fun set(row: String, part: Aspect, parameter: String, seed: Long = 0L): List<String> =
        effects(row, seed).mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("a colour is every part's own: `red sky` the vault, `red age` the vault and the air together") {
        check(set("red sky age", Aspect.SKY, "colour") == listOf("red")) { "red sky: ${landed("red sky age")}" }
        check(set("red age", Aspect.SKY, "colour") == listOf("red") && set("red age", Aspect.AIR, "colour") == listOf("red")) {
            "red age: ${landed("red age")}"
        }
        check(effects("red age").any { it is Effect.Leans && it.aspect == Aspect.BIOMES && "minecraft:badlands" in it.weights }) {
            "red age leaned no biome toward the badlands"
        }
    }

    test("a colour builds what it is said of, and the whole Age one time in ten") {
        check(seeds.all { set("red rock age", Aspect.TERRAIN, "stone", it) == listOf("#red") }) { "red rock was not always of red stone" }
        val villages = landed("red villages age").filter { it.word == "red" && it.place.aspect == Aspect.STRUCTURES }
        check(villages.single().effects == listOf(Effect.Sets(ParameterKey("material", Aspect.STRUCTURES), "#red"))) { "red villages: $villages" }
        check(villages.single().place.members == Members.Named(setOf("minecraft:villages"))) { "red was said of ${villages.single().place}" }
        val redStone = seeds.count { set("red age", Aspect.TERRAIN, "stone", it).isNotEmpty() }
        check(redStone in 2..25) { "red age built red stone at $redStone seeds of ${seeds.count()}" }
    }

    test("`colourless` drains the air, whitens what has a colour, and builds of one of black, grey or white") {
        check(set("colourless age", Aspect.AIR, "drained") == listOf("true")) { "colourless age did not drain the air" }
        check(set("colourless age", Aspect.SKY, "colour") == listOf("white")) { "colourless age: ${landed("colourless age")}" }
        val stones = seeds.map { set("colourless rock age", Aspect.TERRAIN, "stone", it) }
        check(stones.all { it.size == 1 }) { "colourless rock built of ${stones.first { it.size != 1 }}" }
        check(stones.flatten().toSet() == setOf("#black", "#grey", "#white")) { "colourless rock was only ever ${stones.flatten().toSet()}" }
    }

    test("`molten` holds the sea to molten ones; said of the underground, and only then, it opens magma chambers") {
        val chambers = Effect.Admits(Aspect.FEATURES, setOf("agesandtheart:magma_chamber"))
        check(effects("molten sea age").any { it is Effect.Restricts && it.aspect == Aspect.SEA }) { "molten sea: ${landed("molten sea age")}" }
        check(chambers in effects("molten underground age")) { "molten underground opened no magma chamber" }
        check(seeds.none { chambers in effects("molten age", it) }) { "molten age opened magma chambers" }
    }

    test("`volcanic` brings its four features, and said of the land the land they stand in") {
        val features = setOf("agesandtheart:volcano", "agesandtheart:firespout", "agesandtheart:magma_chamber", "agesandtheart:lava_tubes")
        val ofTheAge = effects("volcanic age").filterIsInstance<Effect.Admits>().flatMap { it.members }.toSet()
        check(ofTheAge == features) { "volcanic age brought $ofTheAge" }
        val ofTheLand = effects("volcanic landmass age")
        check(ofTheLand.filterIsInstance<Effect.Admits>().flatMap { it.members }.toSet() == features) { "volcanic landmass: $ofTheLand" }
        check(Effect.Sets(ParameterKey("stone", Aspect.TERRAIN), "#molten") in ofTheLand) { "volcanic landmass is not of molten rock" }
        check(seeds.none { set("volcanic age", Aspect.TERRAIN, "stone", it).isNotEmpty() }) { "volcanic age set the stone" }
    }
})
