package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.words.Word
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** What lands of a reading once its chances are drawn (`notes/the-word-rework.md`), held against the fixture words. */
@Tags(NEEDS_REGISTRIES)
class DrawCheck : FunSpec({

    val seeds = 0L..<200L

    fun drawn(row: String, seed: Long = 0L, isGated: (Word) -> Boolean = { false }): Drawn {
        val reading = Reader.read(FixtureWords.words, row.split(" "), FixtureWords.membership, seed)
        return Draw.of(reading, FixtureWords.words, FixtureWords.membership, ShippedCorpus.vocabulary, isGated, seed)
    }

    fun Drawn.of(word: String): List<Landing> = landed.filter { it.word == word }

    test("a placement lands on its offer's chance, and the same seed draws the same") {
        val landedAt = seeds.count { seed -> drawn("gloomy sky age", seed).of("gloomy").isNotEmpty() }
        check(landedAt in 70..130) { "an even chance landed at $landedAt seeds of ${seeds.count()}" }
        check(drawn("gloomy sky age", 7L) == drawn("gloomy sky age", 7L)) { "one seed drew two ways" }
        check(drawn("gloomy sky age").possible.any { it.word == "gloomy" }) { "what might land is missing from what could" }
    }

    test("a page `age` hands out lands somewhere, however its chances fall") {
        val nowhere = seeds.filter { seed -> drawn("gloomy age", seed).of("gloomy").isEmpty() }
        check(nowhere.isEmpty()) { "gloomy age landed nowhere at seeds $nowhere" }
        val everywhere = seeds.count { seed -> drawn("gloomy age", seed).of("gloomy").map { it.place.aspect }.toSet().size > 1 }
        check(everywhere > 0) { "an even chance never reached two parts" }
    }

    test("where two clash, the firmer holds, and a page whose every landing was overruled is drawn elsewhere, for one flaw") {
        val skyPhrase = 2
        val winners = seeds.map { seed ->
            val drawn = drawn("red blue sky age", seed)
            val sky = drawn.landed.filter { it.saidOf == skyPhrase && it.effects.any { effect -> effect is Effect.Sets } }
            check(sky.size == 1) { "the sky took ${sky.map { it.word }} at seed $seed" }
            val loser = drawn.dangling.singleOrNull() ?: error("no page dangled at seed $seed: ${drawn.dangling}")
            check(loser.written != sky.single().word && loser.drawnOnto != null) { "the loser at seed $seed was $loser" }
            check(drawn.flaws.map { it.register } == listOf(Register.INCOHERENT)) { "red blue sky cost ${drawn.flaws} at seed $seed" }
            sky.single().word
        }.toSet()
        check(winners == setOf("red", "blue")) { "only $winners ever held" }
        val joined = drawn("red and blue sky age")
        check(joined.dangling.isEmpty() && joined.flaws.isEmpty()) { "`and` did not keep both: $joined" }
        val settled = drawn("huge colossal villages age")
        check(settled.dangling.isEmpty() && settled.flaws.isEmpty()) { "two sizes that settle together clashed: ${settled.flaws}" }
    }

    test("a dangling page is drawn onto a word that takes it, never a gated one, and that word's phrase lands whole") {
        val ontoHerds = seeds.map { drawn("sheep fliers age", it) }.firstOrNull { it.dangling.single().drawnOnto == "herds" }
            ?: error("sheep was never drawn onto herds")
        val herds = ontoHerds.of("herds").flatMap { it.effects }
        check(Effect.Abundance(2.0) in herds) { "the herds the sheep were drawn onto did not land whole: $herds" }
        check(ontoHerds.of("sheep").any { Effect.Chooses(Aspect.SPAWNS, "minecraft:sheep") in it.effects }) { "the sheep did not land in the herds" }
        val gated = seeds.mapNotNull { seed -> drawn("sheep fliers age", seed) { it.name == "herds" }.dangling.single().drawnOnto }.toSet()
        check(gated == setOf("inhabitants")) { "with herds gated, sheep was drawn onto $gated" }
        val alone = drawn("no age").dangling.single()
        check(alone.written == "no" && alone.drawnOnto == null) { "a structure word that bound nothing was drawn onto ${alone.drawnOnto}" }
    }

    test("a thing's landings are said of its members, or of a body of its own") {
        val villages = drawn("colossal villages age").of("colossal").single()
        check(villages.place.members == Members.Named(setOf("minecraft:villages"))) { "colossal was said of ${villages.place}" }
        check(villages.saidOf == 1) { "colossal was said of page ${villages.saidOf}" }
        val suns = drawn("blue sun sun age").landed.filter { it.place.aspect == Aspect.SUN }.map { it.place.members }.toSet()
        check(suns == setOf(Members.Body(0), Members.Body(1))) { "two suns were $suns" }
    }

    test("a thing naming members it is given descriptions of only describes them, asking for none") {
        val described = drawn("colossal villages age").landed.flatMap { it.effects }
        check(described.none { it is Effect.Chooses }) { "colossal villages asked for villages: $described" }
        val asked = drawn("villages age").landed.flatMap { it.effects }
        check(Effect.Chooses(Aspect.STRUCTURES, "minecraft:villages") in asked) { "villages alone asked for none: $asked" }
    }

    test("a negated thing is one strike, its description narrowing it: `no red trees` strikes the woody kinds carrying red") {
        val struck = drawn("no red trees age").landed.filter { it.polarity == Polarity.EXCEPT }
        check(struck.size == 1) { "no red trees struck ${struck.size} ways: $struck" }
        val bars = struck.single().effects.filterIsInstance<Effect.Restricts>().flatMap { it.bars.keys }.toSet()
        check(bars == setOf("woody", "red")) { "the strike was barred by $bars" }
    }

    test("an acceptance may refuse what `age` hands out, and what dangles: `porous age` holes the rock and leaves the features whole") {
        val wholeAge = drawn("porous age")
        val reached = wholeAge.of("porous").map { it.place.aspect }.toSet()
        check(reached == setOf(Aspect.CARVERS)) { "porous age reached $reached" }
        check(Effect.Chooses(Aspect.CARVERS, "porous") in wholeAge.of("porous").flatMap { it.effects } && wholeAge.dangling.isEmpty())
        val drawnOnto = seeds.map { drawn("age porous", it).dangling.single().drawnOnto }.toSet()
        check(drawnOnto == setOf("rock", "arches")) { "a dangling porous was drawn onto $drawnOnto, where the features refuse it" }
        val said = drawn("porous features age").of("porous").flatMap { it.effects }
        check(Effect.Sets(ParameterKey("porosity", Aspect.FEATURES), "0.5..1.0") in said) { "porous features turned $said" }
    }

    test("a book is what was drawn: a flaw per dangling page, naming the word it was drawn onto, and every page's price") {
        val writing = Writing(FixtureWords.words, ShippedCorpus.vocabulary, FixtureWords.membership) { false }
        val reading = Reader.read(FixtureWords.words, "sheep fliers no age".split(" "), FixtureWords.membership, 0L)
        val book = writing.bookOf(reading, 0L)
        val dangles = book.chargedBeforeResolving.filter { it.register == Register.DANGLES }
        check(dangles.map { it.words.first() }.toSet() == setOf("sheep", "no")) { "the dangling pages were charged as $dangles" }
        check(dangles.first { it.words.first() == "sheep" }.words.size == 2) { "sheep's flaw does not say where it went" }
        check(book.cost == reading.pages.sumOf { FixtureWords.words.priceOf(FixtureWords.words.word(it.written) ?: error("")) })
        val resolved = writing.resolve("colossal villages age".split(" "), 0L)
        check(resolved.instability.isCoherent) { "colossal villages was charged: ${resolved.instability.flaws}" }
    }
})
