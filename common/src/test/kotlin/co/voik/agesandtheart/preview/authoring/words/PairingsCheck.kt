package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.RUNS_OCCASIONALLY_TAG
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.words.Pairings.Hold
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The acceptance tables, held against words whose answers are known: what takes an offer and whether it holds, a reading
 * restating what its part already holds coming out hollow, an offer nothing takes, and the words offering what a thing
 * takes. And, now and then, no word of ours hollow on the part of the world it speaks for.
 */
@Tags(NEEDS_REGISTRIES)
class PairingsCheck : FunSpec({

    val corpus by lazy { WordCorpus(ShippedCorpus.vocabulary) }

    fun word(name: String): Word = corpus.words.word(name) ?: error("'$name' is no word")

    fun fixture(vararg offers: Offer): Word = Word("pairing_fixture".location(), listOf(Sense(offers = offers.toList())))

    test("a temperature is taken by the climate and holds, and a word's parts offer is placed whatever its defaults") {
        val frozen = Pairings(corpus).of(word("frozen"))
        val temperature = frozen.offers.first { it.offer.concept.path == "temperature" }
        val climate = temperature.takers.firstOrNull { it.who == "climate" }
        check(climate?.hold == Hold.HOLDS) { "frozen's temperature on the climate: ${climate?.hold} (${temperature.takers.map { it.who }})" }
        val porous = Pairings(corpus).of(word("porous"))
        val unplaced = porous.offers.flatMap { it.takers }.filter { it.hold == Hold.NEVER_PLACED }.map { it.who }
        check(unplaced.isEmpty()) { "porous was never placed before $unplaced" }
    }

    test("a reading choosing what its part already holds is hollow") {
        val restating = fixture(Offer(CoreConcepts.READING, audience = Audience.Part(Aspect.UNDERGROUND), reading = listOf(Effect.Chooses(Aspect.CARVERS, "caves"))))
        val table = Pairings(corpus, standIn = restating).of(restating)
        val onTheUnderground = table.offers.single().takers.firstOrNull { it.who.startsWith("underground") }
        check(onTheUnderground?.hold == Hold.CHANGED_NOTHING) { "choosing the caves the rock had came out ${onTheUnderground?.hold}" }
    }

    test("an offer for a part that takes nothing of its concept is taken by nothing") {
        val offeredAstray = fixture(Offer(CoreConcepts.MEMBER, audience = Audience.Part(Aspect.SKY), value = co.voik.agesandtheart.age.words.Value.Text("minecraft:zombie")))
        val table = Pairings(corpus, standIn = offeredAstray).of(offeredAstray)
        check(table.untaken.size == 1) { "a zombie offered to the sky was taken by ${table.offers.single().takers.map { it.who }}" }
    }

    test("what a thing takes lists the words offering it") {
        val rock = Pairings(corpus).of(word("rock"))
        val block = rock.accepts.firstOrNull { it.acceptance.concept == CoreConcepts.BLOCK }
        check(block != null && block.offeredBy.any { it.name == "frozen" }) { "rock's block is offered by ${block?.offeredBy?.map { it.name }}" }
    }

    test("no word of ours is hollow on the part of the world it speaks for").config(tags = setOf(RUNS_OCCASIONALLY_TAG)) {
        val hollow = Pairings(corpus).hollowOnTheParts()
        check(hollow.isEmpty()) { "${hollow.size} hollow:\n  ${hollow.sorted().joinToString("\n  ")}" }
    }
})
