package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Speech
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.location
import io.kotest.core.spec.style.FunSpec

/** The edits a word takes in Scrivener, each a pure function keeping what its file can hold. */
class WordEditsCheck : FunSpec({

    val wear = "wear".location()
    val colour = "colour".location()
    val porousWay = Value.Parts(mapOf("way" to "porous"))

    test("a word built by edits is the word its file holds: `porous`, from nothing") {
        val held = WordFile.read("porous").getOrThrow()
        val rock = Aspect.byPage("rock") ?: error("no rock")
        val built = Candidate.blank("porous".location()).copy(comment = held.comment).changingWord { word ->
            word.copy(speech = Speech.ADJECTIVE).changingSense(0) { sense ->
                sense.withOffer(Offer(wear, porousWay).withChanceFromWholeAge(0.0))
                    .withOffer(Offer(wear, porousWay, audience = Audience.Part(rock)))
            }
        }
        check(WordFile.textOf(built) == WordFile.textOf(held)) { "built:\n${WordFile.textOf(built)}\nheld:\n${WordFile.textOf(held)}" }
    }

    test("one sense is the default, an acceptance of a concept replaces the one standing, and a chance is a share") {
        val word = Candidate.blank("beds".location()).word.withSense().withSense().withDefaultSense(1).withDefaultSense(2)
        check(word.senses.map(Sense::isDefault) == listOf(false, false, true)) { "defaults: ${word.senses.map(Sense::isDefault)}" }
        check(word.withoutSense(0).senses.size == 2) { "a sense was not taken out" }
        val folding = Acceptance(colour, outcome = Outcome.Folds)
        val setting = Acceptance(colour, outcome = Outcome.Becomes(listOf(EffectTemplate.Taking(EffectTemplate.Verb.SETS, "colour"))))
        val sense = Sense().withAcceptance(folding).withAcceptance(setting)
        check(sense.accepts == listOf(setting)) { "accepts: ${sense.accepts}" }
        val turned = sense.changingAcceptance(0) { it.changingEffects { effects -> effects.drop(1) } }
        check(turned.accepts.single().outcome == Outcome.Becomes(emptyList())) { "effects: ${turned.accepts}" }
        val offer = Offer(colour, Value.Text("red")).withChance(1.5).withChanceFromWholeAge(-1.0)
        check(offer.chance == Offer.CERTAIN && offer.chanceFromWholeAge == Offer.NEVER) { "chances: $offer" }
        check(offer.withChanceFromWholeAge(null).chanceFromWholeAge == offer.chance) { "a cleared Age chance did not follow the chance" }
    }

    test("an effect joins its kind at its place, and an entry is taken out alone until none is left") {
        val oak = Effect.Admits(Aspect.FEATURES, setOf("minecraft:oak"))
        val birch = Effect.Admits(Aspect.FEATURES, setOf("minecraft:birch"))
        val warm = Effect.Sets(ParameterKey("temperature", Aspect.CLIMATE), "0.2..0.6")
        val effects = emptyList<Effect>().withEffect(oak).withEffect(warm).withEffect(birch)
        check(effects == listOf(Effect.Admits(Aspect.FEATURES, setOf("minecraft:oak", "minecraft:birch")), warm)) { "joined: $effects" }
        val lessOak = effects.withoutEntry(0, "minecraft:oak")
        check(lessOak.first() == birch) { "taking out the oak: $lessOak" }
        check(lessOak.withoutEntry(0, "minecraft:birch") == listOf(warm)) { "the last entry left its effect standing" }
        val leaning = listOf(Effect.Leans(Aspect.BIOMES, mapOf("#frozen" to -1.0))).withEntryValue(0, "#frozen", 0.5)
        check(leaning == listOf(Effect.Leans(Aspect.BIOMES, mapOf("#frozen" to 0.5)))) { "the weight: $leaning" }
    }
})
