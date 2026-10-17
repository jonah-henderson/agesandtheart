package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AspectOptions
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Sky
import com.mojang.serialization.JsonOps
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * `frequency` is one name on the curtain, the bow and the rain, so the four words that set it reach all
 * three — and never put a curtain or a bow into an Age that did not have one.
 */
@Tags(NEEDS_REGISTRIES)
class FrequencyWordsCheck : FunSpec({

    fun AgeComposition.rainFrequency() = optionsFor(Aspect.WEATHER, 0).of(Atmosphere.RAINFALL)

    fun bare(word: String, seed: Long): AgeComposition =
        Resolver.resolve(ShippedCorpus.vocabulary, ShippedCorpus.read(listOf(word, "age")), seed).composition

    test("a bare frequency word brings no curtain and no bow the Age would not have had") {
        for (seed in SEEDS) {
            val plain = Sky.specFor(ShippedCorpus.resolved(seed).composition, seed)
            for (word in WORDS) {
                val sky = Sky.specFor(bare(word, seed), seed)
                check((sky.aurora == null) == (plain.aurora == null)) { "seed $seed: bare '$word' changed whether it has an aurora" }
                check((sky.rainbow == null) == (plain.rainbow == null)) { "seed $seed: bare '$word' changed whether it has a rainbow" }
            }
        }
    }

    test("a bare frequency word is felt by the rain, which is where it lands when nothing is aimed") {
        val frequent = SEEDS.count { bare("frequent", it).rainFrequency() == "0.6..1" }
        check(frequent == SEEDS.count()) { "bare 'frequent' reached the weather in $frequent of ${SEEDS.count()} Ages" }
    }

    test("aimed at the weather, each sets how much of the time it rains") {
        val said = WORDS.associateWith { ShippedCorpus.resolved(SEEDS.first, it, "weather").composition.rainFrequency() }
        check(said["constant"] == "1..1") { "'constant weather' said ${said["constant"]}" }
        check(said["sporadic"] == "-1..-0.9") { "'sporadic weather' said ${said["sporadic"]}" }
        check(said["frequent"] == "0.6..1") { "'frequent weather' said ${said["frequent"]}" }
        check(said["infrequent"] == "-1..-0.6") { "'infrequent weather' said ${said["infrequent"]}" }
    }

    test("aimed at a curtain, each says it, which is how one comes") {
        for (word in WORDS) {
            val sky = Sky.specFor(ShippedCorpus.resolved(SEEDS.first, word, "aurora").composition, SEEDS.first)
            check(sky.aurora != null) { "'$word aurora' brought no curtain" }
        }
    }

    test("an Age saved with the weather's old dial name still reads") {
        val saved = JsonParser.parseString("""{"weather": {"rainfall": "0.5..0.9"}}""")
        val options = AspectOptions.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow()
        check(options.of(Aspect.WEATHER, 0).of(Atmosphere.RAINFALL) == "0.5..0.9") { "the old name was dropped" }
    }
})

private val WORDS = listOf("frequent", "infrequent", "constant", "sporadic")
private val SEEDS = 0L..<60L
