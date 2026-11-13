package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.reading.Draw
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.WorldMembership
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.ParameterKey
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** The phenomena, `star_fissure`, `windswept` and the creatures' dispositions we ship (`notes/the-word-rework.md`, step 6). */
@Tags(NEEDS_REGISTRIES)
class PhenomenaCheck : FunSpec({

    val words by lazy { ShippedWords.with() }
    val membership by lazy { WorldMembership(ShippedCorpus.vocabulary) }
    val seeds = 0L..<80L

    fun landed(row: String, seed: Long = 0L): List<Landing> {
        val reading = Reader.read(words, row.split(" "), membership, seed)
        return Draw.of(reading, words, membership, ShippedCorpus.vocabulary, { false }, seed).landed
    }

    fun effects(row: String, seed: Long = 0L): List<Effect> = landed(row, seed).flatMap { it.effects }

    fun set(row: String, part: Aspect, parameter: String): List<String> = effects(row)
        .mapNotNull(ShippedWords::turned).filter { (key, _) -> key == ParameterKey(parameter, part) }.map { (_, value) -> value }

    test("what we ship loads and fits") {
        check(words.problems.isEmpty()) { "what we ship does not fit: ${words.problems.take(10)}" }
    }

    test("a phenomenon is brought by its word, and takes how often, how strong and which way it blows") {
        check(Effect.Chooses(Aspect.PHENOMENA, "gale") in effects("constant north gale age")) { "no gale: ${effects("constant north gale age")}" }
        check(set("constant north gale age", Aspect.PHENOMENA, "frequency") == listOf("1.0..1.0")) { "the gale was not constant" }
        check(set("constant north gale age", Aspect.AIR, "from") == listOf("north")) { "the gale did not blow from the north" }
        check(set("colossal tornado age", Aspect.PHENOMENA, "strength") == listOf("1.0..1.0")) { "a colossal tornado was not the strongest" }
        check(set("blizzard age", Aspect.AIR, "motes") == listOf("snow") && set("blizzard age", Aspect.CLIMATE, "temperature") == listOf("-1.0..-0.5")) {
            "blizzard: ${effects("blizzard age")}"
        }
    }

    test("`no star_fissure` seals the Age; `windswept age` is the landform and now and then the wind still blowing") {
        check(landed("no star_fissure age").any { it.polarity == Polarity.EXCEPT && Effect.Chooses(Aspect.STRUCTURES, "agesandtheart:star_fissure") in it.effects })
        check(seeds.all { Effect.Chooses(Aspect.TERRAIN, "windswept") in effects("windswept age", it) }) { "windswept age lost its landform" }
        val gales = seeds.count { seed -> effects("windswept age", seed).any { it is Effect.Admits && "gale" in it.members } }
        check(gales in 15..50) { "windswept age brought a gale at $gales seeds of ${seeds.count()}" }
        check(seeds.none { seed -> effects("windswept landmass age", seed).any { it is Effect.Admits && it.aspect == Aspect.PHENOMENA } }) {
            "windswept landmass brought weather"
        }
    }

    test("a disposition is the creatures' it is said of, and every creature's said of the whole Age") {
        check(set("friendly monsters age", Aspect.SPAWNS, "disposition") == listOf("friendly")) { "friendly monsters: ${effects("friendly monsters age")}" }
        check(set("tamable age", Aspect.SPAWNS, "disposition") == listOf("tamable")) { "tamable age tamed nothing" }
    }
})
