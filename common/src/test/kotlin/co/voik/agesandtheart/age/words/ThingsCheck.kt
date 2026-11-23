package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Drawn
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The groups, the formation, the mintings and the trees we ship (`notes/the-word-rework.md`, step 6). */
@Tags(NEEDS_REGISTRIES)
class ThingsCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }

    fun drawn(row: String): Drawn {
        val reading = Reader.read(words, row.split(" "), membership, 0L)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, 0L)
    }

    fun landed(row: String): List<Landing> = drawn(row).landed

    fun effects(row: String): List<Effect> = landed(row).flatMap { it.effects }

    fun set(row: String, part: Aspect, parameter: String): List<String> = effects(row)
        .mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("a group takes what its part takes, of its own members: `oak trees`, `red oak trees`; `bat fish` refused, `zombie trees` two things") {
        check(Effect.Chooses(Aspect.FEATURES, "minecraft:oak") in effects("oak trees age")) { "oak trees: ${effects("oak trees age")}" }
        check(set("red trees age", Aspect.FEATURES, "colour") == listOf("red")) { "red trees: ${effects("red trees age")}" }
        val redOaks = "red oak trees age"
        check(set(redOaks, Aspect.FEATURES, "colour") == listOf("red") && set(redOaks, Aspect.LEAVES, "colour").isEmpty()) {
            "red oak trees coloured every tree's leaves: ${effects(redOaks)}"
        }
        check(effects(redOaks).none { it is Effect.Chooses }) { "red oak trees asked for oaks as well as colouring them: ${effects(redOaks)}" }
        check(set("colossal trees age", Aspect.FEATURES, "size") == listOf("1.0..1.0")) { "colossal trees took nothing of the features' page" }
        check(drawn("bat fish age").dangling.any { it.written == "bat" }) { "bat fish were read as fish" }
        val besideTheTrees = effects("zombie trees age")
        check(Effect.Chooses(Aspect.SPAWNS, "minecraft:zombie") in besideTheTrees && besideTheTrees.none { it is Effect.Chooses && it.aspect == Aspect.FEATURES }) {
            "zombie trees were not a zombie beside the trees: $besideTheTrees"
        }
        check(Effect.Chooses(Aspect.FEATURES, "minecraft:birch_checked") in effects("birch trees age")) { "birch trees: ${effects("birch trees age")}" }
    }

    test("a ban after the last `age` is the Age's, and a page written with `no` that nothing takes lands nothing") {
        check(Effect.Bans("minecraft:dirt") in effects("age no dirt")) { "age no dirt: ${effects("age no dirt")}" }
        val stranded = drawn("no sheep fliers age")
        check(stranded.dangling.singleOrNull()?.let { it.written == "sheep" && it.drawnOnto == null } == true) {
            "a negated sheep nothing took was drawn as ${stranded.dangling}"
        }
        check(stranded.landed.none { it.word == "sheep" }) { "a negated sheep landed: ${stranded.landed}" }
    }

    test("a single tree is said of what holds it, never standing alone: `birch trees`, `birch age`, and `age birch` dangling") {
        check(Effect.Chooses(Aspect.FEATURES, "minecraft:birch_checked") in effects("birch age")) { "birch age: ${effects("birch age")}" }
        check(drawn("age birch").dangling.any { it.written == "birch" }) { "a birch stood alone" }
        check(drawn("age spruce").dangling.any { it.written == "spruce" }) { "a derived spruce stood alone" }
        check(Effect.Chooses(Aspect.FEATURES, "minecraft:spruce") in effects("spruce trees age")) { "spruce trees: ${effects("spruce trees age")}" }
        check(drawn("age jungle_tree").dangling.isEmpty()) { "jungle_tree, a noun, did not stand as a thing" }
    }

    test("`no monsters` strikes every hostile creature, and `teeming monsters` asks four times as many") {
        val struck = landed("no monsters age").filter { it.polarity == Polarity.EXCEPT }
        check(struck.any { landing -> landing.effects.any { it is Effect.Restricts && "hostile" in it.bars } }) { "no monsters: $struck" }
        check(Effect.Abundance(4.0) in effects("teeming monsters age")) { "teeming monsters were not teeming" }
    }

    test("herds are of whatever creature is named, and their size is how many arrive together") {
        check(Effect.Chooses(Aspect.SPAWNS, "minecraft:zombie") in effects("zombie herds age")) { "zombie herds: ${effects("zombie herds age")}" }
        check(Effect.Chooses(Aspect.SPAWNS, "minecraft:warden") in effects("herds of warden age")) { "herds of warden: ${effects("herds of warden age")}" }
        check(set("colossal herds age", Aspect.SPAWNS, "herd") == listOf("1.0..1.0")) { "colossal herds: ${effects("colossal herds age")}" }
    }

    test("a minting is made of what its phrase names, at the size, height and spacing it asks") {
        val obelisks = effects("colossal gold_block obelisks age")
        check(obelisks.any { it is Effect.Mints && it.pattern == "agesandtheart:obelisks" }) { "no obelisks: $obelisks" }
        check(Effect.MadeOf("minecraft:gold_block") in obelisks) { "the obelisks were not of gold: $obelisks" }
        check(set("colossal gold_block obelisks age", Aspect.FEATURES, "size") == listOf("1.0..1.0")) { "the obelisks were not colossal" }
        check(set("high obelisks age", Aspect.FEATURES, "tallness") == listOf("0.6..1.0")) { "high obelisks were not tall" }
        check(set("high lakes age", Aspect.FEATURES, "altitude") == listOf("0.6..1.0")) { "high lakes did not lie high" }
        check(effects("monuments age").count { it is Effect.Mints } == 7) { "monuments did not mint every monumental shape" }
    }

    test("monuments lean the structures toward the monumental and the underground toward great halls, and no more") {
        val leans = effects("monuments age").filterIsInstance<Effect.Leans>()
        check(Effect.Leans(Aspect.STRUCTURES, mapOf("#monumental" to 1.0)) in leans) { "monuments: $leans" }
        check(Effect.Leans(Aspect.UNDERGROUND, mapOf("great_halls" to 1.0)) in leans) { "monuments: $leans" }
        check(leans.none { it.aspect == Aspect.FEATURES || it.aspect == Aspect.TERRAIN }) { "monuments leaned too far: $leans" }
    }
})
