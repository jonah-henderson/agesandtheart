package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.reward.PaperTreeWindow
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a word laid bare is felt part by part at its [Word.unaimed] chances, and aimed is felt in full where
 * it lands — through the real parser and resolver. `polar age`, `polar climate` and `polar sun` are the
 * three sentences the rule exists for.
 */
@Tags(NEEDS_REGISTRIES)
class UnaimedCheck : FunSpec({

    fun bare(word: String, seed: Long): AgeComposition =
        Resolver.resolve(ShippedCorpus.vocabulary, ShippedCorpus.read(listOf(word, "age")), seed).composition

    fun aimed(seed: Long, vararg pages: String): AgeComposition = ShippedCorpus.resolved(seed, *pages).composition

    fun AgeComposition.sunPath() = optionsFor(Aspect.SUN, 0).of(SkyBodies.PATH)

    fun AgeComposition.temperature() = optionsFor(Aspect.CLIMATE, 0).of(ClimateAxis.TEMPERATURE.parameter)

    test("a polar Age is always cold, and has a polar sun about one Age in four") {
        val ages = SEEDS.map { bare("polar", it) }
        check(ages.all { it.temperature() == COLD }) { "a polar Age came out warmer than polar" }
        val share = ages.count { it.sunPath() == POLAR }.toDouble() / ages.size
        check(share in RARELY..OFTEN) { "a polar Age had a polar sun ${share * 100}% of the time" }
    }

    test("a polar climate is cold, and leaves the sun alone") {
        for (seed in SEEDS) {
            val age = aimed(seed, "polar", "climate")
            check(age.temperature() == COLD) { "seed $seed: 'polar climate' left it at ${age.temperature()}" }
            check(age.sunPath() != POLAR) { "seed $seed: 'polar climate' reached the sun" }
        }
    }

    test("a polar sun always circles the pole, and leaves the climate alone") {
        for (seed in SEEDS) {
            val age = aimed(seed, "polar", "sun")
            check(age.sunPath() == POLAR) { "seed $seed: 'polar sun' left the path at ${age.sunPath()}" }
            check(age.temperature() == aimed(seed).temperature()) { "seed $seed: 'polar sun' reached the climate" }
        }
    }

    test("a polar sun is the paper tree's sky") {
        val age = aimed(SEEDS.first, "polar", "sun")
        check(PaperTreeWindow.isPolar(Sky.specFor(age, SEEDS.first))) { "'polar sun' did not read as polar" }
    }

    test("a page costs the same wherever it is laid, and whatever the roll") {
        val polar = ShippedCorpus.vocabulary.word("polar") ?: error("no 'polar'")
        fun costOf(pages: List<String>, seed: Long) =
            Resolver.resolve(ShippedCorpus.vocabulary, ShippedCorpus.read(pages), seed).cost
        val bare = SEEDS.map { costOf(listOf("polar", "age"), it) }.toSet()
        check(bare.size == 1) { "one bare book cost $bare across seeds" }
        val structure = costOf(listOf("age"), SEEDS.first)
        check(bare.single() - structure == polar.price) { "bare 'polar' was charged ${bare.single() - structure}" }
    }
})

private val SEEDS = 0L..<200L
private const val POLAR = "polar"
private const val COLD = "-0.95..-0.45"

/** A quarter, give or take what two hundred seeds can tell apart. */
private const val RARELY = 0.15
private const val OFTEN = 0.35
